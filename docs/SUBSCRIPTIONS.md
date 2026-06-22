# Register-interest / subscriptions — scoping

Status: **scoping only** (no implementation yet). This is the largest remaining subsystem and the
first that requires **server&rarr;client push** — today the shim is purely request/response. Findings
below are grounded in a real capture against Geode 1.15 (`tools/SubscriptionCapture`, with mutations
triggered from gfsh).

## Connection model (the big change)

A subscription-enabled client opens **three** connections to the server, each starting with a
different communication-mode byte (the shim today only handles mode 100):

| Mode byte | `CommunicationMode` | Role |
|-----------|---------------------|------|
| `100` (0x64) | ClientToServer | normal ops (GET/PUT/QUERY/…) — handled today |
| `107` (0x6b) | ClientToServerForQueue | subscription **control**: REGISTER/UNREGISTER_INTEREST requests + responses (incl. the GII initial image) |
| `101` (0x65) | PrimaryServerToClient | the server&rarr;client **push feed**: server acks with `105` (SuccessfulServerToClient), then streams events |

So the shim's acceptor must branch on the first byte and treat 101/107 connections specially. The
`101` feed connection is **long-lived** and the server pushes asynchronously down it.

Handshake observed: the `101` feed C2S is `65 ff0096 3b 00000000 01 26 56 01 5c04 …clientProxyId…`;
the server replies a single byte `69` (=105 Successful) + a server-identity handshake, then events.

## Opcodes

- Requests (on the 107 control connection): `REGISTER_INTEREST=20`, `REGISTER_INTEREST_LIST=24`,
  `UNREGISTER_INTEREST=22`, `UNREGISTER_INTEREST_LIST=25` (errors: 21/23). Register-interest parts
  include region, interest type (ALL_KEYS / regex / key), and `InterestResultPolicy`
  (NONE/KEYS/KEYS_VALUES). KEYS_VALUES returns an initial image (GII) of matching entries — the
  capture's 107 connection carried a ~12.7 KB response for an `ALL_KEYS` register against a populated
  region.
- Pushed events (on the 101 feed): `CLIENT_MARKER=54` (sent first), then `LOCAL_CREATE=27`,
  `LOCAL_UPDATE=28`, `LOCAL_DESTROY=16`, `LOCAL_INVALIDATE=15`. Each event carries the region, key,
  value (for create/update), and an **EventID** (membershipId + threadId + sequenceId, a
  DataSerializableFixedID). `SERVER_TO_CLIENT_PING=99` keeps the feed alive; `PERIODIC_ACK=52` is the
  client acking received events; `CLIENT_READY=53` / `MAKE_PRIMARY=31` relate to durable/redundant
  feeds. Non-durable clients receive events immediately after register-interest (no readyForEvents).

## What a first cut requires

1. **Connection-mode dispatch + feed handshake** — read the first byte in the acceptor; for `101`,
   do the Successful (105) handshake and retain the channel as this client's feed; for `107`, handle
   it like an op connection that also accepts interest requests.
2. **Interest registry** — per client (by proxy/membership id) the set of region + interest
   (ALL_KEYS / regex / key-list), built/cleared by REGISTER/UNREGISTER_INTEREST.
3. **Register-interest response** — at minimum an ack; for `KEYS_VALUES`, the GII of matching entries.
4. **Event generation** — hook every mutation (PUT/REMOVE/INVALIDATE/putAll/clear, from *any*
   connection) → match against all clients' interests → build a `LOCAL_*` message with a generated
   EventID → push down each interested client's `101` feed (preceded once by a `CLIENT_MARKER`).
5. **Event framing** — new server→client builders for CLIENT_MARKER + LOCAL_CREATE/UPDATE/DESTROY/
   INVALIDATE and the EventID object (byte-matched to the capture).
6. **Feed lifecycle** — SERVER_TO_CLIENT_PING keepalive, PERIODIC_ACK draining, channel close cleanup.

## Hard constraints / honest limitations

- **Architecture inversion.** Server-initiated push is new; it needs an async per-client outbound
  queue and careful Netty channel ownership (the feed channel is written from mutation threads).
- **Cross-replica eventing requires a backplane (now pluggable; opt-in).** In-memory delivery reaches
  only clients on the *same shim instance*; behind a load balancer a mutation on replica A is otherwise
  invisible to an interested client on replica B (Couchbase KV is not a message bus). This is now solved
  by a **pluggable `EventBackplane`** (`com.protogemcouch.subscription`): each `publish*` delivers
  locally *and* broadcasts a `RemoteEvent`, and `applyRemote` re-delivers events from other replicas
  through the same delivery cores (a replica drops its own echoes by `originInstanceId`). The default is
  `NoOpEventBackplane` (single-instance, zero dependency). Two concrete transports ship behind the same
  seam (select with `EVENT_BACKPLANE`):
  - **`mesh` — self-contained, broker-free (no external dependency; the recommended end state).** Each
    replica runs a small TCP listener (`MESH_PORT`, default `40406`) and broadcasts each event directly
    to its peers as a length-prefixed frame. Peers come from `MESH_PEER_DNS` (a Kubernetes **headless
    Service** name, re-resolved every `MESH_DISCOVERY_INTERVAL_SECONDS`, default `10`, so scaling is
    tracked) or a static `MESH_PEERS` (`host:port,host:port`). A replica skips meshing to itself, and
    `originInstanceId` guards against any loopback. The deployment stays just **shim + Couchbase**.
  - **`redis` — opt-in Redis pub/sub** (`RedisEventBackplane`, a tiny hand-rolled RESP client — no Redis
    client library in the build): `REDIS_HOST` (default `127.0.0.1`), `REDIS_PORT` (default `6379`),
    `EVENT_BACKPLANE_CHANNEL` (default `protogemcouch-events`).

  Both keep the shim core dependency-free behind the abstraction. The **mesh** transport is the path to
  zero reliance on any broker product. **Validated end-to-end on a real 2-replica Kubernetes deployment**
  (`EVENT_BACKPLANE=mesh` via the Helm chart + its headless Service): a `CacheListener` on replica A
  fires for a mutation made on replica B (`CROSS_REPLICA_EVENT_CHECK PASS`), and a `backplane=none`
  negative control correctly FAILs — see `tools/CrossReplicaEventCheck` + `scripts/k8s-mesh-e2e.sh`.
- **GII consistency.** The KEYS_VALUES initial image plus the live feed must not drop or duplicate the
  events racing with the GII (Geode solves this with the marker + queue ordering).
- Durable clients, subscription redundancy, MAKE_PRIMARY/secondary feeds, and conflation are **out of
  scope** for a first cut.

## Recommended phased plan

- **P1 (bounded first cut, single instance):** mode-byte dispatch + `101` feed handshake + interest
  registry for **ALL_KEYS**; push `LOCAL_CREATE`/`LOCAL_UPDATE`/`LOCAL_DESTROY` for PUT/REMOVE done on
  the same shim instance; CLIENT_MARKER + EventID framing byte-matched to capture; register-interest
  ack (InterestResultPolicy.NONE/KEYS). Validate with a real client: register interest, mutate from a
  second client, assert a `CacheListener` fires. Document the single-instance limitation.
  - **P1a — DONE.** Connection-mode dispatch in `RawShimServer` (100 op / 107 control / 101 feed);
    `FEED_HANDSHAKE_REPLY` (105 + body) retains the feed channel in `SubscriptionRegistry`;
    `RegisterInterestHandler` records region interest and replies with the captured NONE ack. Gate
    `ProtoGemCouchSubscriptionIntegrationTest.subscriptionClientConnectsAndRegistersInterest` (real
    subscription client connects + registers interest, no error).
  - **P1b — DONE.** PUT/REMOVE push CLIENT_MARKER + LOCAL_CREATE / LOCAL_DESTROY down interested
    feeds. EventID and VMVersionTag are constructed via Geode's own classes and serialized
    (`SubscriptionRegistry`); the message part layouts are byte-matched to the captured server
    (LOCAL_CREATE = 9 parts, LOCAL_DESTROY = 7 parts). The CLIENT_MARKER uses a separate EventID
    thread id so it cannot shadow data events in the client's `(membershipId, threadId, seq)` dedup
    (the initial bug that silently dropped the first event). Gates `cacheListenerFiresOnRemoteCreate`
    and `cacheListenerFiresOnRemoteDestroy` (a real Geode 1.15 client's `CacheListener` fires for a
    create and a destroy made by a *separate* client).
  - **P1 limitations (documented):** single shim instance only; interest is per-region (ALL_KEYS),
    not per-client; no self-event suppression (the originating client also receives its event); the
    EventID membership id and versionTag member are fabricated/null (accepted by the client as opaque
    dedup/version keys). These are P2/P3 items.
  - **P2 (in progress):** create/update distinction **DONE** — a PUT to an existing key (checked when
    a feed is interested) notifies as `LOCAL_UPDATE` so the client fires `afterUpdate`, not
    `afterCreate` (gate `cacheListenerDistinguishesCreateFromUpdate`). **LOCAL_INVALIDATE DONE** —
    `Region.invalidate` pushes a LOCAL_INVALIDATE (same 7-part layout as LOCAL_DESTROY, byte-matched)
    so the client fires `afterInvalidate` (gate `cacheListenerFiresOnRemoteInvalidate`). The
    create/update/destroy/invalidate event types are now complete. **Self-event suppression DONE** —
    every connection's `ClientProxyMembershipID` bytes are captured at handshake as a stable client
    id (identical across a client's op/control/feed connections), and a mutation is never echoed to
    the originating client's own feed (gate `clientDoesNotReceiveItsOwnEventsEchoedBack`); this client
    identity is also the foundation for per-client interest + UNREGISTER. **KEYS_VALUES GII DONE** —
    `registerInterest(KEYS_VALUES)` returns the region's snapshot as a chunked `RESPONSE_FROM_PRIMARY`
    whose single object part is a `VersionedObjectList` (keys + values, reusing the getAll VOL builder;
    no version tags needed — the part is the VOL directly, read via `Part.getObject()`), so the client
    loads the initial image into its local cache (gate `registerInterestKeysValuesLoadsInitialImage`).
    The policy is read from the request's `01 25 <ordinal>` part (NONE=0/KEYS=1/KEYS_VALUES=2); NONE
    still gets the empty ack. **Per-client interest + UNREGISTER DONE** — interest is tracked per
    client id (not globally): a feed only receives events for regions its client registered, and
    `UNREGISTER_INTEREST` (22/25) removes it (a feed close also drops its client's interest). Gates
    `unregisterInterestStopsEvents` + `SubscriptionRegistryTest`. **P2 complete.**
  - **P3 — per-key interest filtering DONE.** REGISTER_INTEREST (20) / REGISTER_INTEREST_LIST (24) are
    parsed into an `Interest` matcher (all-keys / specific key / key-list / regex; the captured layouts
    are documented on `RegisterInterestHandler`), and the push path filters per feed by the mutated key
    (`SubscriptionRegistry.isInterestedInKey`). A client that registers a specific key, a key list, or a
    regex receives events only for matching keys (the `"ALL_KEYS"` sentinel / `.*` still means the whole
    region). Validated against a real Geode 1.15 client by `ProtoGemCouchInterestFilteringIntegrationTest`
    + `SubscriptionRegistryTest`. Per-key UNREGISTER granularity stays region-level (a documented tail).
  - **P3 — durable clients DONE (single-instance).** A durable client presents a stable durable id (the
    last Geode string in its handshake membership, parsed by `DurableHandshakeParser`) plus a timeout.
    When its feed disconnects, the shim retains its interest and queues matching events in memory
    (`DurableState`), replaying them in order on reconnect + `readyForEvents()` (CLIENT_READY = opcode
    53, `ClientReadyHandler`), and dropping the queue if it doesn't return within the timeout. A durable
    client's UNREGISTER-on-close is ignored so its interest survives the disconnect. Validated against a
    real Geode 1.15 client by `ProtoGemCouchDurableClientIntegrationTest` + `DurableSubscriptionTest`.
    **Deferred (documented):** a durable client can't unregister interest mid-session; and the
    per-client wire timeout is honored but capped by `DURABLE_MAX_QUEUE`.
  - **Multi-replica durable persistence — IN PROGRESS (1.2.0-M1).** Lifting the single-instance limit
    so a durable client's retained interest + event queue survive a replica failing and replay on a
    reconnect to *any* replica (Couchbase-backed durable registry + single-writer origin enqueue; see
    `docs/ROADMAP.md` § 1.2.0-M1). **Slice 1 DONE — the persistence primitive:** a per-durable-client
    Couchbase doc `__protogemcouch::durable::<id>` holds the registry record (`type=durableRegistry`,
    `durableId`, `timeoutSeconds`, `away`, `interests[]`, `cqs[]`) plus its event `queue[]` (base64
    frames). The `Repository` exposes `saveDurable` / `loadDurable` (metadata via sub-document upserts
    that leave the queue intact), `enqueueDurableEvent` (atomic subdoc `arrayAppend`, bounded by
    `DURABLE_MAX_QUEUE`), `drainDurableQueue` (CAS-guarded clear), and `dropDurable` — all behind the
    **`DURABLE_PERSISTENCE`** flag (default off → no-ops, single-instance behavior unchanged), plus
    `markDurableAway` (flip away/awaySince without rewriting interests) and `sweepExpiredDurable`
    (cross-replica timeout reclaim). **Slice 2 DONE — `SubscriptionRegistry` wired** (behind the same
    flag): on a durable feed's disconnect it persists the record (away) + flushes the queue to
    Couchbase; while away, matching events enqueue to Couchbase; on reconnect to **any** replica +
    `readyForEvents()` it drains the Couchbase queue and replays, marking the doc not-away; a periodic
    `sweepExpiredDurable` (`DURABLE_SWEEP_SECONDS`, default 60) reclaims expired docs, so the per-client
    local timer only frees memory (never drops a doc the client may have reconnected to elsewhere).
    Validated real-client by `ProtoGemCouchDurablePersistenceMultiReplicaIntegrationTest` (reconnect to a
    *different* replica replays from Couchbase). **Slice 3a DONE — cross-replica origin enqueue (interest
    events):** the replica that processes a mutation (its *origin*) enqueues matching register-interest
    events for *all* away durable clients read from the persisted registry (`listAwayDurable`, cached and
    refreshed every `DURABLE_AWAY_REFRESH_MS`, default 1000ms) — not just the ones it owned in memory — so
    a mutation on a replica that never saw the client (its former owner may be dead) is still delivered.
    Single-writer: only the local-origin publish path enqueues (backplane echoes don't), so events are
    enqueued exactly once; a reconnecting client stays "away" until `CLIENT_READY` so reconnect-window
    events are captured. Validated by the same test's `nonOwnerReplicaEnqueuesForAwayClientFromTheRegistry`.
    Remaining: **Slice 3b** — make *CQ* events owner-independent too (capture CQ OQL text, persist CQ
    definitions, origin recompiles + evaluates each away client's CQ); and **Slice 4** — k8s failover
    validation. Known bound: away-registry cache freshness ≈ the refresh interval.
  - **P3 — redundancy / keepalive DONE.** A real redundancy-enabled, keepalive-pinging client
    (`tools/RedundancyKeepaliveProbe`) produces no unhandled opcodes: client PINGs (5) are acked,
    MAKE_PRIMARY is drained on the feed connection, and PERIODIC_ACK (52) is drained. Subscription
    redundancy is a graceful no-op for the single-logical-backend shim (like single-hop). The keepalive
    fix that matters: **feeds are exempt from the idle-connection reaper** (`IS_FEED` marker checked by
    `IdleConnectionHandler`), so a long-idle feed waiting for events is not silently reaped; dead feeds
    are detected at the TCP layer.
- **P2:** regex + key-list interest, LOCAL_INVALIDATE, the KEYS_VALUES GII response, UNREGISTER,
  PERIODIC_ACK draining, SERVER_TO_CLIENT_PING.
- **P3 (only if needed):** durable clients, redundancy/MAKE_PRIMARY, conflation, and a cross-replica
  eventing backplane.

`tools/SubscriptionCapture` reproduces the capture for byte-level work in the implementation phases.
