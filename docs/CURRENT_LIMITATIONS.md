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
- **Server-side cache callbacks** — `CacheLoader`/`CacheWriter`/`CacheListener` *registered on a server
  region*, and server-side expiration/eviction *events*, would run user code / synthesize events on the
  server, which the stateless shim does not host (TTL is applied via Couchbase expiry). **Client-side**
  cache callbacks are supported: a client's `CacheLoader` fills a get-miss, a `CacheWriter` vetoes or
  allows a write before it is sent, and `CacheListener`s fire on the shim's server-pushed events.

**Queryable only at the top level / scalar fields.** OQL and CQ predicates resolve top-level scalar
fields of `HashMap<String,Object>` and PDX values. **Not queryable** (preserved opaquely — they
round-trip but their fields aren't readable): customer **Serializable POJOs**, **custom
DataSerializable** values, **PDX object-array fields** (arrays of nested PDX — scalar arrays and nested
object paths *are* queryable as of 1.1.0-M3), and **nested complex values requiring the user's classes**.
The keyset-metadata operations (`REMOVE`/`PUT_ALL`/`SIZE`/`KEY_SET`) are a separate, much more expensive
performance class: each is **O(region size)** (the per-region keyset document is read/rewritten whole),
so treat them as cold-path, not hot-path. With the default single per-region keyset document, this also
imposes a hard **per-region key-count ceiling** — Couchbase's 20 MiB document limit caps a region at
roughly `20 MiB / (avg_key_length + 3)` keys (~2.1M for short keys, ~400k for 50-char keys); CRUD by key
is unaffected. Quantified in `docs/SOAK_RESULTS.md` (keyset-metadata at-scale characterization).

**Mitigation (1.2.0-M2): keyset sharding.** Set **`KEYSET_SHARDS=N`** (default 1) to split each region's
keyset across N docs (`floorMod(key.hashCode(), N)`), which lifts the ceiling ~N× and shrinks each
`REMOVE`/`PUT_ALL`/commit rewrite to ~`region/N` keys (measured: `REMOVE` flattens vs growing). It stays
cross-process-safe and behavior is byte-identical at `N=1`. Set it at deploy time — changing the count
over existing data requires a keyset rebuild. `KEY_SET`/`SIZE` remain O(region) (they must return all
keys) but read shards in parallel.

**Scope-expansion items still open** (tracked in `ROADMAP.md` §3): full PDX registry discovery + schema
evolution beyond the per-type path; `DataSerializable` *field* access (needs the classes); arbitrary
object graphs / complete DataSerializer marker coverage; full opcode golden-wire coverage.

**Capacity qualification** is characterized on a dedicated rig (`docs/SOAK_RESULTS.md`: per-shim read
ceiling ~16.9k ops/sec, near-linear two-shim scaling, shim-CPU-bound with Couchbase headroom).
Remaining: the 4+-shim point and failure-injection-at-scale — both reproducible on the `deploy/` rigs.

## Positioning

> A Geode-to-Couchbase protocol shim with a broad, real-client-validated compatibility profile and a
> full production-readiness/observability story, suitable as a scoped compatibility layer — not a
> drop-in, fully-native Geode server replacement.
