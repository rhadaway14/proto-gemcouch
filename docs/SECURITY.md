# ProtoGemCouch Security

## Purpose

This document records the current security posture and minimum deployment guidance for ProtoGemCouch.

---

## Current security model

ProtoGemCouch currently relies on:

- externalized runtime secrets via environment variables
- startup config validation
- redacted safe config logging
- Couchbase authentication using provided credentials
- separate health port for operational health checks
- GitHub Actions for automated build and security scanning

---

## Secrets handling

Sensitive values must not be hardcoded:
- `CB_PASSWORD`
- potentially `CB_USERNAME`

Rules:
- do not commit secrets to source control
- do not put real credentials in docs
- do not hardcode credentials in Java source
- prefer runtime secret injection

Recommended secret sources:
- Docker runtime environment injection
- Kubernetes Secrets
- CI/CD secret stores
- cloud secret managers

### File-mounted secrets (preferred)

Any config value can be supplied from a file instead of an environment variable by setting
`<NAME>_FILE` to a path; the value is read (trimmed) from that file. This is the preferred way to
provide credentials, since env vars can leak via `/proc`, crash dumps, and `docker inspect`:

```text
CB_USERNAME_FILE=/etc/protogemcouch/secrets/cb-username
CB_PASSWORD_FILE=/etc/protogemcouch/secrets/cb-password
```

The Helm chart (`charts/protogemcouch`) uses this: it mounts a Kubernetes Secret (chart-managed, or
an external one via `couchbase.existingSecret` for Vault / external-secrets / sealed-secrets) as
files and points `CB_USERNAME_FILE` / `CB_PASSWORD_FILE` at them, so credentials never appear in the
container's environment.

---

## Logging safety

Current implementation:
- redacts password values in startup logs
- avoids printing raw secrets directly

Operational rule:
- never log secrets
- never paste real credentials into tickets or shared docs

### Audit logging

Security-relevant events are emitted on a **dedicated audit stream** — the SLF4J logger
`protogemcouch.audit` — at WARN, separate from operational logs, so they can be routed to their own
sink (file / SIEM) by logger name. Every audit line carries an `audit=true` marker (for filtering even
when streams are merged), the remote peer, and a reason, in the standard `key=value` format. Audited
events:

| Event | When |
|---|---|
| `connection_rejected` | a connection is refused because the `MAX_CONNECTIONS` cap is reached |
| `connection_first_request_timeout` | a connection is closed for not completing its first request in time (slowloris guard) |
| `malformed_frame` | an inbound frame is rejected as malformed/oversized and the connection closed |
| `tls_handshake_rejected` | a TLS/mTLS handshake fails (e.g. an untrusted or missing client cert under `TLS_CLIENT_AUTH=require`) |

Validated by `AuditLogTest` (format/marker) and `ProtoGemCouchAuditLogIntegrationTest` (a malformed
frame emits the audit event in the container log). To ship the audit trail separately in production,
route the `protogemcouch.audit` logger to its own appender.

---

## Health endpoint security

ProtoGemCouch exposes:
- `/live`
- `/ready`

These endpoints are intentionally simple and do not expose credentials or detailed system internals.

Recommendations:
- do not expose `HEALTH_PORT` broadly on untrusted networks
- restrict access to operators, orchestrators, or trusted monitoring systems
- treat health endpoints as operational surfaces, not public APIs

---

## Couchbase credential guidance

Use least-privilege credentials where practical.

The runtime account should have only the permissions needed for:
- KV reads/writes
- query access only if required for `SIZE` and `KEY_SET`

Do not use a broad admin account in production unless strictly necessary.

---

## Transport security

### Couchbase (backend) transport

The shim connects to Couchbase over TLS when the connection string uses `couchbases://` or
`CB_TLS_ENABLED=true`. It trusts the cluster certificate supplied via `CB_TLS_CERT_PATH` (a PEM
file). Validated end-to-end by `ProtoGemCouchBackendTlsIntegrationTest` against a TLS-served
Couchbase.

| Env var | Default | Meaning |
|---|---|---|
| `CB_TLS_ENABLED` | `false` | Enable TLS to Couchbase (also implied by a `couchbases://` connection string). |
| `CB_TLS_CERT_PATH` | — | PEM certificate to trust (the Couchbase cluster cert / CA). |
| `CB_TLS_VERIFY_HOSTNAME` | `true` | Verify the Couchbase hostname against the certificate. Only disable for self-signed certs whose SAN does not match the host (e.g. local test setups). |

For production, use a Couchbase certificate whose SAN matches the connection hostname and leave
hostname verification enabled.

### Shim-side transport (inbound TLS)

The Geode protocol listener supports native TLS. When enabled, the shim terminates TLS on the
Geode port using a server keystore, validated against a real Geode SSL client
(`ProtoGemCouchTlsIntegrationTest`). It is **off by default**; enable it for any deployment that
crosses an untrusted network.

| Env var | Default | Meaning |
|---|---|---|
| `TLS_ENABLED` | `false` | Enable TLS on the Geode listener. |
| `TLS_KEYSTORE_PATH` | — | Path to the server keystore (required when enabled). |
| `TLS_KEYSTORE_PASSWORD` | — | Keystore password. |
| `TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type. |
| `TLS_CLIENT_AUTH` | `none` | `require` enables mutual TLS (client-certificate authentication). |
| `TLS_TRUSTSTORE_PATH` / `_PASSWORD` / `_TYPE` | — | Truststore for verifying client certs (required when client auth is `require`). |
| `TLS_PROTOCOLS` | `TLSv1.3,TLSv1.2` | Comma-separated enabled-protocol allowlist; legacy SSLv3 / TLS 1.0 / 1.1 are excluded. Narrow to `TLSv1.3` to require TLS 1.3. |
| `TLS_CIPHERS` | — | Optional comma-separated cipher-suite allowlist; unset uses the provider's strong defaults. |

The shim pins this protocol/cipher policy explicitly (rather than relying on JVM defaults) on both the
Geode listener and — when `HEALTH_TLS_ENABLED` — the health HTTPS endpoint, so the accepted TLS
versions are auditable and operator-controllable. `ProtoGemCouchTlsPolicyIntegrationTest` proves the
policy is enforced server-side: a TLS-1.3-pinned instance rejects a TLS 1.2 client at the handshake.

Geode clients connect with `ssl-enabled-components=server` and a truststore trusting the shim's
certificate. See `docs/RUNBOOK.md` for the full variable list and `docker-compose.yml`
(`protogemcouch-tls`) for a working example.

Mutual TLS (`TLS_CLIENT_AUTH=require`) provides transport-level client authentication: only clients
presenting a certificate trusted by the shim's truststore can connect. This is the recommended
client-auth model for the shim (it does not implement the Geode application-level security
handshake).

### Certificate rotation

**Hot reload (1.2.0-M3) — no restart.** Set `TLS_RELOAD_SECONDS=<n>` (default `0` = off) and the shim
polls the keystore (and truststore, for mutual TLS) every `n` seconds; when the material changes it
rebuilds the Geode-listener `SslContext` and swaps it for **new** connections — established TLS sessions
are unaffected. A mid-rotation partial read or a bad keystore is ignored (the current context is kept,
retried next poll), so TLS never breaks. The swap is logged + audited (`tls_cert_reloaded`). Validated by
`TlsCertReloaderTest` (real keytool keystores: rotation rebuilds + swaps; unchanged keeps; corrupt keeps).
Polling a content hash (not a file-watch) is deliberate — it reliably catches a Kubernetes Secret
rotation, which swaps the mount's `..data` symlink. *Caveat: hot reload covers the Geode listener; the
`HEALTH_TLS_ENABLED` admin HTTPS endpoint still rotates via restart.*

**Without hot reload** (`TLS_RELOAD_SECONDS=0`), the shim reads its keystore/truststore once at startup,
so rotation is a **rolling restart**. Because the shim is stateless and runs multiple replicas, that
restart is **zero-downtime**: a `RollingUpdate` replaces pods one at a time and the PodDisruptionBudget
keeps `minAvailable` serving throughout (data lives in Couchbase, validated by
`ProtoGemCouchChaosIntegrationTest`).

**Standalone / Docker.** Replace the keystore (and truststore) file the container mounts, then restart
the container. Run more than one instance behind a load balancer to avoid a connectivity gap.

**Kubernetes (Helm).** The chart mounts the keystore/truststore from a Secret you manage
(`tls.enabled=true`, `tls.existingSecret`); see `charts/protogemcouch/values.yaml`. To rotate:
1. Update the Secret (e.g. via cert-manager, Vault, or `kubectl create secret ... --dry-run | kubectl apply`).
2. **With hot reload** (`TLS_RELOAD_SECONDS` set, e.g. via `extraEnv`): nothing else to do — Kubernetes
   propagates the updated Secret into the mounted files within ~a minute and the shim picks it up on its
   next poll, **no pod roll**. **Without it**: roll the Deployment — on the next `helm upgrade` the
   `checksum/tls-secret` pod annotation (the Secret's `resourceVersion`) changes and triggers the rollout
   automatically; for an out-of-band Secret update, run `kubectl rollout restart
   deploy/<release>-protogemcouch` or use a Secret-watching controller (e.g. Stakater Reloader).
   RollingUpdate + PDB make this seamless.

**Server-certificate rotation** (same CA): issue the new cert from a CA the clients already trust, then
roll the shim — clients keep validating against the unchanged CA, so there is no client-side change.

**CA rotation** (for mTLS, ordered to avoid an outage):
1. Add the **new CA** to client truststores *before* switching the server cert (so clients will trust
   the new server cert), and add the new CA to the shim truststore *before* clients present new client
   certs (so the shim accepts either CA during migration).
2. Roll out the new server cert (shim) and new client certs (clients) in any order — both CAs are
   trusted on both sides during the overlap.
3. Once every party is on the new CA, remove the old CA from the shim truststore and client truststores
   and roll once more.

### Health/admin endpoint

The health/admin server (`/live`, `/ready`, `/metrics`, `/metrics/json`) defaults to plain HTTP on
all interfaces, which is appropriate for a trusted operator/monitoring network. It can be hardened:

| Env var | Default | Meaning |
|---|---|---|
| `HEALTH_TLS_ENABLED` | `false` | Serve the admin endpoints over HTTPS, using the same keystore as `TLS_KEYSTORE_*`. |
| `HEALTH_BIND_ADDRESS` | all interfaces | Bind to a specific interface (e.g. `127.0.0.1` or an internal address) to restrict exposure. |

Enable HTTPS for metrics scraping over untrusted networks, and/or restrict the bind address. Keep
`HEALTH_PORT` access limited to operators and monitoring systems regardless (see network exposure
guidance below). The endpoints expose no credentials or sensitive payloads.

---

## Connection resource guards

To limit resource exhaustion from dead, slow, or excessive client connections:

| Env var | Default | Effect |
|---|---|---|
| `CONNECTION_IDLE_TIMEOUT_SECONDS` | 300 | Connections idle (no read/write) this long are closed and reaped. `0` disables. |
| `MAX_CONNECTIONS` | 0 (unlimited) | New connections beyond this concurrent count are rejected and closed. |
| `FIRST_REQUEST_TIMEOUT_SECONDS` | 10 | A connection must complete its handshake and first request within this long or it is closed. Not reset by trickled bytes, so it bounds slowloris-style connections. `0` disables. |
| `HANDLER_MAX_PENDING_TASKS` | 256 | Per-handler-thread queue bound; once full, requests are shed (connection closed) instead of growing the backlog unbounded. `0` = unbounded. |

These guards are observable via `protogemcouch_connections_rejected_total`,
`protogemcouch_idle_connections_closed_total`, `protogemcouch_connections_first_request_timeout_total`,
and `protogemcouch_requests_shed_total`. Set `MAX_CONNECTIONS` to a value matched to the shim's
resources, and keep the idle and first-request timeouts enabled on untrusted networks.

**Memory-exhaustion resilience (1.2.0-M4).** A backend stall or outage under load is a memory-exhaustion
risk: requests block on slow backend calls and the handler backlog grows. Two guards bound it. The
**handler queue is kept deliberately small** (`HANDLER_MAX_PENDING_TASKS` default `256`) so the shim
**sheds** (fails fast, closes the connection) long before the heap is exhausted — a prior large default
let the backlog OOM the process during a backend hard-outage in the capacity soak. As a last resort, the
image runs with **`-XX:+ExitOnOutOfMemoryError`**, so any `OutOfMemoryError` halts the JVM with a
non-zero exit for a clean orchestrator restart rather than leaving a wedged, never-restarted process.
Raise `HANDLER_MAX_PENDING_TASKS` only with heap headroom for the larger backlog.

**PDX type/enum registry growth.** To decode PDX values the shim remembers every PDX type and enum a
client registers (as Geode itself does); the registry is in-memory and not evicted, so a client that
registers a very large number of *distinct* PDX types would grow it over time. This is now **observable
and boundable**: the `protogemcouch_pdx_types` / `protogemcouch_pdx_enums` gauges expose the live sizes
(with an alert and dashboard panel), and the optional `MAX_PDX_TYPES` / `MAX_PDX_ENUMS` caps (0 =
unlimited, the default) reject new registrations past the cap — incrementing
`protogemcouch_pdx_registry_rejected_total` and emitting a `pdx_registry_cap_exceeded` audit event —
while still serving every already-registered type. Set the caps to a value comfortably above your
application's legitimate distinct-type count on untrusted networks; the bounded container heap caps the
blast radius regardless, and `MAX_CONNECTIONS` / network exposure should stay restricted.

The idle timeout reaps inactive connections; the first-request deadline additionally closes
slowloris-style connections that stay technically active by trickling bytes without ever completing
a request (which would otherwise keep resetting the idle timer). Together they bound both idle and
slow-but-never-complete connections.

---

## Network exposure guidance

Recommended:
- expose the shim only to the clients that need it
- restrict inbound access to `SHIM_PORT`
- restrict inbound access to `HEALTH_PORT`
- isolate the shim and Couchbase on private networks whenever possible

---

## Inbound frame validation

The Geode protocol encodes payload length, part count, and per-part length as raw 32-bit
integers supplied by the client. The frame decoder validates every one of these against
configurable limits before allocating memory or reading bytes, so a corrupt or hostile frame
cannot drive the shim into an oversized allocation and crash it with an `OutOfMemoryError`.

Limits (with defaults):

| Env var | Default | Meaning |
|---|---|---|
| `MAX_FRAME_BYTES` | 52428800 (50 MiB) | Maximum declared payload size, and per-part length cap. |
| `MAX_FRAME_PARTS` | 100000 | Maximum number of parts a single frame may declare. |

Parts are parsed from a slice bounded by the declared payload length, so a hostile per-part length can
never read past its own frame into the bytes of a following (pipelined) frame — the decoder consumes
exactly the header plus the declared payload, keeping the stream frame-aligned. When a frame violates
a limit, the shim:

- increments `protogemcouch_malformed_frames_total`
- emits an `audit=true malformed_frame` event (audit stream) with a reason code and the offending value
- closes the offending connection (the byte stream can no longer be trusted to be frame-aligned)

This is fuzz-validated: `GemFrameDecoderFuzzTest` feeds tens of thousands of random and hostile-header
inputs, every truncation prefix, fragmented and pipelined delivery, and boundary cases, asserting the
decoder never throws, hangs, or over-allocates and emits only self-consistent frames.

Tune the limits down to the smallest values that comfortably fit legitimate traffic for your
deployment to reduce the per-connection memory exposure further.

---

## Deserialization safety (untrusted client values)

To decide whether a client value is queryable, the shim sometimes **deserializes** it — a
Java-serialized value (Geode `DataSerializer` `SERIALIZABLE` code `0x2c`) is read back to check whether
it is a `Map`/`Collection`. Deserializing untrusted bytes is a classic gadget-chain risk (CWE-502): a
malicious payload can execute a chain during `readObject` regardless of how the result is used. Two
layers constrain it (`com.protogemcouch.serialization.SafeDeserialization`):

- **Strict allowlist on the shim's own deserialization.** Every `ObjectInputStream` the shim opens runs
  under an `ObjectInputFilter` that permits **only** JDK container/scalar types
  (`java.lang` / `java.util` / `java.math` / `java.time`, plus primitives and arrays of them) and
  **rejects every application class** — where gadget chains live. A rejected value simply isn't treated
  as a queryable map; it is preserved opaquely and round-trips unchanged. Depth/reference/array bounds
  also cap resource use. Validated by `SafeDeserializationTest` (a JDK map deserializes; a non-JDK class,
  and a JDK map carrying a non-JDK element, are rejected).
- **Process-wide gadget blocklist (defense in depth).** At startup the shim installs a JVM-wide
  serialization filter (`ObjectInputFilter.Config.setSerialFilter`) that blocks the well-known gadget
  packages (commons-collections functors, BeanUtils, Groovy/Spring/Xalan/JNDI/RMI/scripting, …) for
  **any** `ObjectInputStream` in the process — including the one Geode's `DataSerializer` uses
  internally for a `SERIALIZABLE` element — while leaving all other classes allowed so Geode's own
  serialization keeps working. It **defers to an operator-provided `jdk.serialFilter`**, so you can
  tighten or replace the policy without code changes.

Operators on untrusted networks who do not need Java-serialized client values queryable can tighten
further by setting `-Djdk.serialFilter=!*` (reject all Java deserialization) — values still round-trip
opaquely. PDX and the shim's native value encodings are unaffected (they do not use Java serialization).

### 1.3.0 re-review — broader nested value-type decode

1.3.0 widened the *structured/queryable* nested-value set (typed object arrays, JDK `Set`s, non-`ArrayList`
`List`s, alongside the existing `java.time` / wrappers / `UUID` / `BigInteger`). The deserialization
surface was re-reviewed:

- **No new inbound deserialization surface.** The newly-structured types are **already inside the existing
  allowlist** — `Integer[]` / `UUID[]` / `Instant[]` are arrays of allowed `java.lang` / `java.util` /
  `java.time` types, and `HashSet` / `LinkedList` are `java.util`. A value carrying a customer POJO or a
  non-JDK enum is still rejected by the `ObjectInputFilter` and preserved opaquely. The allowlist + the
  process-wide gadget filter are unchanged.
- **New read-path is a class *load*, not a deserialize.** A stored typed-object-array document records its
  component class name so it reconstructs type-exact (`Integer[]` stays `Integer[]`). On read,
  `resolveArrayComponent` resolves it with `Class.forName`, restricted to a hardcoded JDK
  scalar/utility allowlist (`SUPPORTED_ARRAY_COMPONENT_NAMES`) **or** an enum — the same dynamic
  enum-class-load the nested-enum decode already performs (reviewed in 1.0/M6). This loads a class **by
  name from a shim-written document**; it does **not** feed attacker bytes to `readObject`, so it is not a
  CWE-502 gadget vector. The bounded residual — an attacker with **Couchbase write access** (already a
  deeper compromise) could name an on-classpath class to trigger its static initializer — is mitigated by
  Couchbase access control + backend TLS + mTLS, and an unresolvable/disallowed component simply degrades
  to a generic `Object[]`. Legitimate component classes are always JDK types, since the inbound allowlist
  blocks everything else from reaching the structured path in the first place.
- **PDX paths unchanged.** PDX object-array field querying (M1) reads raw bytes via `PdxReaderImpl` + the
  shim's own `PdxTypeRegistry` (no class load); PDX registry persistence (M2) stores/loads the same
  `DataSerializer`-encoded `PdxType` blobs through the same guarded reader (see below). No new eval/inject
  surface.

The full-surface soak of these decode paths (`PDX_PERSISTENCE` on, rich nested-type values) ran with **0
errors / no leak** (`docs/SOAK_RESULTS.md`, 1.3.0-M4).

---

## OQL query / pushdown / index path (1.1.0)

The optional N1QL **pushdown** (`OQL_PUSHDOWN`, default off) and the deeper **field querying** added in
1.1.0 (nested-object paths, scalar arrays) introduce a query/index code path. It was security-reviewed:

- **No N1QL injection.** The generated N1QL binds all client **literals** as query parameters
  (`$prefix`, `$v…`, `$n…` — never string-concatenated). The only client-derived tokens interpolated into
  the statement are **field names**, and those are rejected unless they match a strict identifier pattern
  (`[A-Za-z_][A-Za-z0-9_]*`) — validated both when the OQL is parsed (`OqlQuery` path segments) and again
  at the backend before interpolation (`CouchbaseRepository`, defense-in-depth). `LIMIT` is a parsed
  integer. The query runs **read-only** (`QueryOptions.readonly(true)`) with `REQUEST_PLUS`, so it can
  neither mutate data nor inject a side effect.
- **PDX `pdxFields` sidecar is size-bounded.** When pushdown is on, write paths extract a PDX value's
  scalar fields into a queryable `pdxFields` sidecar. It is added to the document **before** the
  per-value size check (`CB_MAX_VALUE_BYTES`), so it cannot be used to bypass that limit or grow a
  document unboundedly. The sidecar's keys are PDX field names (client data) stored as JSON keys — they
  are never interpolated into N1QL, and a non-identifier field name simply isn't queryable. Extraction is
  best-effort and exception-guarded (a malformed PDX leaves the document without a sidecar; it is still
  filtered correctly via `pdxFields IS MISSING`).
- **Nested-PDX navigation via reflection is contained.** Reading a nested PDX `OBJECT` field uses the
  protected `PdxReaderImpl.getRaw` via reflection with a **hardcoded** method/class target (no
  client-controlled reflection), fully wrapped in `try/catch(Throwable)` so any failure degrades to "field
  absent" rather than an error. The shim installs no `SecurityManager`, and Geode is on the unnamed
  module, so no `--add-opens` is required.
- **Bounded recursion / iteration.** Nested-path resolution recurses **once per path segment**, and the
  path length is bounded by the (frame-size-limited) query string — not by the data — so a cyclic or
  deeply-nested PDX value cannot cause unbounded recursion. Array index access bounds-checks the list/array
  (out-of-range → absent), and `IN` iterates a collection bounded by the (size-limited) stored value.

Operators creating the secondary indexes pushdown uses should scope them per region
(`WHERE META().id LIKE "region::%"`) and grant the shim's Couchbase user only the privileges it needs
(KV + read-only Query); the shim never issues DDL itself.

### Re-review — 1.4.0 (OR pushdown, aggregates, GROUP BY, DISTINCT)

1.4.0 added four new code paths; all were reviewed against the injection invariant:

- **OR pushdown** (`queryPushdownByOrGroups`): each AND-group in the OR is processed by the same
  `buildGroupAndClause` as the single-group path. No new field interpolation; per-group param names
  are disambiguated with a `g<group>_<i>` prefix (still generated from integer indices, not user data).
- **Aggregate pushdown** (added in 1.5.0-M1 but listed here for completeness of the 1.4.0 aggregate
  work): `SELECT <aggExpr> AS r` — the aggregate field name passes `SAFE_FIELD` before interpolation;
  all predicate values go through bind params; the result column alias (`r`) is a hardcoded literal.
- **GROUP BY pushdown** (1.5.0-M2): group-key field, aggregate field, both checked with `SAFE_FIELD`
  before interpolation; the `GROUP BY` expression reuses the same `COALESCE(envPath, barePath)` string
  (already interpolated with validated identifiers); no additional injection surface.
- **DISTINCT / paren WHERE / DNF expansion**: purely in-memory in `OqlQuery` — no N1QL surface.
  `parseDnf`/`splitTopLevel` are pure OQL parsing; field names reaching `pushdownOrGroups` are
  structurally constrained by the `CONDITION` regex.

### Re-review — 1.5.0-M3 (IN list, LIKE, IS NULL / IS NOT NULL)

Three new predicate types reach the N1QL layer; reviewed:

- **IN_LIST**: items are placed into a `JsonArray` and bound as a single parameter (`$v`). N1QL
  `TO_STRING(field) IN $v` — the array contents are never string-interpolated, only bound.
- **LIKE**: the pattern is bound as a string parameter (`$v`). N1QL `TO_STRING(field) LIKE $v` — the
  `%` and `_` wildcards in the pattern are interpreted by Couchbase's query engine, not by the shim,
  and they arrive as a bind value, not as part of the query string. No injection surface.
- **IS NULL / IS NOT NULL**: no value is needed; the N1QL fragment is a hardcoded `IS NOT VALUED` /
  `IS VALUED` predicate on the already-validated field path. Nothing from user input is interpolated.
- **`appendMapOnlyPredFrag` helper**: shared by `aggregatePushdown` and `groupByPushdown`; receives
  field paths already validated by the callers' `SAFE_FIELD` guards. The `paramPrefix` argument is
  always a caller-generated `"av_" + <int>` string — never user input.

**Summary through 1.5.0-M3**: The original guarantee holds: the only user-derived token ever
interpolated into a N1QL string is a field name that has passed `SAFE_FIELD`; all literal values
travel as Couchbase bind parameters. `LIMIT` is a Java `int`. Read-only query flag is set on all
N1QL calls.

---

## Durable subscription persistence (1.2.0)

With `DURABLE_PERSISTENCE` on (default off), a durable client's subscription state — its durable-client
id, registered interests, **CQ definitions (the OQL text)**, and **queued (undelivered) event payloads**
— is persisted to Couchbase under internal `__protogemcouch::durable::*` keys so it survives the owning
replica failing and replays on reconnect to any replica. Security review of that surface:

- **Stored where the values already live.** Durable records and queued events are ordinary documents in
  the same bucket as the data, so they inherit the **same protection**: Couchbase backend TLS
  (`docs/SECURITY.md` → backend transport), the shim's least-privilege Couchbase user, and the
  per-value size cap. They are not exposed on any shim endpoint.
- **No new injection/eval surface.** A persisted CQ is recompiled on the origin replica with the **same
  OQL parser** as the live QUERY/CQ path and evaluated **in memory** (`OqlQuery.matches`, not N1QL), so
  the no-injection / bounded-recursion guarantees above apply unchanged — there is no string-built query
  and no code eval. Field names still must match the strict identifier pattern.
- **Durable-client-id is the identity (client-supplied) — treat ids as semi-secret.** As in native
  Geode, a durable client is identified solely by its `durable-client-id`; a client that connects with
  another client's id resumes that subscription and drains its queued events. The shim does not bind a
  durable id to a TLS identity. On untrusted networks, **authenticate connections with mutual TLS**,
  keep durable ids unguessable, and restrict shim-port exposure (below). This is inherent to the Geode
  durable-client model, not specific to the shim.
- **Bounded queue.** Each durable client's persisted queue is capped (`DURABLE_MAX_QUEUE`, oldest
  dropped past the cap), so a permanently-away durable client cannot grow its queue without limit; the
  away-record sweep reclaims expired durable clients. The away-registry size is observable via
  `protogemcouch_durable_away_registered`.

---

## PDX registry persistence (1.3.0)

With `PDX_PERSISTENCE` on (default off), PDX type/enum ids are allocated from a cluster-wide durable
Couchbase registry (under internal `__protogemcouch::pdx::*` keys) instead of an in-memory per-instance
counter, so ids are consistent across replicas and survive a restart. Security review of that surface:

- **Stored where the values already live.** The persisted artifacts are the **serialized `PdxType` /
  `EnumInfo`** the client itself registers (the schema/layout — not instance data) and a small
  fingerprint→id index, kept as ordinary documents in the same bucket, inheriting the same backend TLS,
  least-privilege user, and size cap. They are not exposed on any shim endpoint.
- **No new deserialization/eval surface.** The bytes are the same `DataSerializer`-encoded `PdxType`
  blobs already parsed on the in-memory path; on a load-on-miss they are deserialized with the **same**
  guarded reader and the assigned id stamped — there is no client-controlled class loading or query eval.
  Bulk discovery (`GET_PDX_TYPES`/`GET_PDX_ENUMS`) returns the persisted registry, bounded by the same
  optional `MAX_PDX_TYPES` / `MAX_PDX_ENUMS` registration caps.
- **Id allocation is race-safe.** Ids come from a Couchbase atomic counter with an insert-if-absent
  fingerprint doc, so concurrent registration of the same type across replicas converges on one id (the
  loser adopts the winner's id; a skipped counter value is a harmless gap) — no duplicate ids, no
  cross-replica mis-resolution.

---

## Container hardening

Current hardening includes:
- JRE-only runtime image (no build toolchain or source shipped; the shaded jar is built before the
  image and copied in)
- base image **pinned by digest** (`eclipse-temurin:17-jre@sha256:…` in the `Dockerfile`) for
  reproducible, tamper-evident builds
- runs as a **fixed non-root UID/GID** (`USER 10001:10001`), compatible with read-only root
  filesystems and arbitrary-UID admission policies
- graceful shutdown on `SIGTERM` (the signal Kubernetes/`docker stop` send): the listener stops
  accepting, in-flight requests drain within the termination grace period, then the Couchbase
  connection and health server close cleanly
- **SBOM + build provenance** generated and attached to the published image as OCI attestations by
  CI (`sbom: true`, `provenance: mode=max` in the Docker publish workflow). Inspect with
  `docker buildx imagetools inspect docker.io/rhadaway14/protogemcouch:latest --format '{{ json .SBOM }}'`.
- **Image vulnerability scanning** (Trivy) runs in CI on every build, including pull requests, before
  the image is ever published. HIGH+CRITICAL findings (OS packages and bundled jar libraries) are
  uploaded to the GitHub **code-scanning / Security** tab for triage. The build **hard-fails on a
  fixable CRITICAL in an OS package** — the part remediable by bumping the pinned base-image digest.
  Jar/library CVEs are deliberately scoped out of the hard gate because they originate in Geode's
  transitive dependencies (e.g. Shiro `CVE-2022-40664`), which cannot be upgraded without breaking
  Geode; gating on them would block every release. Operators must triage those via the Security tab.
- **Keyless image signing** (cosign + GitHub OIDC) signs every published image by digest, with the
  signature recorded in the Rekor transparency log. No long-lived keys are stored. Verify with
  `cosign verify docker.io/rhadaway14/protogemcouch:latest --certificate-identity-regexp '.*' --certificate-oidc-issuer https://token.actions.githubusercontent.com`.

Recommended future improvements:
- bump the pinned base-image digest on a cadence to clear OS-package findings
- pin a stricter cosign certificate-identity in `cosign verify` policies once the repo path is fixed
- adopt a vulnerability-exception file (`.trivyignore`) with documented expiry for accepted jar CVEs

---

## Dependency and code security scanning

GitHub Actions now provides automated scanning coverage through:
- build and test workflow
- Docker image build workflow (Trivy image vulnerability scanning + keyless cosign signing)
- dependency graph submission
- CodeQL analysis

### Current CI security posture
- Maven build runs automatically in CI
- dependency graph submission is automated
- CodeQL static analysis is automated
- Trivy scans the container image on every build (PRs included); HIGH+CRITICAL findings go to the
  Security tab and a fixable CRITICAL OS-package CVE hard-fails the build
- published images are signed with keyless cosign (GitHub OIDC + Rekor)
- scans run on push and pull request for mainline branches
- dependency/code scanning can also run on schedule

### Recommended operator practice
- review GitHub Security / Code Scanning alerts regularly
- do not ignore repeated dependency findings without triage
- update vulnerable dependencies intentionally and retest
- treat CI scan failures or alerts as release blockers when severity justifies it

### Vulnerability triage SLA

Findings are gated and triaged on a fixed timeline rather than left advisory:

- **Enforcement (gating).** Two layers gate releases:
  1. *In-workflow hard gate* — a fixable **CRITICAL OS-package** CVE fails the image build (and thus
     the publish) directly (`docker-image.yml`).
  2. *Branch-protection gate* — the `CodeQL`, `Code scanning results`, and dependency-scan checks are
     required on the protected branches, so an unresolved code-scanning alert or a new Dependabot
     dependency alert at or above the threshold below blocks merge. CodeQL runs the broader
     `security-and-quality` suite.
- **Triage timelines** (from when an alert appears, to a fix or a recorded, time-boxed acceptance):
  | Severity | Triage within | Remediate/accept within |
  |---|---|---|
  | Critical | 2 business days | 7 days |
  | High | 5 business days | 30 days |
  | Medium | 10 business days | 90 days |
  | Low | best effort | next dependency refresh |
- **Accepted exceptions** are recorded in `.trivyignore` (CVE id + component + justification + expiry),
  and re-reviewed on expiry. Only CVEs that are not remediable on our side (e.g. Apache Geode pinned
  transitive dependencies) or not reachable in the shim's usage are accepted; fixable CVEs in our own
  direct dependencies are fixed, not ignored. Library/jar CVEs from Geode's transitive tree remain
  outside the in-workflow hard gate (gating on them would block every release on un-upgradeable Geode
  dependencies) — they are surfaced to the Security tab and handled via this SLA + the exception file.

---

## Operational security checklist

Before non-lab deployment:

- [ ] secrets are externalized
- [ ] no real secrets are committed
- [ ] logs reviewed for secret leakage
- [ ] health port exposure restricted
- [ ] Couchbase credentials are least-privilege
- [ ] shim port exposure restricted
- [x] deployment image built from known source state (base pinned by digest; SBOM + provenance attested)
- [x] container runs as non-root
- [x] dependency/code scanning configured in CI
- [ ] CI security findings reviewed before release

---

## Current limitations

The security posture has been reviewed for the 1.0.0 GA and **re-reviewed through 1.3.0** (this
document, plus the deserialization / resource-guard / TLS hardening passes; for 1.2.0 — hot TLS reload,
the durable-subscription persistence surface, and the memory-exhaustion resilience guards; and for 1.3.0 —
the **broader nested value-type decode** surface and the **PDX registry persistence** surface, both of
which add no new untrusted-deserialization vector, see the sections above). That internal review is **not
a substitute for an independent third-party audit**, and additional, environment-specific hardening may
be warranted.

Future security work:
- stronger TLS story for all traffic
- secret-manager integration
- image vulnerability scanning/policy enforcement
- shim-side auth model if needed
- more restrictive health endpoint exposure guidance per environment

---

## Current conclusion

ProtoGemCouch supports basic secure operational hygiene:
- no secrets in source
- no hardcoded passwords
- redacted startup logging
- non-root container runtime, container-aware bounded heap
- simple health endpoints without sensitive payloads
- inbound frame validation + connection resource guards
- gadget-safe deserialization of untrusted client values (allowlist + process-wide gadget filter)
- automated CI-based dependency and static code scanning

Additional hardening is still recommended before broader production deployment.