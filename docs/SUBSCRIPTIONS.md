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
- **Cross-replica eventing is not possible without a backplane.** Events can propagate in-memory only
  among clients connected to the *same shim instance*. With multiple shim replicas sharing one
  Couchbase, a mutation on replica A is invisible to interested clients on replica B — Couchbase KV
  is not a message bus. True multi-replica eventing would need a pub/sub backplane (Couchbase DCP/
  Eventing, or an external broker), which is a separate, heavy initiative. **A first cut is
  single-instance-scoped and must document this.**
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
  - **P1b — next.** Hook the mutation path (PUT/REMOVE) to push CLIENT_MARKER + LOCAL_CREATE/UPDATE/
    DESTROY (with EventID) down interested feeds; gate = a real client's `CacheListener` fires.
- **P2:** regex + key-list interest, LOCAL_INVALIDATE, the KEYS_VALUES GII response, UNREGISTER,
  PERIODIC_ACK draining, SERVER_TO_CLIENT_PING.
- **P3 (only if needed):** durable clients, redundancy/MAKE_PRIMARY, conflation, and a cross-replica
  eventing backplane.

`tools/SubscriptionCapture` reproduces the capture for byte-level work in the implementation phases.
