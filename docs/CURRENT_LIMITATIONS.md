# Current limitations

> **Sources of truth.** The exact supported surface is the **compatibility contract** in
> `docs/COMPATABILITY_MATRIX.md`, and the prioritized backlog is `docs/ROADMAP.md`. This file is a
> short, plain-English summary of what the shim is *not* — keep the per-feature detail in those two
> docs so this one can't drift.

## What ProtoGemCouch is

A **scoped, production-candidate** Geode/GemFire→Couchbase protocol shim with a broad, real-client-
validated surface — **not** a full Apache Geode server replacement. The validated surface today
includes core CRUD, bulk `putAll`/`getAll`, key-metadata (`size`/`keySet`), atomic ops, OQL queries
(WHERE/projection/ORDER BY/paging/parameters, incl. PDX field access), transactions, continuous
queries, register-interest/subscriptions (server→client events), graceful function rejection, region
`clear`/`invalidate`/`destroyRegion`, TTL, durability, large-value limits, a broad value-type profile
(scalars, wrappers, primitive/typed arrays, `Object[]`, `ArrayList`, `HashMap<String,Object>` with
recursively-nested values, UUID/BigInteger/BigDecimal/enum, PDX incl. schema evolution, opaque
Serializable POJOs and DataSerializable), TLS/mTLS, and a full observability stack (Prometheus metrics
+ Grafana dashboards, Alertmanager, OpenTelemetry tracing, Loki logs). See the matrix for specifics.

## What it is not / does not do

**Not a full Geode server.** No full distributed-region semantics, no server-side execution of user
code, no full native wire parity.

**Deliberate non-goals** (a real client gets a clean error or a documented no-op, not a crash):
- **Server-side function execution** — the shim has none of the user's `Function` classes; calls are
  rejected cleanly (`docs/FUNCTIONS.md`).
- **Partitioned-region single-hop** — the shim is a single logical backend with no bucket topology, so
  `GET_CLIENT_PARTITION_ATTRIBUTES` is a documented graceful no-op (the client uses direct routing).
- **Server-side region creation with custom attributes** — `destroyRegion` is supported; the schemaless
  shim serves any region on first use, so dynamic create is a no-op.
- **The Geode application-level security handshake** — use transport TLS / mutual TLS instead.

**Queryable only at the top level / scalar fields.** OQL and CQ predicates resolve top-level scalar
fields of `HashMap<String,Object>` and PDX values. **Not queryable** (preserved opaquely — they
round-trip but their fields aren't readable): customer **Serializable POJOs**, **custom
DataSerializable** values, **PDX OBJECT/array fields**, and **nested complex values requiring the
user's classes**. The keyset-metadata operations (`REMOVE`/`PUT_ALL`/`SIZE`/`KEY_SET`) are a separate,
much more expensive performance class and become pathological at very large keyspaces — treat them as
cold-path, not hot-path (see `docs/SOAK_RESULTS.md`).

**Scope-expansion items still open** (tracked in `ROADMAP.md` §3): full PDX registry discovery + schema
evolution beyond the per-type path; `DataSerializable` *field* access (needs the classes); arbitrary
object graphs / complete DataSerializer marker coverage; protocol version negotiation; full opcode
golden-wire coverage; `CacheLoader`/`CacheWriter` and expiration/eviction listeners.

**Capacity qualification** is characterized on a dedicated rig (`docs/SOAK_RESULTS.md`: per-shim read
ceiling ~16.9k ops/sec, near-linear two-shim scaling, shim-CPU-bound with Couchbase headroom).
Remaining: the 4+-shim point and failure-injection-at-scale — both reproducible on the `deploy/` rigs.

## Positioning

> A Geode-to-Couchbase protocol shim with a broad, real-client-validated compatibility profile and a
> full production-readiness/observability story, suitable as a scoped compatibility layer — not a
> drop-in, fully-native Geode server replacement.
