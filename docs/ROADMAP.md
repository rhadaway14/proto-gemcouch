# ProtoGemCouch Roadmap

A living backlog. **ProtoGemCouch reached 1.0.0 GA on 2026-06-20** — the road-to-1.0 work (Level 4
production-readiness and the supportable GemFire/Geode SDK parity surface) is recorded as DONE in the
sections below. **1.1.0 GA shipped 2026-06-21, 1.2.0 GA shipped 2026-06-24, 1.3.0 GA shipped
2026-06-26** (all recorded below); **1.4.0 M1–M3 are DONE and M4 hardening is in progress** (theme:
OQL query completeness). The contract for what is supported today is `docs/COMPATABILITY_MATRIX.md`.

Legend: `[x]` done · `[~]` in progress · `[ ]` todo.

---

## 1.1.0 — SHIPPED 2026-06-21 (performance + operability + parity-depth)

1.1.0 made OQL fast (N1QL pushdown), made the in-memory state observable and bounded, and lifted the
top "scalars only" parity limit (nested-object + scalar-array field querying). All M1–M4 below are DONE
and the `v1.1.0` GA shipped. (Kept here as the delivered record; the **current focus is 1.2.0**, further
below.)

### 1.1.0-M1 — Operability: registry observability + bounds · **DONE** (merged, ahead of the 2026-07-03 target)
- [x] Prometheus gauges for the in-memory registries: PDX type & enum registry size, active
  transactions, subscription feeds, registered interests/CQs, durable clients + durable-queue depth
  (sampled at scrape; also in the JSON snapshot).
- [x] Optional cap (+ `audit` event + `protogemcouch_pdx_registry_rejected_total` metric) on the PDX
  type/enum registry via `MAX_PDX_TYPES` / `MAX_PDX_ENUMS` (0 = unlimited); already-registered types are
  still served.
- [x] Grafana panels (registry sizes) + Alertmanager rules (`ProtoGemCouchPdxRegistryRejections`,
  `ProtoGemCouchDurableQueueBacklog`).
- Validated: `PdxRegistryCapTest`, `MetricsRegistryGaugeTest`, `ProtoGemCouchRegistryMetricsIntegrationTest`
  (/metrics exposes the gauges end-to-end); docs updated (`OBSERVABILITY.md`, `SECURITY.md`, `RUNBOOK.md`).

### 1.1.0-M2 — OQL query pushdown (headline) · target 2026-08-01
- [x] **Slice 1 — string-equality pushdown via N1QL.** Decision: N1QL + a managed secondary index
  (values are already stored as JSON; the dev/test cluster runs `data,index,query`). A single `AND` of
  `field = 'string'` conditions pushes a region-scoped, **superset** N1QL predicate (covers both map
  encodings + all non-map/PDX docs) with `REQUEST_PLUS` consistency; the shim's matcher re-filters
  authoritatively, so results are identical to the scan and anything ineligible/any error falls back to
  it. Behind the **`OQL_PUSHDOWN`** flag (default off). Validated end-to-end against a real Geode client
  (`ProtoGemCouchQueryPushdownIntegrationTest`, incl. PDX + mixed regions) + `OqlQueryPushdownTest`.
- [x] **Slice 2 — numeric equality + range pushdown.** `field = <num>` / `< <= > >=` numeric literals
  now push down too (via `TO_NUMBER(...) <op> $n` + a `TYPE="string"` superset escape), AND-combinable
  with string equality; numeric `<>`/`!=`, string ranges, and `OR` still scan. Real-client validated
  (numeric eq/range, mixed AND, PDX numeric range). (`OqlQuery.pushdownPredicates`.)
- [x] **Slice 3 — `LIMIT`.** `LIMIT n` is now parsed + applied in-shim (it was previously a query
  error), and pushed to N1QL for pushdown-eligible queries with no `ORDER BY` (capping backend rows),
  guarded by an unbounded refetch when a capped page does not yield enough matches. `ORDER BY`+`LIMIT`
  applies the cap in-shim after the sort. Real-client validated (pushed cap, `LIMIT` > matches, scan
  `LIMIT`, top-N, non-map guard).
- [x] **Slice 4 — PDX scalar-field pushdown.** PDX is stored opaquely, so when pushdown is on the shim
  writes a queryable `pdxFields` scalar sidecar at write time; the N1QL predicate filters PDX docs on it
  (or keeps un-enriched PDX docs as candidates via `pdxFields IS MISSING`), so a PDX-heavy region is now
  selective, not swept. Real-client validated (6/10 active PDX selected; PDX with a non-scalar field).
- [x] **Slice 5 — `LIMIT`-without-`WHERE` push + partial-predicate push.** `SELECT * FROM /r LIMIT n`
  now pushes a region-scoped capped query (no full-region scan); and a mixed `AND` pushes its eligible
  subset (skipping ineligible conditions) with the shim re-applying the full `WHERE` — both validated
  real-client. (`OqlQuery.pushdownPredicates` returns the eligible subset; `hasWhere()` gates the
  region-`LIMIT` push.)
- [x] **Slice 6 (capstone) — perf-gate query-p99.** New `query-heavy` benchmark profile +
  `BENCH_QUERYABLE_VALUES` mode (seeds map values with a top-level `k` field and runs `WHERE k = N`, a
  real pushdown-eligible query). The runner emits `query_p99_ms`; `scripts/perf-gate.sh` enforces a
  `PERF_MAX_QUERY_P99_MS` ceiling (and a throughput floor set just above the scan rate, so a silent
  regression to scanning trips it). A CI query-gate (`.github/workflows/perf-gate.yml`) runs it against
  the pushdown shim with an indexed field. Measured local win: ≈6× throughput / ~5× lower query p99
  (pushdown on vs full-scan). `scripts/perf-baseline.query.env` (CI) + `perf-baseline.rig.env` (tight).
- Exit: **M2 COMPLETE** — query correctness unchanged vs a real Geode client across all slices; the
  pushdown win is measured and perf-gated.
- [ ] Managed-index lifecycle: documented operator `CREATE INDEX` step (done in `docs/OQL.md`);
  optional create-on-first-use later.
- [ ] Re-validate query p99 on the soak; add a query-weighted benchmark profile + a perf-gate query-p99
  threshold.
- Exit: query correctness unchanged vs a real Geode client; soak shows the p99 drop; `docs/OQL.md`
  updated. Risk: medium (changes the query path) — the critical path of 1.1.0.

### 1.1.0-M3 — Parity: PDX nested/object/array field querying · target 2026-08-22
- [x] **Nested object paths (`order.address.zip`) — DONE.** Field references are parsed into alias-aware
  navigation paths; the shared resolver descends nested Geode maps by key and nested PDX objects by raw
  bytes (`PdxReaderImpl.getRaw` + recurse with the shim's own `PdxTypeRegistry`). Works for `WHERE` /
  projection / `ORDER BY` **and CQ** (shared resolver). Real-client validated: nested map query, nested
  PDX query, nested-field CQ (`ProtoGemCouchQueryIntegrationTest`, `ProtoGemCouchCqIntegrationTest`) +
  `OqlQueryTest` (path parse/nav). Nested predicates resolve in the matcher (not pushed down), so query
  correctness is unchanged.
- [x] **Array fields — DONE.** A `[index]` suffix in a path indexes a `List`/array (`r.tags[0]`,
  `r.addresses[0].zip`), and `<literal> IN <path>` tests containment (`'gold' IN r.tags`). PDX scalar
  arrays (`String[]`/`int[]`/`long[]`/`short[]`/`double[]`/`float[]`/`boolean[]`/`char[]`) are read via
  `PdxReaderImpl`'s typed readers; a scalar-array leaf resolves to the whole list so `IN` can scan it.
  Real-client validated (`pdxScalarArrayIndexAndContainmentQuery`) + `OqlQueryTest`. Object-arrays
  (arrays of nested PDX) and `byte[]` remain out of scope (documented).
- [x] `COMPATABILITY_MATRIX.md` updated.
- Exit: **M3 COMPLETE** — scalar, nested-object, and scalar-array field querying for maps + PDX, in
  `WHERE`/projection/`ORDER BY` and CQ; query correctness unchanged vs a real Geode client.
- Swap option (not taken): multi-replica durable subscriptions remain in the 1.2.0 backlog.

### 1.1.0-M4 — Hardening + RC → 1.1.0 GA · freeze 2026-08-31 · RC 2026-09-02 · GA 2026-09-04
- [x] **Cross-version client matrix in CI — DONE.** New `.github/workflows/cross-version-matrix.yml`
  runs real Geode `1.13.0`/`1.14.0`/`1.15.1` clients (standalone `cross-version-client/` harness, public
  client API only — no shim recompile, since the shim's internal-API use is version-sensitive) against a
  shim with `SUPPORTED_VERSION_ORDINALS=120,125,150`, asserting CRUD/bulk/metadata + OQL over map & PDX.
  Validated the harness + widened-shim path locally with the cached 1.15.1 client (the 1.13/1.14 entries
  resolve + run online in CI). `COMPATABILITY_MATRIX.md` updated.
- [x] **Keyset-metadata at-scale re-characterization — DONE.** Quantified the cold path with
  `tools.KeysetScaleProbe`: `KEY_SET`/`SIZE`/`REMOVE`/`PUT_ALL` are O(region size) (whole-keyset-doc
  read or CAS-rewrite) while single `PUT` stays flat (sub-doc append) and CRUD-by-key is O(1). Measured
  ~9→21 ms `KEY_SET`/`REMOVE` over 5k→50k keys; the per-region keyset doc imposes a hard key-count
  ceiling at Couchbase's 20 MiB limit (~`20 MiB / (key_len + 3)` keys, ~2.1M for short keys), not gated
  by `CB_MAX_VALUE_BYTES`. Documented in `docs/SOAK_RESULTS.md`, `CURRENT_LIMITATIONS.md`, `RUNBOOK.md`.
- [x] **Full-surface soak re-run with pushdown enabled + security re-review of the new query/index path —
  DONE.** Soak against an `OQL_PUSHDOWN=true` shim (queryable values + GSI, subscriptions on): 155k ops,
  **0 shim request errors**, 8,277 pushdown queries / 0 fallbacks, 138k interest events, no connection
  leak → `SOAK_VERDICT PASS` (`docs/SOAK_RESULTS.md`). Security review found no N1QL injection (params +
  strict field-name validation), a size-bounded `pdxFields` sidecar, hardcoded+guarded `getRaw`
  reflection, and query-string-bounded recursion (`docs/SECURITY.md`).
- [x] **`CHANGELOG.md` `[1.1.0]`; cut `v1.1.0-rc1` → verify → cut `v1.1.0` GA — DONE.** Version bumped
  (pom + Helm chart/appVersion + image tag); CHANGELOG finalized. rc1 surfaced a CI perf-gate
  threshold mis-calibration (fixed: wait for the GSI online + CI-tolerant gross-guard thresholds); rc2
  went green on all four gates (full `mvn verify`, signed image, perf-gate, cross-version matrix with
  real 1.13/1.14/1.15 clients). **`v1.1.0` GA cut on the green rc2 commit + GitHub Release published.**

---

## 1.2.0 — SHIPPED 2026-06-24 (theme: HA + scale + operability depth)

1.2.0 built on the 1.1.0 query/observability work toward **high availability, scale, and operability
depth**. All items were additive/non-breaking (a semver minor). Shipped well ahead of the targets below.

### 1.2.0-M1 — Multi-replica durable subscriptions (headline) · **COMPLETE** (all 4 slices, ahead of the 2026-07-18 target)
Today durable state (retained interest + the disconnect-time event queue, in `SubscriptionRegistry`'s
`DurableState`) is **in-memory per shim instance**, so a durable client that reconnects to a *different*
replica (after a failover) loses its queue. Decided architecture: **full HA via a Couchbase-backed
durable registry + single-writer origin enqueue** — persist each durable client's interest/CQs + queue
in Couchbase; the replica that *processes a mutation* (its origin) appends matching events to away
durable clients' queue docs (single writer per event → no dedup, and no dependence on the client's former
owner replica → survives any replica failing). Behind a flag (default off) for safe rollout.
- [x] **Slice 1 — Couchbase durable-queue persistence primitive — DONE.** Repository methods (default
  no-op + `CouchbaseRepository` impl) over a `__protogemcouch::durable::<id>` doc: `saveDurable` /
  `loadDurable` of the durable record (`DurableRecord`: interests, CQs, timeout, away-flag) via
  sub-document upserts that don't clobber the queue; `enqueueDurableEvent` (atomic subdoc
  `arrayAppend`, bounded by `DURABLE_MAX_QUEUE` — oldest dropped on overflow); `drainDurableQueue`
  (CAS-guarded clear so a concurrent enqueue is never lost); `dropDurable`. Behind the
  `DURABLE_PERSISTENCE` flag (default off → the methods are no-ops and single-instance behavior is
  unchanged); `TracingRepository` delegates all five. Validated by `DurableRecordCodecTest` (codec
  round-trip) + `DurablePersistenceIntegrationTest` (7 cases against real Couchbase: save/load, drain
  ordering, the bound, save-doesn't-clobber-queue, drop, and the off-by-default no-op).
- [x] **Slice 2 — wire `SubscriptionRegistry` — DONE.** Behind `DURABLE_PERSISTENCE` (default off →
  in-memory behavior unchanged): on a durable feed's disconnect the registry persists its record
  (`saveDurable`, away) and flushes its queue to Couchbase; while away, matching events
  `enqueueDurableEvent` to Couchbase instead of in-memory; on reconnect to **any** replica +
  `CLIENT_READY` it `drainDurableQueue`s and replays (and `markDurableAway(false)`); a periodic
  `sweepExpiredDurable` (`DURABLE_SWEEP_SECONDS`, default 60) reclaims expired docs cross-replica, so
  the local per-client expiry timer only frees memory and never drops a doc the client may have
  reconnected to elsewhere. Added the `markDurableAway` primitive (flip away/awaySince without
  rewriting interests). Validated real-client by
  `ProtoGemCouchDurablePersistenceMultiReplicaIntegrationTest` (durable client on replica A → A killed
  feed → mutation enqueued on A → reconnect to **replica B** → replay from Couchbase), with the existing
  single-instance durable suite still green under persistence. (CQ-*definition* persistence is deferred
  to Slice 3 — durable CQ *events* already replay via the persisted queue.)
- [x] **Slice 3 — cross-replica origin enqueue — DONE.** The replica that processes a mutation (its
  *origin*) enqueues matching events for *all* away durable clients by reading the persisted registry —
  not just the ones it owned in memory — so delivery survives the client's former owner replica failing.
  `Repository.listAwayDurable()` (N1QL) feeds a background-refreshed cache (`DURABLE_AWAY_REFRESH_MS`,
  default 1000); the **local origin** publish path is the single writer (backplane echoes don't enqueue →
  exactly-once); clients stay "away" until `CLIENT_READY` so reconnect-window events are captured. **3a:
  interest events** (replaces Slice 2's in-memory/owner enqueue). **3b: CQ events** — CQ OQL text is now
  captured at `registerCq` and persisted in the durable record, so the origin recompiles (cached) +
  evaluates each away client's CQ and enqueues CQ create/update/destroy. Real-client validated by
  `ProtoGemCouchDurablePersistenceMultiReplicaIntegrationTest` (`nonOwnerReplicaEnqueuesForAwayClientFromTheRegistry`
  for interest, `nonOwnerReplicaEnqueuesCqEventForAwayClient` for CQ): a mutation on replica **B**, which
  never owned the client, replays on reconnect. Known bound: away-registry cache freshness ≈ the refresh
  interval.
- [x] **Slice 4 — multi-replica (k8s) failover validation — DONE.** Validated on the real Kubernetes
  test cluster with **no eventing backplane** (so delivery comes purely from the Couchbase registry/queue,
  not any in-memory cross-replica path): a 2-replica shim (`DURABLE_PERSISTENCE=true`), a durable client
  subscribes on **replica A**, **A is hard-killed**, a mutation lands on **replica B** (B's origin enqueues
  for the away client from the persisted registry), and the durable client **reconnects to B and replays
  the missed event** → `DURABLE_FAILOVER_CHECK PASS`. Tooling: `tools.DurableFailoverCheck` (subscribe /
  mutate / verify roles) driven by `scripts/k8s-durable-failover-e2e.sh` (deploy → kill → mutate → verify),
  mirroring the mesh-e2e pattern. **1.2.0-M1 COMPLETE** — durable subscriptions now survive a replica
  failing and replay on reconnect to any replica, for both interest and CQ events.

### 1.2.0-M2 — Keyset-metadata at-scale improvement · **DONE** (ahead of the 2026-08-08 target)
- [x] **Sharded keyset metadata.** The per-region keyset is split across **`KEYSET_SHARDS`** docs
  (`__protogemcouch::keyset::<region>::s<n>`), each key routed by `floorMod(key.hashCode(), shards)`
  (`String.hashCode` is spec-deterministic → a key maps to the same shard on every replica, so it stays
  cross-process-safe). This **lifts the single-doc 20 MiB key-count ceiling ~N×** (each shard is its own
  20 MiB doc) and shrinks every `REMOVE` / `PUT_ALL` / TTL-evict / commit rewrite to ~`region/N` keys;
  `KEY_SET`/`SIZE` read all shards **in parallel**. The hot contention-free single-key add (sub-document
  `arrayAddUnique`) and the CAS remove are preserved exactly, now per-shard. **`KEYSET_SHARDS=1`
  (default) reuses the legacy single-doc id → byte-identical behavior**, so sharding is opt-in (set at
  deploy time; changing the count over existing data needs a keyset rebuild — documented).
- [x] **Identical-behavior + concurrency validated** real-client: `ProtoGemCouchKeysetShardingIntegration
  Test` (16 shards — exact `size`/`keySet` under concurrent puts, cross-shard removes, and clear) plus the
  existing `ProtoGemCouchKeysetConcurrencyIntegrationTest` still green on the unsharded default path.
- [x] **`KeysetScaleProbe` re-run** (unsharded vs 16-shard, 5k/20k keys): `REMOVE` flattens — unsharded
  8.8→11.7 ms (5k→20k, grows with the whole-doc CAS) vs sharded ~6.0→5.3 ms (rewrites ~1/16); `PUT` flat
  for both (~2–3 ms, sub-doc append); `KEY_SET`/`SIZE` comparable (inherently O(region), parallelized).
- Exit: **M2 COMPLETE** — keyset scales past the single-doc ceiling, behavior unchanged by default.

### 1.2.0-M3 — Operability + parity depth · target 2026-08-29
- [x] **Hot TLS cert reload — DONE.** `TLS_RELOAD_SECONDS=<n>` (default 0 = off) makes the shim poll the
  keystore/truststore (a content hash, so it catches a k8s Secret's `..data` symlink swap that a
  file-watch misses) and, on change, rebuild the Geode-listener `SslContext` and swap it for **new**
  connections — no restart; established TLS sessions are untouched. A partial/bad keystore is ignored
  (old context kept, retried next poll), so TLS never breaks; the swap is logged + audited. `shimSslContext`
  is now volatile (read per-connection); `TlsCertReloader` does the polling. Validated by
  `TlsCertReloaderTest` (real keytool keystores: rotate→rebuild+swap, unchanged→keep, corrupt→keep) +
  full unit suite/coverage green. Caveat: covers the Geode listener; the `HEALTH_TLS_ENABLED` admin
  endpoint still rotates via restart. Docs: `SECURITY.md` (rotation now hot-reloadable).
- [x] **Broader DataSerializer marker coverage — `java.time` nested scalars DONE.** `Instant`,
  `LocalDate`, and `LocalDateTime` nested inside a `HashMap<String,Object>` now decode **structurally**
  (queryable, exact round-trip) instead of falling to the opaque Java-serialized path — added to
  `NestedValueSupport`'s structured set and the nested JSON codec (stored as their ISO-8601 string, which
  parses back to an equal value). The inbound deserialization already allowed `java.time`
  (`SafeDeserialization`). Validated: `NestedComplexTypesTest` (real inbound Java-deserialization decode),
  `CouchbaseRepositoryRoundTripPropertyTest` (2000 iters across the JSON boundary), and the real-client
  `ProtoGemCouchRoundTripPropertyIntegrationTest` (`RandomValueGraphs` now emits java.time → put/get +
  putAll/getAll through a live Geode client + shim + Couchbase). Still opaque-when-nested (round-trip
  only, need the user's classes or exact component types): Serializable POJOs, PDX instances, typed
  object arrays (`Integer[]`/`UUID[]`/…), and non-`ArrayList` `List`s.
- [x] Optional **4+-shim horizontal-scale characterization — DONE** (2026-06-23, EC2 rig `shim_count=4`,
  `loadgen_count=4`, read-heavy): **~58k aggregate ops/sec, 0 errors**, extending the near-linear curve
  16.9k → 35k → 58k (1/2/4 shims). CPU-attributed mid-load: shim hosts ~95% busy (saturated), the single
  `r6i.xlarge` Couchbase only ~21% — shim-CPU-bound with backend headroom past four shims. See
  `docs/SOAK_RESULTS.md`. (Also fixed the rig's `git_ref` default `master`→`main` after the trunk migration.)
- **1.2.0-M3 COMPLETE** (hot TLS reload + nested java.time coverage + the 4-shim characterization).

### 1.2.0-M4 — Hardening + RC → 1.2.0 GA · **COMPLETE — 1.2.0 GA shipped 2026-06-24** (ahead of the 2026-09-11 target)
- [x] **Slice 1 — durable-replay IT hardening (DONE).** The CQ/multi-replica durable-replay ITs flaked
  on starved CI runners (event "never replayed") because the Phase-2 mutation could fire before the
  origin replica's away-registry cache (refreshed on `DURABLE_AWAY_REFRESH_MS`) had picked up the
  now-away client, so nothing was enqueued. Fixed by exposing `protogemcouch_durable_away_registered`
  (the size of a replica's away-registry scan set — also useful operationally) and gating each test on
  the *enqueuing* replica seeing the away client (poll up to 30s) before mutating, replacing the blind
  `Thread.sleep`s. Verified: full unit suite + all 5 durable ITs green, twice back-to-back.
- [x] **Slice 2 — soak the new HA/scale paths (DONE).** EC2 rig (4 shims/2 load-gens), self-driving
  chaos (mixed load + latency/loss/pause/partition/hard-outage) with `KEYSET_SHARDS=8` +
  `DURABLE_PERSISTENCE=true`, 100k keys. The soak **found a GA-blocking bug**: a backend hard-outage
  under load drove the handler queues (64×10,000=640k) to OOM, which tore down the Netty executor and
  left the JVM wedged-but-alive (rejecting everything, `running=true,exit=0,restarts=0` → no restart).
  **Fixed** with backpressure (`DEFAULT_MAX_PENDING_TASKS` 10,000→256 so it sheds before OOM) +
  fail-fast (`-XX:+ExitOnOutOfMemoryError`, `halt(1)` on abnormal listener close). Re-soak **PASS**: all
  4 shims survived the fault scenario (`ready=200`), self-recovered, shed excess load (~5.5M/shim)
  instead of dying. Also hardened the benchmark seed (concurrent + retry). See `docs/SOAK_RESULTS.md`.
  (Durable-subscription *failover* itself was validated on k8s in M1 Slice 4.)
- [x] **Slice 3 — security re-review (DONE).** Re-reviewed the surface through 1.2.0 against the code
  and refreshed `SECURITY.md`: new **durable-subscription persistence** section (what's persisted to
  Couchbase, no new N1QL-injection/eval surface — CQ recompiled in-memory via the same parser,
  durable-client-id is client-supplied identity → use mTLS, bounded `DURABLE_MAX_QUEUE`); documented the
  **memory-exhaustion resilience** guards (shed-before-OOM + `-XX:+ExitOnOutOfMemoryError`) and fixed a
  stale `HANDLER_MAX_PENDING_TASKS` default (10000→256); confirmed the deserialization allowlist already
  covers `java.time` and hot-TLS-reload is documented. Also fixed the **k8s failover e2e** flake: its
  blind `sleep 6` (which raced B's REQUEST_PLUS away-registry refresh) is now an
  `await_away_registered` gate (mirror of the Slice 1 IT fix) — validated on the cluster, 2/2 PASS.
- [x] **Slice 4 — cross-version matrix (DONE).** 1.2.0 introduces **no new client-facing wire forms**
  (durable HA reuses the version-negotiated durable-client protocol + unchanged interest/CQ event forms;
  sharding/hot-TLS are server-internal; nested `java.time` is server-side queryability only), so the
  validated client range is unchanged. Re-ran the cross-version CI matrix on 1.2.0 — **real Geode
  1.13.0 / 1.14.0 / 1.15.1 clients all PASS**. `COMPATABILITY_MATRIX.md` refreshed to "as of 1.2.0"
  (wire-compat note + durable HA in the supported surface).
- [x] **Slice 5 — release (DONE).** `CHANGELOG.md` `[1.2.0]` + version bumps (pom, Helm chart, image
  tag) merged; cut **`v1.2.0-rc1`** → all release pipelines green (verify, perf, cross-version,
  Trivy+cosign image); then cut **`v1.2.0` GA** on the same commit (`311870b`) with operator approval —
  pipelines green again, GitHub Release published, GA image `1.2.0` live (matches the chart pin).
  **1.2.0 GA shipped 2026-06-24.**

---

## 1.3.0 — SHIPPED 2026-06-26 (theme: parity completeness — full value-type fidelity & queryability)

With HA + scale delivered (1.2.0), 1.3.0 closed the remaining **value-type fidelity / queryability** gaps
documented in `docs/CURRENT_LIMITATIONS.md` — the dominant "not queryable / preserved opaquely" caveats —
so more real Geode workloads run unchanged. All items were additive/non-breaking (semver minors, no new
client-facing wire forms). **All M1–M4 below are DONE and the `v1.3.0` GA shipped** (signed image
`docker.io/rhadaway14/protogemcouch:1.3.0`, GitHub Release published), ~2.5 months ahead of the original
2026-09-10 GA target.

### 1.3.0-M1 — PDX object-array field querying (headline) · **COMPLETE** (3 slices, ahead of the 2026-07-16 target)
Arrays of nested PDX objects (a PDX field that is a `PdxInstance[]`) were **not queryable** — scalar
arrays and single nested-object paths landed in 1.1.0-M3, but object-arrays were explicitly deferred.
OQL/CQ path resolution now navigates them, without the user's classes (PDX is self-describing), reusing
the existing array-index + nested-PDX (`PdxReaderImpl`) reflection path. Completes the PDX query surface.
- [x] **Slice 1 — indexed navigation (PR #19).** `PdxFieldAccessor` reads an `OBJECT_ARRAY` field's raw
  bytes (`getRaw`) and walks the `DataSerializer.writeObjectArray` form — a `writeArrayLength` prefix, the
  component-type header (`DSCODE.CLASS` + class name, e.g. `org.apache.geode.pdx.PdxInstance`), then each
  `writeObject` element — slicing each self-framed nested-PDX element (`0x5d <len> <typeId> <data>`) to
  recurse with the shim's own `PdxTypeRegistry`. So `r.addresses[0].zip` (and deeper) resolves in
  `WHERE` / projection / `ORDER BY` and CQ (shared resolver). Also widened the projection-field guard to
  accept per-segment `[index]`. Real-client validated (`pdxObjectArrayIndexedFieldQuery`).
- [x] **Slice 2 — `IN` containment + edge-case hardening (PR #20).** An object-array leaf returns its
  element list, so `<literal> IN r.<field>` does **element-equality** containment; scalar string elements
  decode (`DSCODE.STRING 0x57` + `writeUTF`, the same framing as the component-type header) so
  `'a@x.com' IN r.contacts` over a string `Object[]` matches. By element-equality a scalar literal never
  equals a nested-PDX *object* element (use indexed access for objects — documented). Out-of-range index,
  null / unknown-DSCODE / truncated elements, and empty / null arrays all resolve cleanly (never throw).
  Real-client validated (`pdxObjectArrayInContainmentAndIndexEdgeCases`) + CQ parity
  (`cqListenerFiresForPredicateMatchingPdxObjectArrayElementField`, new `PutOnce` `pdxobjarray` op).
- [x] **Slice 3 — docs + matrix (this).** `docs/OQL.md`, `docs/COMPATABILITY_MATRIX.md`, and
  `docs/CURRENT_LIMITATIONS.md` updated (object-array querying supported; the "arrays of nested PDX not
  queryable" caveat removed).
- Exit: **M1 COMPLETE** — scalar, nested-object, scalar-array, and **object-array** field querying for
  maps + PDX, in `WHERE` / projection / `ORDER BY` and CQ; query correctness unchanged vs a real Geode
  client. The version bump + GA tagging happen in M4 (operator-gated).

### 1.3.0-M2 — PDX registry discovery + schema-evolution depth · **COMPLETE** (3 slices, ahead of the 2026-08-06 target)
The PDX type/enum registry was in-memory per shim instance with a local id counter, so ids were lost on
restart and assigned inconsistently across replicas — a PDX value written via one replica could mis-resolve
(or fail to resolve) via another. M2 makes the registry **durable + cluster-wide-consistent** behind
**`PDX_PERSISTENCE`** (default off → in-memory behavior byte-identical), mirroring the 1.2.0-M1
durable-subscription pattern.
- [x] **Slice 1 — persistence primitive (PR #22).** `Repository` no-op defaults + `CouchbaseRepository`
  impl + `TracingRepository` delegation: `allocatePdxTypeId`/`allocatePdxEnumId` (atomic cluster-wide id
  via a Couchbase counter; idempotent per fingerprint via insert-if-absent, race-loser adopts the
  winner's id), `loadPdxType`/`loadPdxEnum` (reverse), `loadAllPdxTypes`/`loadAllPdxEnums` (bulk, N1QL).
  Real-Couchbase IT (7): idempotent/distinct, reverse load, restart-durability, the concurrent-allocation
  race, bulk, enum parity, off-by-default no-op.
- [x] **Slice 2 — registry wiring + multi-replica (PR #23).** `PdxTypeRegistry`/`PdxEnumRegistry` allocate
  via the repository when the flag is on (else the local counter — byte-identical); `getPdxType` /
  `serializedPdxType` (`GET_PDX_TYPE_BY_ID`) / `serializedEnum` load-on-miss from Couchbase and stamp the
  id, so any replica resolves any id. `docker-compose` runs the `protogemcouch` + `-replica` pair with
  `PDX_PERSISTENCE=true`. Real-client multi-replica IT: a PDX value written via replica A is decoded +
  queried on replica B (which never registered the type). No regression across the PDX IT suite with the
  flag on.
- [x] **Slice 3 — discovery hardening + schema-evolution-at-scale + docs (this).** Bulk
  `GET_PDX_TYPES`/`GET_PDX_ENUMS` unions the persisted cluster-wide registry, so a fresh replica serves it
  all; a multi-replica IT registers many evolving versions on A and resolves + per-version-queries them on
  B; `COMPATABILITY_MATRIX.md` / `CURRENT_LIMITATIONS.md` / `SECURITY.md` updated (the durability
  limitation lifted; `PDX_PERSISTENCE` documented).
- Exit: **M2 COMPLETE** — PDX type↔id is durable and consistent cluster-wide (opt-in); discovery, reverse
  lookup, and multi-version schema evolution resolve on any replica + after a restart. Version bump + GA
  tagging in M4 (operator-gated).

### 1.3.0-M3 — DataSerializer marker coverage + golden-wire completeness · **COMPLETE** (3 slices, ahead of the 2026-08-27 target)
Decoded a broader set of built-in/JDK `DataSerializer` markers **structurally** (more nested map values
queryable instead of opaque) and tightened the golden-wire request coverage.
- [x] **Slice 1 — typed object arrays nested (PR #26).** `Integer[]`, `Long[]`, `UUID[]`, `BigInteger[]`,
  `Instant[]`, `Date[]`, `enum[]`, … nested in a `HashMap<String,Object>` are now structured + queryable
  with **exact component-type fidelity** (the codec records the component class; `Arrays.equals` holds),
  instead of forcing the whole map opaque. `copyValue` preserves the component type. Queryable with no
  resolver change (`navigateMember`/`IN` already handle `Object[]`).
- [x] **Slice 2 — nested JDK Sets + non-`ArrayList` Lists (PR #27).** Any `List` (`LinkedList`, …) and
  any `Set` (`HashSet`/`TreeSet`/`LinkedHashSet`) nested in a map are now structured + queryable
  (equals-level — reconstruct as `ArrayList` / `LinkedHashSet`); Sets are queryable via `IN`. Closed a
  latent `copyValue` immutability leak for Sets.
- [x] **Slice 3 — golden-wire request fixtures + docs (this).** Captured + locked the two
  client-triggerable PDX registry request opcodes (`GET_PDX_ID_FOR_TYPE`, `GET_PDX_ID_FOR_ENUM`) as real
  golden-wire request fixtures (`tools.RequestWireCapture` extended), removing them from the exemption
  list (the bulk/reverse PDX ops remain exempt — internal-sync-driven, covered by the dedicated capture
  tools). Refreshed `COMPATABILITY_MATRIX.md` / `CURRENT_LIMITATIONS.md` (the nested opaque set is now
  just customer POJOs + nested PDX) + `CHANGELOG.md`.
- **Boundary (stays a non-goal):** customer `Serializable` POJO / custom `DataSerializable` *field* access
  and nested PDX-as-a-map-value need the user's classes (and the deserialization allowlist) — preserved
  opaque (round-trip only). Validated by the property + real-client round-trip suites (now emitting typed
  arrays, Sets, and `LinkedList`s) + new query ITs. Version bump + GA tagging in M4 (operator-gated).

### 1.3.0-M4 — Hardening + RC → 1.3.0 GA · **COMPLETE — 1.3.0 GA shipped 2026-06-26** (ahead of the 2026-09-10 target)
- [x] **Soak the new decode + PDX-persistence paths (PR #30).** A new benchmark **`BENCH_RICH_VALUES`** mode
  seeds/PUTs maps holding the M3 nested types (`Integer[]`/`UUID[]` typed object arrays, a `HashSet`, a
  `LinkedList`, an `Instant`), so the full-surface soak drives the new encode (PUT/seed) + decode (GET /
  full-region `QUERY` scan) paths. Ran 180s with `PDX_PERSISTENCE=true` + `DURABLE_PERSISTENCE=true` →
  **`SOAK_VERDICT PASS`** (errors=0, shed=0, conn_growth=0). See `docs/SOAK_RESULTS.md`.
- [x] **Security re-review of the expanded decode surface (PR #31).** 1.3.0 adds **no new
  untrusted-deserialization (CWE-502) surface** — the newly-structured types are already inside the
  `SafeDeserialization` allowlist (`java.lang`/`util`/`math`/`time` + arrays); the only new code
  (`resolveArrayComponent`'s `Class.forName` for a typed array's component class) is a class *load*, not a
  deserialize, allowlisted to JDK scalars or an enum (the same shape as the existing nested-enum decode).
  `SECURITY.md` re-reviewed-through-1.3.0.
- [x] **Cross-version matrix re-validation (PR #32).** Re-ran real Geode 1.13.0 / 1.14.0 / 1.15.1 clients
  against the 1.3.0 shim — green (1.3.0 adds no new client-facing wire forms). `COMPATABILITY_MATRIX.md`
  refreshed to "as of 1.3.0".
- [x] **Release (PR #33 → tags).** Version bumps 1.2.0→1.3.0 (pom, Helm chart version/appVersion, image
  tag) + `CHANGELOG.md` `[1.3.0]`. Cut **`v1.3.0-rc1`** → all four gates green (release-candidate `mvn
  verify`, perf-gate, cross-version matrix, Trivy+SBOM+cosign image); then **`v1.3.0` GA** on the same
  commit (`a4a67ee`) with operator approval — gates green again, signed image `1.3.0` published, GitHub
  Release published (https://github.com/rhadaway14/proto-gemcouch/releases/tag/v1.3.0).
- **1.3.0-M4 COMPLETE — 1.3.0 GA shipped 2026-06-26.**

---

## Current focus — 1.4.0 backlog (theme: OQL query completeness — aggregates, grouping, distinct)

The query **surface** is now broad — 1.1.0 made it fast (N1QL pushdown) and 1.3.0 completed the field-type
breadth (scalar / nested-object / scalar-array / object-array, over map + PDX). 1.4.0 makes OQL
**expressive**: the aggregate / grouping / distinct features real Geode workloads use, which today raise a
clean "unsupported" error. All items are additive/non-breaking (semver minors). The client *request* stays
standard OQL — the new *reply* shapes are reverse-engineered from a real Geode server and golden-wire-locked
(the same pattern that produced the original chunked query response). **Cross-region joins stay a documented
non-goal** (a Couchbase-backed shim would load both whole regions and cross-product them — poor ROI).
Milestone dates are nominal targets from a ~2026-06-26 start (the project has shipped each minor well ahead
of calendar).

### 1.4.0-M1 — Aggregate functions (headline) · **COMPLETE** (ahead of the 2026-07-17 target)
- [x] `SELECT COUNT(*)`, `COUNT(field)`, `SUM(field)`, `MIN(field)`, `MAX(field)`, `AVG(field)` over
  the WHERE-filtered set, computed in-shim. The aggregate reply wire shape reverse-engineered from a real
  Geode 1.15 server (via `tools.GeodeQueryCapture GROUP_BY_CAPTURE=1`) and golden-wire-locked. Works for
  map and PDX values. Validated end-to-end by `ProtoGemCouchAggregateQueryIntegrationTest`.

### 1.4.0-M2 — GROUP BY · **COMPLETE** (ahead of the 2026-08-07 target)
- [x] `SELECT <key>, <agg>(<field>|*) FROM /region [WHERE …] GROUP BY <key>` — groups the WHERE-filtered
  set by one or more key columns, computes per-group aggregates. Result is `SelectResults<Struct>` with
  real column names (`"0"` for COUNT; field name for SUM/AVG/MIN/MAX). `ORDER BY` and `HAVING` with
  `GROUP BY` deferred (real Geode 1.15 fails on `HAVING` too). Wire shape captured from a real Geode
  1.15 server. Works for map and PDX values. Validated by `ProtoGemCouchGroupByIntegrationTest`.

### 1.4.0-M3 — DISTINCT + parenthesized WHERE + OR pushdown · **COMPLETE** (ahead of the 2026-08-28 target)
- [x] **`SELECT DISTINCT`** — deduplicates projected rows using a `LinkedHashSet`; `SELECT DISTINCT *`
  raises `UnsupportedQueryException` cleanly (Geode semantics); `DISTINCT` is combined with `WHERE`,
  `ORDER BY`, `LIMIT`, and multi-field projections. Validated by `ProtoGemCouchDistinctQueryIntegrationTest`.
- [x] **Parenthesized AND/OR (`WHERE`)** — nested `AND`/`OR` with parentheses now parse to DNF
  (`List<List<Condition>>` or-of-and-groups) via a recursive `parseDnf` / `parseConjunction` /
  `parseAtomDnf` descent; `splitTopLevel` is paren-depth + string-literal-aware. Cross-product
  distribution handles `(A OR B) AND (C OR D)` → 4 groups. Parentheses previously raised
  `UnsupportedQueryException`. Validated by `OqlQueryParenWhereTest` (11 unit tests) +
  `ProtoGemCouchParenWhereIntegrationTest` (7 ITs).
- [x] **`OR` pushdown to N1QL** — `pushdownOrGroups()` returns one `FieldPredicate` list per OR branch
  (if all branches have eligible predicates); `CouchbaseRepository.queryPushdownByOrGroups` ORs the
  per-group N1QL clauses via `buildOrGroupsWhere` (delegating to `buildGroupAndClause` with the same
  `SAFE_FIELD` guard). Validated by `OqlQueryOrPushdownTest` (9 unit tests) + 4 new ITs in
  `ProtoGemCouchQueryPushdownIntegrationTest`.
- Exit: **M3 COMPLETE** — DISTINCT, paren WHERE (DNF), and OR pushdown all validated real-client; matrix
  + `docs/OQL.md` updated.

### 1.4.0-M4 — Hardening + RC → 1.4.0 GA · **IN PROGRESS** · GA target 2026-09-10
- [x] **Security re-review** — all M3 N1QL paths go through `SAFE_FIELD` in `buildGroupAndClause`;
  `parseDnf`/`splitTopLevel` are pure OQL parsing with no N1QL surface; field names reaching
  `pushdownOrGroups` are structurally constrained by the `CONDITION` regex. No new injection vectors.
  `SECURITY.md` re-reviewed through 1.4.0.
- [ ] Soak the new aggregate/group-by/distinct/OR-pushdown paths (query-heavy benchmark profile).
- [ ] Cross-version matrix re-validation (1.4.0 adds no new client-facing wire forms for aggregate/group-by;
  DISTINCT/paren-WHERE/OR-pushdown are server-internal).
- [ ] `CHANGELOG.md` `[1.4.0]`; cut `v1.4.0-rc1` → verify gates → cut `v1.4.0` GA + GitHub Release
  (GA tag operator-gated).

### Deferred (conditional)
JTA `TX_SYNCHRONIZATION` (op 90) — only if a JTA-coordinated client actually enters scope (no client to
validate against today).

### Housekeeping (not gated to a milestone)
- [x] **Dependency Graph — DONE.** The repo is public, so the dependency graph is on by default; the CI
  `dependency-submission` step now passes green on every run (CodeQL — the actual static gate — already
  passed). No repo-settings change was required.
- [x] **`docs/CONFIGURATION.md` env-var reference — DONE.** A single consolidated reference for every
  operator-facing environment variable (core/connection, Couchbase behavior + TLS, inbound TLS/mTLS,
  connection + frame guards, handler pool/backpressure, OQL, keyset sharding, durable HA, PDX
  persistence, eventing backplane, protocol/compat, OTel), each with its default and a pointer to the
  deeper doc. The category docs (`RUNBOOK.md` / `SECURITY.md` / `DEPLOYMENT.md`) keep the operational
  "why"; `CONFIGURATION.md` is the at-a-glance table.

---

## Road to 1.0 (completed — historical record)

## 1. Done

- **Observability** — Prometheus latency histograms; Grafana + Prometheus stack; provisioned
  dashboard; per-operation, byte-size, and error metrics.
- **Robustness** — frame-decoder hardening (DoS/OOM); deterministic backend-failure semantics;
  graceful Geode `EXCEPTION` responses (validated, default); blocking work off the Netty event
  loop; connection lifecycle guards (idle reaping, max-connections cap, slowloris first-request
  deadline); handler-queue backpressure; failure integration tests + CI `verify` gate.
- **Performance/scale** — benchmark + soak baselines; reusable `scripts/soak.sh`; PUT_ALL
  optimization (~2× faster, concurrent writes + batched keyset); connection-accounting fix.
- **Transport security** — inbound TLS; mutual TLS (client-cert auth); Couchbase backend TLS;
  health-port HTTPS + bind-address restriction.


---

## 2. Production-readiness gaps (current scope)

### 2a. Data correctness & high availability — highest priority

- [x] **Keyset-metadata concurrency.** `size`/`keySet` are backed by a single per-region metadata
  document. A first pass used compare-and-swap with bounded retries, but under heavy single-doc
  contention (120-way concurrent puts) lockstep retries could exhaust the budget and silently drop a
  key — an occasional `size 119/120`. **Fixed** by making the hot add path **contention-free**: a
  server-side sub-document `arrayAddUnique` (atomic, no read-modify-write CAS) for single-key adds, so
  concurrent adds can never lose an update. Removes (no sub-document by-value equivalent) keep the CAS
  path, now with jittered backoff so they don't retry in lockstep. Validated by
  `ProtoGemCouchKeysetConcurrencyIntegrationTest` (120 concurrent puts → exact `size`/`keySet`), run
  repeatedly with no flake.
- [x] **Multi-replica validation** — `ProtoGemCouchMultiReplicaIntegrationTest` runs two shim
  replicas (`protogemcouch-replica`) sharing one Couchbase, drives concurrent puts across both via a
  multi-server Geode pool, and asserts `size`/`keySet` reflect every key (cross-process CAS) while
  confirming both replicas served traffic. The shim is otherwise stateless, so it scales
  horizontally behind a load balancer.
- [x] **Durability/consistency options** — configurable Couchbase write durability via
  `CB_DURABILITY` (`none` default / `majority` / `majorityAndPersistToActive` / `persistToMajority`)
  applied to all value writes; and **`putAll` partial-failure semantics**: per-entry outcomes so
  successful writes persist and are counted even when others fail, with the failure surfaced to the
  client as a PUT_ALL server error naming the failed keys (validated by
  `ProtoGemCouchPutAllFailureIntegrationTest` + `CouchbaseDurabilityTest`). Remaining: structured
  per-key `VersionedObjectList` partial result (vs. a single error), and durability validation on a
  replicated cluster.
- [x] **TTL / expiration & eviction** — entry TTL via Couchbase document expiry on value writes
  (put/putAll/putIfAbsent/replace/invalidate), with: a default (`CB_TTL_SECONDS`), **per-region
  overrides** (`CB_TTL_REGIONS`), **idle-timeout** vs time-to-live semantics (`CB_TTL_MODE=idle`
  refreshes expiry on reads via get-and-touch), and **keyset eviction** (`size`/`keySet` verify
  existence and prune expired keys from the keyset metadata, keeping them correct post-expiry). All
  validated end-to-end (`ProtoGemCouchTtlIntegrationTest`, `ProtoGemCouchTtlIdleIntegrationTest`)
  plus `TtlConfig` unit tests.
- [x] **Large-value limits** — `CB_MAX_VALUE_BYTES` (default Couchbase's 20 MiB document ceiling)
  caps the encoded value-document size. An oversized value is rejected up front, before any backend
  write, with a clean `ServerOperationException`, so it never reaches Couchbase and never updates the
  region's keyset; in a `putAll` it is a per-key failure (under-limit entries still persist). Set `0`
  to disable. Validated by `ProtoGemCouchLargeValueIntegrationTest` (dedicated low-limit shim) +
  `MaxValueBytesConfigTest`. Enforced at the single `encodeStoredValue` chokepoint, so it covers
  put / putAll / putIfAbsent / replace / transactional commit.

### 2b. Deployment hardening

- [x] **Kubernetes** — Helm chart (`charts/protogemcouch`): multi-replica Deployment, Service,
  ConfigMap, Secret (or `existingSecret`), startup/liveness/readiness probes, resource
  requests/limits, HPA, PodDisruptionBudget, `terminationGracePeriodSeconds`, config-checksum
  rollout. Validated with `helm lint`/`helm template` **and a full in-cluster e2e** on the test
  cluster: image pulled from Docker Hub, 2-replica Helm deploy with file-mounted secrets, in-cluster
  Couchbase, and a real in-cluster Geode client round-trip (**42,606 ops, 0 errors**). The e2e
  surfaced and fixed a multi-release-jar packaging bug (the Geode client crashed from the fat jar on
  JDK 9+ until the shaded manifest was marked `Multi-Release: true`).
- [x] **Graceful shutdown** — a `SIGTERM` shutdown hook (the signal Kubernetes/`docker stop` send)
  runs an idempotent drain: stop accepting, drain in-flight request handlers and event loops within
  a bounded grace period, then close Couchbase and the health server. Pairs with
  `terminationGracePeriodSeconds`. Exercised by `docker compose stop` in the integration flow.
- [x] **Image hardening** — base image pinned by digest, runs as a fixed non-root UID
  (`USER 10001:10001`), JRE-only runtime, and CI attaches an SBOM + build provenance (SLSA) to the
  published image. CI also runs Trivy vulnerability scanning on every build (HIGH+CRITICAL uploaded
  to the Security tab; hard gate on fixable CRITICAL OS-package CVEs — jar CVEs from Geode's
  transitive deps are triaged, not gated) and signs every published image with keyless cosign
  (GitHub OIDC + Rekor). Remaining: periodic base-digest bumps and a documented `.trivyignore`.
- [x] **Resource sizing guidance** tied to capacity tests — initial guidance derived from the EC2
  rig: a 4-vCPU (`c6i.xlarge`) shim sustains ~16–17k read ops/sec at p99 < 5 ms before it is
  CPU-bound, so replica count ≈ `target_read_QPS / ~16k`; the shim tier is the constraint and a single
  `r6i.xlarge` Couchbase node had large headroom (~40k KV ops/s at ~20% CPU). Full numbers + caveats in
  `docs/SOAK_RESULTS.md`.

### 2c. Security (remaining)

- [x] **Secret management** — credentials read from file mounts via `CB_USERNAME_FILE` /
  `CB_PASSWORD_FILE` (Kubernetes Secret volumes / Docker secrets) instead of env vars; the Helm
  chart mounts a chart-managed or external (`existingSecret`) Secret as files, so secrets stay out
  of the process environment. Vault / external-secrets integrate via `existingSecret`.
- [x] **Vulnerability-scan enforcement** — CodeQL runs the broader `security-and-quality` suite;
  code-scanning + dependency alerts are release-gating via branch protection (alongside the existing
  in-workflow hard gate on fixable CRITICAL OS-package CVEs); a documented **triage SLA** (severity →
  triage/remediate timelines) and a tracked `.trivyignore` exception file (CVE + justification +
  expiry, re-reviewed on expiry) keep the signal clean. Jar/library CVEs from Geode's un-upgradeable
  transitive tree stay outside the hard gate by design (see `docs/SECURITY.md`).
- [x] **TLS policy** — the inbound TLS listener (and the health HTTPS endpoint) pin an explicit,
  auditable protocol/cipher policy instead of relying on JVM defaults: `TLS_PROTOCOLS` (default
  `TLSv1.3,TLSv1.2`; legacy SSLv3/TLS 1.0/1.1 excluded) and an optional `TLS_CIPHERS` allowlist.
  Validated by `ProtoGemCouchTlsPolicyIntegrationTest` (a TLS 1.2 client is rejected by a TLS-1.3-pinned
  instance; a TLS 1.3 client negotiates) + `TlsConfigTest`. **Certificate rotation** is documented and
  chart-supported: the Helm chart mounts the keystore/truststore from a Secret (`tls.existingSecret`)
  and rolls pods on change (`checksum/tls-secret`); rotation is a zero-downtime rolling restart
  (stateless + PDB), with the CA-rotation ordering for mTLS written up in `docs/SECURITY.md`.
- [x] **Audit logging** — security events (max-connections rejections, slowloris/first-request
  timeouts, malformed frames, TLS/mTLS handshake rejections) are emitted on a dedicated `protogemcouch.audit`
  logger at WARN with an `audit=true` marker, separate from operational logs and routable to its own
  sink. `AuditLog` + `AuditLogTest`; end-to-end `ProtoGemCouchAuditLogIntegrationTest`. See
  `docs/SECURITY.md`.

### 2d. Scale & capacity qualification

- [x] **Endurance soak** via `scripts/soak.sh` — sustained mixed-load runs (incl. a 1-hour run; a
  connection-accounting leak was caught and fixed by an earlier soak, see `docs/SOAK_RESULTS.md`). The
  script now renders an automated **stability verdict** (hard-gating errors, shedding, memory-leak, and
  connection-leak; throughput trend reported, gated only on dedicated infra via
  `SOAK_FAIL_ON_THROUGHPUT`) with a machine-readable `SOAK_VERDICT` line + exit code, so the soak can
  gate rather than be eyeballed.
- [x] **Multi-host capacity ceiling** — turnkey dedicated rigs built (`deploy/ec2` Terraform + NLB,
  `deploy/eks` Helm/eksctl: separate shim hosts, dedicated Couchbase, separate load generators, full
  obs stack) and the ceiling measured on the EC2 rig (`docs/SOAK_RESULTS.md`): per-shim read ceiling
  ~16.9k ops/sec (p99 4.3 ms), and a **near-linear scaling curve** — 2 shims behind the NLB driven from
  2 load gens reach **~35k ops/sec** aggregate (~2×, 0 errors). **Shim-CPU-bound with large Couchbase
  headroom** (one `r6i.xlarge` served ~40k KV ops/s at ~15% node CPU), so throughput scales by adding
  shim replicas. (Extending the curve to 4+ shims is just more of the same on the rig.)
- [x] **Failure injection at scale** — backend latency, packet loss, partial (frozen) outage, and a
  KV-port partition, all under sustained multi-host load. `deploy/ec2/scripts/fault-injection.sh`
  injects each fault with bounded self-healing windows; a hands-off self-driving mode
  (`chaos_experiment=true` + `chaos-autorun.sh`) runs the whole experiment on the rig and uploads
  results to S3 (no SSH). **Validated on the rig (2026-06-19, see `docs/SOAK_RESULTS.md`):** over an
  11.7M-op run the resilience contract held on every fault — latency/loss ridden out with **0 errors**
  (throughput throttles, p99 →958 ms, recovers), and pause/partition/hard-outage failed **bounded and
  clean** (no hang) then fully recovered with **no shim restart** (counters monotonic, both shims `up`
  throughout); total error rate 0.036%, 0 requests shed.

### 2e. Operability

- [x] **Alerting rules** — `prometheus/protogemcouch-alerts.rules.yml` (8 alerts: shim-down, backend
  errors, high error rate, p99-latency SLO, connections-rejected, requests-shed, malformed-frame spike,
  slowloris timeouts), loaded by the bundled Prometheus and validated with `promtool check rules` +
  unit tests (`protogemcouch-alerts_test.yml`). The observability stack now also ships an
  **Alertmanager** (Compose service, `alertmanager/alertmanager.yml`): Prometheus forwards fired alerts
  to it (`alerting:` block) and it routes them by severity with an inhibit rule for `ProtoGemCouchDown`;
  shipped receivers are integration-free sinks operators fill in. Validated with `amtool check-config`
  and a live Prometheus→Alertmanager discovery smoke. See `docs/OBSERVABILITY.md`.
- [x] **Distributed tracing** — OpenTelemetry, off by default (initialized only from `OTEL_*` env).
  A span per Geode operation (`geode.<OPERATION>`) with the Couchbase backend call nested under it
  (`couchbase.<op>`, via the `TracingRepository` decorator), exported over OTLP; errors recorded on the
  span. Validated end-to-end against Jaeger (operation + backend spans paired 1:1). Opt-in overlay
  `docker-compose.tracing.yml` (Jaeger backend + a provisioned Grafana Jaeger datasource, so traces sit
  beside metrics + logs in Grafana). Spans are shim-rooted (the Geode protocol carries no trace context).
- [x] **Log aggregation (Loki)** — the shim's structured logfmt logs (incl. the `protogemcouch.audit`
  stream) are shipped to **Loki** by **Promtail** (Docker-socket discovery) and queryable in **Grafana**
  via a provisioned Loki datasource; Promtail lifts `level`/`logger` to labels and stores the logfmt
  body so LogQL `| logfmt` parses the fields. Validated end-to-end (LogQL field filters return the
  expected operations). See `docs/OBSERVABILITY.md`.
- [x] **Runbook completeness** — `docs/RUNBOOK.md` now has incident response (severity tiers +
  first-response triage + rollback), a **per-alert playbook** for each of the 8 Alertmanager alerts
  (likely cause → diagnose via the metrics/logs/traces stack → remediate via the real env levers),
  common procedures (rolling restart, scale, cert rotation), and a support-handoff checklist (what to
  capture + doc references). Also corrected the stale `ERROR_RESPONSE_MODE` default (EXCEPTION frame is
  the validated default).

### 2f. Release management & supportability

- [x] **Versioned, tagged release builds** + **published Docker images** — `v*` tags build and publish
  a scanned, SBOM-attested, cosign-signed image (`docker-image.yml`); the release jar is attached by
  `release-candidate.yml`.
- [x] **CHANGELOG** + semantic versioning + a support/compatibility contract — `CHANGELOG.md` (Keep a
  Changelog + semver; `0.2.0` consolidates the parity + hardening work) and the
  `docs/COMPATABILITY_MATRIX.md` contract (supported Geode 1.15.x surface + explicit non-goals).
- [x] **Release gate** — a `v*` tag runs the full Docker-backed `mvn verify` integration suite
  (`release-candidate.yml`), the Trivy image scan + cosign signing (`docker-image.yml`), and an
  automated **perf-regression gate** (`perf-gate.yml` → `scripts/perf-gate.sh`): the concurrency
  benchmark runs against a real shim + Couchbase and fails the build on a gross throughput / tail-latency
  / error regression vs the conservative thresholds in `scripts/perf-baseline.env`. The perf gate also
  runs weekly and on demand (kept off per-PR to avoid shared-runner variance). `docs/RELEASE_CHECKLIST.md`
  ties it together. The CI gate keeps the conservative gross-guard thresholds (`scripts/perf-baseline.env`),
  and a **tight, rig-calibrated profile** (`scripts/perf-baseline.rig.env`, derived from the dedicated-rig
  characterization in `docs/SOAK_RESULTS.md`) enforces SLO-grade thresholds when the gate is run on
  controlled hardware (`PERF_BASELINE=… ./scripts/perf-gate.sh`).

### 2g. Testing/quality (broaden)

- [x] Decoder fuzz/negative tests (`GemFrameDecoderFuzzTest`): 25k random + 4k hostile-header inputs,
  every truncation prefix, byte-by-byte fragmentation, pipelined frames, and boundary cases all uphold
  the invariant — the decoder never throws/hangs/over-allocates and emits only self-consistent frames.
  Hardened the decoder while at it: parts are now parsed from a slice bounded by the declared
  `payloadLength`, so a hostile part length can no longer over-read past its frame into a pipelined one
  (stream desync); the decoder always consumes exactly the header + declared payload.
- [x] **Property/round-trip tests across all value types at scale** — a seeded generator
  (`RandomValueGraphs`) produces randomized `HashMap<String,Object>` graphs spanning the full
  structured supported matrix (scalars, wrappers, `Date`, `BigInteger`/`BigDecimal`/`UUID`, JDK enums,
  primitive arrays, `String[]`, generic `Object[]`, `ArrayList`, nested `Map`), recursively to bounded
  depth. Two harnesses assert equals-level fidelity: `CouchbaseRepositoryRoundTripPropertyTest` runs
  2,000 iterations through the persistence codec across a real JSON-text boundary
  (`encode → JsonObject.fromJson(toString()) → decode`), and `ProtoGemCouchRoundTripPropertyIntegrationTest`
  runs 90 graphs (put/get + putAll/getAll) through a real Geode 1.15 client + live shim + Couchbase,
  also covering the inbound DataSerializer decode and read-path wire re-encode. Each iteration is
  seeded and prints its seed on failure for exact reproduction.
- [x] Chaos tests (`ProtoGemCouchChaosIntegrationTest`): a real **Couchbase container stop/start under
  concurrent load** — in-flight writes fail promptly and cleanly (recorded as errors, no hang), the
  shim process stays up (metrics keep serving), and on recovery the keyset/size reflect **exactly** the
  acknowledged writes (a write that failed mid-outage leaves no phantom key); and a **shim container
  restart mid-flight** — the stateless shim loses no data (acknowledged entries remain readable) and
  the client reconnects and resumes writing. The class runs early and fully restores both shared
  containers, so the rest of the suite is unaffected.
- [x] **Coverage measurement + gate** — JaCoCo measures unit-test line coverage (`target/site/jacoco`)
  and `jacoco:check` (bound to the `test` phase, so `mvn test` and CI's `build-test.yml` both enforce
  it) fails the build below the floor in `pom.xml` (`jacoco.line.coverage.min`, currently 0.60 vs ~0.62
  measured — a ratchet). The gate scopes to the unit-testable surface: dev-only packages
  (`tools`/`probe`/`benchmark`/`samples`) and the live-backend `CouchbaseRepository` are excluded
  (the latter is integration-only — the Docker suite exercises it inside its container where JaCoCo
  on the test JVM can't see it — but it's kept in the report so the gap stays visible). CI publishes
  the report as an artifact. See `docs/TEST_STRATEGY.md`.

---

## 3. GemFire/Geode SDK parity (scope expansion)

> Today the shim is a **scoped compatibility profile** (core CRUD + bulk + key-metadata + broad
> value types + opaque PDX). Full GemFire-server parity is a much larger effort the launch criteria
> deliberately exclude from v1. These widen the supported client surface.

### 3a. Operations not yet supported

- [x] Atomic ops: `putIfAbsent`, `replace`, `replace(old,new)`, `remove(key,value)` (CAS-backed) —
  **complete and fully validated against a real Geode client.** Repository CAS layer
  (`putIfAbsent`/`replace`/`replace(old,new)`/`removeIfValue` with Couchbase insert-if-absent /
  CAS-guarded replace+remove + bounded retries); PUT operation decode (op ids 0x2c/0x2d + flags, with
  the expected-old-value part shifting key/value indices); DESTROY `remove(k,v)` decode (op 0x2e +
  expected-value part). **Storage is correct** (putIfAbsent doesn't overwrite, replace doesn't
  create, compare-ops act only on a match) and **return values are Geode-accurate**:
  `putIfAbsent`/`replace(k,v)` return the prior value via the old-value PUT reply (part[1] flags bit
  `0x01` + value object in part[2]); `replace(k,old,new)` returns the boolean (serialized Boolean
  object); `remove(k,v)` returns the boolean via the DESTROY entry-not-found reply (flag written to
  both the single-hop-on and single-hop-off part slots → client raises EntryNotFoundException →
  `false`). Proven end-to-end by `ProtoGemCouchAtomicOpsIntegrationTest` (7 tests) + repository
  contract unit tests.
- [x] `invalidate` / `getEntry` / `clear`. All three done & validated against a real Geode client.
  `invalidate` (op 83) keeps the key but drops the value (value-less marker, key retained in the
  keyset); `clear` (op 36) removes every entry and clears the region's keyset metadata
  (`ProtoGemCouchRegionOpsIntegrationTest`). **`getEntry` (op 89) done** — the byte shape was captured
  from a real Geode 1.15.1 server (`tools/GetEntryCapture`) and reproduced exactly. Key findings:
    - A non-transactional `getEntry` on a client PROXY region is served **locally** and never reaches
      the server (the capture showed zero wire traffic), so opcode 89 is only sent **inside a client
      transaction**. The handler therefore honors read-your-writes against the tx buffer.
    - The present-key reply is a serialized `EntrySnapshot` written as a plain `DataSerializable`:
      `DSCODE.DATA_SERIALIZABLE (0x2d)` + `DSCODE.CLASS (0x2b)` + the class-name string, then
      `EntrySnapshot.toData` — a leading `allowTombstones` boolean, `writeObject(key)`,
      `writeObject(value)`, `writeLong(lastModified=0)`, `writeBoolean(isRemoved=false)`, and a null
      versionTag (`DSCODE.NULL=0x29`); part[1] is an int 0. The absent-key reply is a null object part
      + int 8 (the client returns `null`). `GemResponseWriter.buildGetEntryResponse` /
      `buildGetEntryNotFoundResponse` reproduce both **byte-for-byte** vs. the real server
      (`GetEntryResponseShapeTest`), golden-wire-locked, and validated end-to-end in
      `ProtoGemCouchTransactionIntegrationTest` (present/absent/read-your-writes/buffered-remove).
    - Surfaced and fixed an adjacent gap: a single-hop client (the default) sends `TX_FAILOVER`
      (opcode 88) to nominate the tx host before the read; the shim's no-op partition metadata means
      it always does, so `TxFailoverHandler` now acks it (else the read retries forever and hangs).
- [~] Region lifecycle over the wire. **`destroyRegion` done** — `Region.destroyRegion()` (opcode 11,
  `DestroyRegionHandler`) removes the region's entries + keyset metadata and acks like `clear`; the
  schemaless shim re-materializes the region on the next write. Validated end-to-end against a real
  Geode 1.15 client (`ProtoGemCouchRegionLifecycleIntegrationTest`). **Remaining:** dynamic
  server-side region *creation with attributes* — a client `PROXY` region is created locally and sends
  no server create, so there is nothing to handle today; this only matters if/when custom server-side
  region attributes are in scope.
- [~] **Queries (OQL)** — `SELECT * FROM /region` **done & validated** against a real Geode client.
  Reverse-engineered the chunked query response by capturing real Geode-server bytes
  (`GeodeQueryCapture` tool): a `ChunkedMessage` (12-byte header + per-chunk framing) with a fixed
  `CollectionType` part and a result-list part. Implemented `GemResponseWriter.buildQueryResponse`
  (+ chunked framing, empty-result and error forms), an `OqlQuery` parser, and a `QueryHandler`
  (opcode 34) that gathers the region's values. **`WHERE` filtering now supported** too:
  `SELECT * FROM /region [alias] WHERE <field> <op> <literal> [AND ...]` (ops `= <> != < <= > >=`;
  string/number/boolean/null literals) combined with **`AND`/`OR`** (AND binds tighter), evaluated
  in-shim against map-typed values' top-level fields. **Projections** too — single-field
  (`SELECT e.status …`) and **multi-field struct** (`SELECT e.status, e.amount …` → Geode `Struct`
  rows, via a generated `StructType` + nested `Object[]` chunked response, byte-matched to the real
  server). **`ORDER BY`** too (`ORDER BY field [ASC|DESC]`, multi-key) — sorted in-shim and returned
  with Geode's order-preserving `Ordered` CollectionType + Object[] result (for SELECT */single-field).
  Validated by `ProtoGemCouchQueryIntegrationTest` (all-rows / empty / WHERE / OR / single+multi
  projection / ORDER BY asc+desc / unsupported) + `OqlQuery` parser/predicate/sort unit tests.
  **PDX field access** too: WHERE/projection/ORDER BY resolve fields of stored PDX instances — the
  shim keeps each `PdxType` by id and uses Geode's own `PdxReaderImpl` to read instance fields by
  name (validated by `PdxFieldAccessorTest` on captured bytes + a real-client PDX query test).
  **Parameterized queries** (`query.execute($1, $2, …)`, opcode 80) are supported: the shim decodes
  the bind values and `OqlQuery.bindParameters` substitutes `$N` as OQL literals before parsing
  (validated by `parameterizedQueryBindsValues`). **Result paging** is supported: a large SELECT * /
  single-field result streams as multiple chunks (each repeating part0=CollectionType + part1=batch,
  lastChunk only on the final chunk), matching the real server; the shim batches by row count
  (`QUERY_PAGE_SIZE`, default 100), validated by `largeResultSetIsStreamedAcrossChunksAndFullyAssembled`.
  **ORDER BY on struct projections** is supported (the StructType is wrapped in the `Ordered`
  CollectionType so the client preserves row order; validated by
  `structProjectionWithOrderByPreservesRowOrder`). **Result paging now covers ORDER BY and struct
  responses too** (each chunk repeats its CollectionType/StructType + that batch's Object[]; validated
  by `largeOrderedAndStructResultsArePagedAndAssembledInOrder`). OQL is **practically complete** for
  the shim. **Deferred (out of scope for now):** *joins* — cross-region joins are uncommon and
  discouraged in GemFire (poor performance), and a Couchbase-backed shim would have to load both
  whole regions into memory and cross-product them (fine only for tiny regions), so the ROI does not
  justify the multi-source-parser + alias-aware-resolver + nested-loop rewrite. **Not feasible
  server-side:** POJO (Java-serialized) field access (needs the domain classes — PDX is the queryable
  path).
- [x] **Transactions (bounded first cut)** — client `begin`/`commit`/`rollback`. Transactional ops
  carry the tx id in the message header; the shim buffers writes per `(connection, txId)` in a
  `TransactionRegistry`, serves reads-your-writes from the buffer, applies the buffer to storage on
  COMMIT (returning a zero-region `TXCommitMessage`, captured + round-trip-validated via
  `TxCommitProbe`), and discards it on ROLLBACK. Validated against a real Geode 1.15 client by
  `ProtoGemCouchTransactionIntegrationTest`. Read-your-writes covers `get`, `containsKey`, `getAll`,
  `size`, and `keySet` (all see the tx's own buffered writes/removes). **Commit is atomic** — all
  value docs + affected keyset metadata are applied in one Couchbase multi-document ACID transaction,
  so a failed op rolls the whole commit back (validated by `commitIsAtomicWhenAnOperationFails`). See
  `docs/TRANSACTIONS.md`. **In-tx compare semantics DONE:** transactional `putIfAbsent` / `replace` /
  `replace(k,old,new)` / `remove(k,v)` now honor their compare against the transaction's effective view
  (read-your-writes over committed state) and reply with the Geode-accurate old value / boolean — so
  `putIfAbsent` won't overwrite an existing key, `replace` won't create an absent one, and `remove(k,v)`
  respects the value, all inside a tx (validated by `ProtoGemCouchTransactionIntegrationTest`, 5 cases).
  The compare is evaluated at buffer time against the current snapshot (a bounded approximation of
  Geode's optimistic commit-time conflict check). **Documented bounded limitations** (not planned —
  rooted in the backend / a bounded tx model): per-region TTL is not applied to transactionally-committed
  writes (a Couchbase ACID transaction has no per-document expiry); JTA `TX_SYNCHRONIZATION` (opcode 90)
  is not handled (only sent by JTA-coordinated transactions); and tx failover is out of scope for the
  per-connection buffered tx model. See `docs/TRANSACTIONS.md`.
- [x] **Continuous Queries (CQ)** — registration + event delivery. **P1 DONE**: EXECUTECQ (42)
  compiles the OQL with the shim's own `OqlQuery` and registers it per client; matching PUT/REMOVE
  push CQ events (LOCAL_CREATE/UPDATE/DESTROY with the `[numCqElems, cqName, cqOp]` section) to the
  client's feed, so a real Geode 1.15 client's `CqListener` fires for create/update/destroy filtered
  by the predicate (`ProtoGemCouchCqIntegrationTest`); STOPCQ/CLOSECQ deregister. Built on the
  subscription feed + OQL engine; `geode-cq` is a test-only dependency (the shim needs no CQ engine).
  **P2 DONE:** stops-matching (an updated value that leaves the result set fires CQ DESTROY;
  `cqListenerFiresDestroyWhenUpdatedValueStopsMatching`), PDX-field CQ predicates
  (`cqListenerFiresForPredicateMatchingPdxObject`), and **executeWithInitialResults** — opcode 43
  returns the region's current matching entries as a chunked `Struct{key,value}` query-style response
  (`buildExecuteCqWithIrReply`; the client op extends `QueryOpImpl`), validated byte-for-byte
  (`CqWithIrResponseShapeTest`) and end-to-end (`executeWithInitialResultsReturnsCurrentMatchingEntries`).
  See `docs/CONTINUOUS_QUERIES.md`. **P3 DONE:** *multiple CQs per event* (each of a client's matching
  CQs fires for one mutation — `multipleCqsOnOneClientEachFireForAMatchingMutation`); *CQ statistics*
  (client-side `CqQuery.getStatistics()` counts the delivered events — `cqStatisticsCountReceivedEvents`);
  and *durable CQs* — a durable client's CQ is retained across disconnect (CLOSECQ on its keepalive close
  is ignored) and CQ events that match while it is away are queued and replayed on reconnect
  (`ProtoGemCouchDurableClientIntegrationTest.durableClientReplaysCqEventsMissedWhileDisconnected`),
  reusing the durable-client queue. **CQ practically complete** (single-instance + cross-replica
  backplane); remaining is CQ monitoring/stats *server-side* and durable-CQ multi-replica persistence
  (tracked with durable clients).
- [x] **Register interest / subscriptions / events** — client subscription queue and server→client
  notifications (a subsystem; prerequisite for CQ and listeners). **P1 + P2 DONE** (server→client push
  works end-to-end), validated against a real Geode 1.15 client by 8 gates in
  `ProtoGemCouchSubscriptionIntegrationTest`: the shim accepts the feed (mode 101) + control (107)
  connections; **register-interest** records per-client interest and `KEYS_VALUES` returns the region's
  initial image (GII via a `VersionedObjectList`); **events** push CLIENT_MARKER + LOCAL_CREATE /
  UPDATE / DESTROY / INVALIDATE (EventID + versionTag built via Geode's own classes) to interested
  feeds, so a `CacheListener` fires for create/update/destroy/invalidate by another client; with
  **create/update distinction**, **client-identity self-event suppression**, and **UNREGISTER**. See
  `docs/SUBSCRIPTIONS.md`; `tools/SubscriptionCapture` reproduces the captures. **Cross-replica
  eventing backplane — DONE (pluggable; opt-in):** a `NoOpEventBackplane` default (single-instance,
  zero dependency) plus a pluggable `EventBackplane` seam where each `publish*` broadcasts a
  `RemoteEvent` and `applyRemote` re-delivers events from other replicas (own echoes dropped by
  `originInstanceId`). Two transports ship: a **self-contained peer mesh** (`EVENT_BACKPLANE=mesh` —
  no broker; each replica broadcasts to peers discovered via a k8s headless Service or a static list;
  the zero-external-dependency end state) and an opt-in **Redis pub/sub** adapter over a hand-rolled
  RESP client (`EVENT_BACKPLANE=redis`; no Redis library in the build). Both sit behind the abstraction
  so the core stays broker-free. Validated by unit + no-infra mesh round-trip/fan-out + a fake-broker
  Redis round-trip, **and end-to-end on a real 2-replica Kubernetes deployment (`EVENT_BACKPLANE=mesh`,
  Helm + headless Service): a `CacheListener` on replica A fires for a mutation made on replica B
  (`CROSS_REPLICA_EVENT_CHECK PASS`), with a `backplane=none` negative control correctly FAILing**
  (`tools/CrossReplicaEventCheck` + `scripts/k8s-mesh-e2e.sh`).
  **Per-key interest filtering DONE:** REGISTER_INTEREST (20) / REGISTER_INTEREST_LIST (24) now parse
  the interest type + key/regex/list (`Interest` matcher: all-keys / specific key / key-list / regex),
  so a feed receives only events whose key matches — validated against a real Geode 1.15 client
  (`ProtoGemCouchInterestFilteringIntegrationTest`: specific-key, regex, and key-list each deliver only
  matching keys; ALL_KEYS still delivers everything). **Durable clients DONE (single-instance):** the
  handshake parses the durable id (`DurableHandshakeParser` — the last Geode string in the membership) +
  timeout; on a durable feed's disconnect the shim retains the client's interest and queues matching
  events, replaying them in order on reconnect + `readyForEvents()` (CLIENT_READY, opcode 53 →
  `ClientReadyHandler`), and drops the queue if the client doesn't return within its timeout. Validated
  against a real Geode 1.15 client (`ProtoGemCouchDurableClientIntegrationTest`: an event made while the
  durable client is disconnected is replayed on reconnect). Multi-replica durable persistence (the queue
  surviving replica death / reconnect-to-any-replica via Couchbase) is the documented follow-up.
  **Redundancy/MAKE_PRIMARY + PERIODIC_ACK/keepalive DONE:** captured a real redundancy-enabled,
  keepalive-pinging subscription client (`RedundancyKeepaliveProbe`) — it produces no unhandled
  opcodes: client PINGs (5) are acked, MAKE_PRIMARY is drained on the feed connection, and PERIODIC_ACK
  (52) is drained (`PeriodicAckHandler`); subscription redundancy is a graceful no-op for the
  single-logical-backend shim (like single-hop). The substantive keepalive fix: **subscription/durable
  feeds are now exempt from the idle-connection reaper** (`IdleConnectionHandler` + the `IS_FEED`
  marker), so a long-idle feed waiting for events is no longer silently reaped; dead feeds are detected
  at the TCP layer. **P3 complete** — this closes the register-interest/subscriptions/CQ subsystem
  (single-instance + cross-replica backplane); the remaining subscription scope-expansion is
  multi-replica *durable* persistence (Couchbase-backed), tracked above.
- [x] **Server-side functions — graceful rejection** (validated by
  `ProtoGemCouchFunctionIntegrationTest`). The shim has none of the user's `Function` classes, so it
  cannot *run* functions; it now rejects them the way a real server rejects an unregistered function
  id — `GET_FUNCTION_ATTRIBUTES` (the probe `FunctionService.onServer/onRegion.execute()` sends first)
  and the `EXECUTE_FUNCTION` / `EXECUTE_REGION_FUNCTION` (+ single-hop) opcodes all return a
  `REQUESTDATAERROR` carrying "The function is not registered for function id …", so the client raises
  a clean `ServerOperationException`/`FunctionException` instead of hanging or seeing the connection
  drop. See `docs/FUNCTIONS.md`; `tools/FunctionCapture` reproduces the capture. **Remaining:** actually
  *executing* functions is out of scope for a stateless shim (would require loading user code).
- [x] **Partitioned-region metadata / single-hop** — resolved as a deliberate, documented
  graceful no-op. Single-hop is a *multi-server* optimization (route a key straight to the server
  hosting its primary bucket); the shim is a single logical server over one Couchbase store with no
  bucket topology, so every op already reaches the one server in a single hop — there is no second hop
  to avoid. `GET_CLIENT_PARTITION_ATTRIBUTES` (opcode 73) now logs and returns without a reply
  (`GetClientPartitionAttributesHandler`); a real client treats that as "no single-hop available" and
  falls back to direct routing (already exercised, single-hop enabled, by the integration suite).
  Empirically a client against a single-server endpoint usually does not even send the probe. The shim
  does **not** fabricate a partition-attributes reply — with no real benefit to offer, a hand-rolled
  response would only risk mis-parsing on the client. (`PartitionAttributesCapture` is kept as a dev
  aid for re-checking the real-server protocol.)

### 3b. Value-type / serialization parity

- [x] **`DataSerializable` (custom) — opaque round-trip.** A custom `DataSerializable` value
  (DSCODE `0x2d`: `2d 2b 57 <class-name> <toData>`) is preserved verbatim as an `OPAQUE_GEODE_VALUE`
  and returned unchanged, so a real Geode client gets its object back via its own `fromData` — the shim
  never needs the class. Fixes a real bug: such values were previously mis-framed as a `byte[]` (the
  raw-byte-array catch-all) and came back to the client as `byte[]`. Validated end-to-end against a
  real client (`ProtoGemCouchDataSerializableIntegrationTest`, put/get + putAll/getAll with a
  client-only class) + `DataSerializableValueTest` (unit). **Not feasible:** field-level querying —
  `DataSerializable` carries no schema (unlike PDX), so the shim cannot read fields without the class.
  **Out of scope:** registered-`Instantiator` / `USER_CLASS` id forms (a different marker).
- [x] **PDX field querying** — OQL `WHERE` / projection / `ORDER BY` on PDX object fields (validated:
  `ProtoGemCouchQueryIntegrationTest.queryPdxByFieldAndProject`), **continuous-query predicates on PDX
  fields** (validated: `ProtoGemCouchCqIntegrationTest.cqListenerFiresForPredicateMatchingPdxObject`),
  and the **reverse PDX lookup** (GET_PDX_TYPE_BY_ID, opcode 92) so a second client can decode a PDX
  value it did not write (e.g. a CQ/subscription event value). One shared PDX-aware field resolver
  backs both the QUERY and CQ paths. See `docs/OQL.md` / `docs/CONTINUOUS_QUERIES.md`. **Remaining:**
  PDX fields whose type is OBJECT/array (only scalars are queryable).
- [x] **PDX schema evolution** — multiple versions of one logical PDX class (same class name,
  different field sets — field added / removed) coexist in a region as distinct types. The registry
  keys types by content fingerprint, so each version gets its own type id and is stored independently;
  field access resolves each field against the *instance's own* embedded type id, so a field absent
  from an older version is correctly treated as absent (not an error) and a newer field matches only
  the versions that declare it. Validated end-to-end against a real Geode 1.15 client by
  `ProtoGemCouchPdxSchemaEvolutionIntegrationTest`: three versions coexist, each round-trips with its
  own fields, an evolved field is queryable only on the versions that have it, and projecting it yields
  just those versions. (The mechanism already supported this; this closes the gap by validating it.)
- [x] **Full PDX registry discovery** — bulk type/enum registry sync. A client syncing its whole PDX
  registry pulls every type (GET_PDX_TYPES, opcode 101) and every enum (GET_PDX_ENUMS, 102) as a
  `Map<Integer, ...>`, plus the reverse enum lookup (GET_PDX_ENUM_BY_ID, 98 — the enum-side counterpart
  of GET_PDX_TYPE_BY_ID). The shim serves these from its `PdxTypeRegistry`/`PdxEnumRegistry` (the enum
  registry now keeps each client's serialized `EnumInfo` so it can re-serve it verbatim). The map reply
  is the Geode HASH_MAP form (`0x43` + count + `Integer` key + serialized `PdxType`/`EnumInfo` value),
  captured from a real Geode 1.15.1 server (`tools/GetPdxRegistryCapture`), golden-wire-locked, the
  framing byte-asserted by `PdxRegistryDiscoveryShapeTest`, and validated end-to-end against a real
  Geode 1.15 client (`ProtoGemCouchPdxRegistryDiscoveryIntegrationTest`: the client's own
  `GetPDXTypesOp`/`GetPDXEnumsOp`/`GetPDXEnumByIdOp` read back every registered type/enum). Completes
  the PDX registry surface alongside the per-type forward (`GET_PDX_ID_FOR_TYPE`) + reverse
  (`GET_PDX_TYPE_BY_ID`) lookups.
- [~] Nested complex types inside `HashMap<String,Object>`. **Done:** a generic `Object[]`, an
  `ArrayList`, a nested `Map<String,Object>` (all recursive, with supported elements), and the JDK
  scalar extras `UUID` / `BigInteger` / `BigDecimal` / `enum` nested inside a map now decode
  *structurally* (the map stays queryable on its top-level scalar fields and round-trips exactly,
  equals-level) instead of collapsing to an opaque blob. One shared `NestedValueSupport` predicate +
  deep copy backs the decode, wire re-encode, and `StoredValue` layers so they can't drift. Validated
  by `NestedComplexTypesTest` (unit) and the Docker-backed serialization/query integration tests
  (incl. a nested-bearing map matched by an OQL `WHERE` on its top-level field). **Deliberately still
  opaque** (round-trips exactly, just not queryable — the shim can't load/normalize them): nested
  Serializable POJOs, PDX instances, *typed* object arrays (`Integer[]`, `UUID[]`, …, kept type-exact),
  and non-`ArrayList` `List`s. Top-level forms of all types remain supported. (**Update — 1.2.0-M3:**
  nested `java.time` scalars `Instant`/`LocalDate`/`LocalDateTime` are now structured/queryable too.)
- [x] **Top-level value-type coverage breadth.** Validated end-to-end against a real Geode 1.15 client
  (`ProtoGemCouchTopLevelValueTypeIntegrationTest`) that the full value-type profile round-trips
  *exactly* as a top-level region value (not just nested in a Map/PDX): the JDK utility scalars
  (`UUID`/`BigInteger`/`BigDecimal`/JDK & custom `enum`/`Instant`/`LocalDate`/`LocalDateTime`) and
  *typed* object arrays (`Integer[]`/`Long[]`/`Double[]`/`UUID[]`/`BigInteger[]`/`Instant[]`/`enum[]`).
  **Fixed a real corruption bug surfaced here:** a top-level `LinkedList`/`HashSet`/`TreeMap` (and the
  other standalone JDK container DSCODEs — `HashTable`/`Vector`/`IdentityHashMap`/`LinkedHashSet`/
  `Stack`/`TreeSet`/`ConcurrentHashMap`, plus `TimeUnit`/`Timestamp`) used to fall through to the
  raw-byte-array catch-all and come back to the client as a `byte[]`. They are now preserved as opaque
  Geode values (exact bytes re-served), validated by a trial deserialization so a real `byte[]` value
  starting with one of those marker bytes is not mis-tagged (`ContainerOpaqueDecodeTest`).
- [ ] Arbitrary object graphs; full per-marker queryability (the opaque container/POJO forms above
  round-trip exactly but are not yet queryable on their contents).

### 3c. Protocol completeness

- [x] **Golden-wire regression library per opcode — reply AND request side.** Every handled opcode's
  reply frame is locked byte-for-byte to a committed hex fixture (`src/test/resources/golden-wire/`,
  `GoldenWireResponseTest`), and the matching **request** frame — a real Geode 1.15 client message
  captured by `tools.RequestWireCapture` — is locked under `src/test/resources/golden-wire-requests/`
  and decoded through the actual `GemFrameDecoder` by `GoldenWireRequestTest` (asserts the opcode + a
  self-consistent part list against the exact bytes a real client sends). Both sides have a coverage
  test tying the fixture set to `OpcodeRegistry`, so a newly handled opcode can't ship without locking
  its reply and request shapes (or an explicit exemption with a reason). The request library locks the
  core data-path / query / interest / transaction / region-lifecycle opcodes; subscription-CQ, PDX
  registry, and ack/control opcodes are documented exemptions (captured by their dedicated tools /
  covered by integration + the reply golden). Per-value-type GET encodings stay locked by the
  `*ShapeTest` suite.
- [x] **Protocol version negotiation** — the shim parses the client protocol version ordinal from the
  handshake and accepts only supported versions (default: the wire-validated 1.15.x line, ordinal 150;
  widenable via `SUPPORTED_VERSION_ORDINALS`). An unsupported version is refused cleanly with a Geode
  `REPLY_REFUSED` handshake (→ `ServerRefusedConnectionException`) instead of being served a session it
  can't decode; rejections are metered + audited. `HandshakeVersionPolicy` (parser + allowlist + refusal
  builder) is unit-tested against a real captured 1.15.x handshake, the refusal format is validated
  against a real Geode client (`HandshakeRefusalClientTest`), and `tools.HandshakeCapture` regenerates
  the capture. (Only 1.15.x is wire-validated here; accepting other ordinals is opt-in per environment.)
- [ ] Client notification/subscription channel (prerequisite for interest/CQ/listeners).

### 3d. Cache semantics

- [x] **Client-side cache callbacks** — a Geode client's `CacheLoader`, `CacheWriter`, and
  `CacheListener` run in the client JVM and compose correctly with the shim as backend: a `CacheLoader`
  fills a get-miss (the shim returns null, the client loader supplies the value), a `CacheWriter` veto
  (`CacheWriterException`) blocks the write from ever reaching the shim while an allowed write
  propagates, and `CacheListener` callbacks fire on the shim's server-pushed events (validated by
  `ProtoGemCouchCacheCallbacksIntegrationTest` + the subscription/CQ suites). No shim code is required
  for these — they are pure client-side behavior over normal get/put/destroy/event ops.
- **Bounded non-goals** (documented): **server-side** `CacheLoader`/`CacheWriter`/`CacheListener`
  (registered on a server region) would run user code on the server, which a stateless shim has no way
  to execute — same class as server-side functions. **Server-side expiration/eviction listener events**
  are also out of scope: the shim applies TTL via Couchbase document expiry (and leaves eviction to
  Couchbase) but does not synthesize per-entry expiration/eviction *events* to push to subscribers.
  Client-side region expiration/eviction (on a `CACHING_PROXY`) is local client behavior and works.

---

## Road-to-1.0 sequencing (completed)

The order the pre-1.0 work was delivered in (all done as of the 1.0.0 GA):

1. Keyset-metadata concurrency + multi-replica HA (correctness blocker for horizontal scale).
2. Kubernetes deployment + secret management + graceful-shutdown validation.
3. Multi-host capacity qualification + endurance soak.
4. Release pipeline + vuln-scan gating + alerting/tracing.
5. Parity expansion, prioritized by target-application need (typically atomic ops → OQL → functions
   → subscriptions/CQ).
