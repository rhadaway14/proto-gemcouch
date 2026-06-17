# ProtoGemCouch Roadmap

A living backlog for taking the shim from a **Level 3 scoped production candidate** to **Level 4
production-ready**, and (separately) for broadening GemFire/Geode SDK parity.

Legend: `[x]` done · `[~]` in progress · `[ ]` todo.

---

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
- [ ] **Resource sizing guidance** tied to capacity tests (methodology documented in
  `docs/SOAK_RESULTS.md`; the sizing numbers themselves need the dedicated-infra capacity runs).

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
- [ ] **Multi-host capacity ceiling** — dedicated Couchbase + separate load generators (all current
  numbers are single-host/relative). Methodology documented in `docs/SOAK_RESULTS.md`; needs a
  dedicated rig to produce absolute capacity/sizing numbers.
- [ ] **Failure injection at scale** — backend latency, partial outages, partitions under load (the
  chaos suite covers single-node backend outage + shim restart today).

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
  ties it together. **Remaining:** tighten the perf thresholds against a dedicated, representative
  environment (today they are gross-regression guards tuned to tolerate CI variance).

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
- [~] `invalidate` / `getEntry` / `clear`. **`invalidate` and `clear` done & validated** against a
  real Geode client (`ProtoGemCouchRegionOpsIntegrationTest`): `invalidate` (op 83) keeps the key but
  drops the value (value-less marker, key retained in the keyset); `clear` (op 36) removes every entry
  and clears the region's keyset metadata. **`getEntry` (op 89) is a follow-up** — the client casts
  the reply object to Geode's *internal* `EntrySnapshot` (a `DataSerializableFixedID`), so unlike the
  documented protocol replies it requires reproducing Geode's internal object wire form. Recipe
  reverse-engineered so far (for when it's picked up):
    - reply part[0] = `DataSerializer.writeObject(EntrySnapshot)` = DSFID framing
      (`DSCODE.DS_FIXED_ID_BYTE=1` or `DS_FIXED_ID_SHORT=2` + the `EntrySnapshot` fixed id — **still to
      confirm**) followed by `toData`;
    - `EntrySnapshot.toData` = `writeBoolean(flag)` then `NonLocalRegionEntry.toData` inline:
      `writeObject(key)`, `writeObject(value)`, `writeLong(lastModified)`, `writeBoolean(isRemoved)`,
      `writeObject(versionTag)`;
    - building blocks exist: `ValueEncoding.encodeGeodeStringValue` is `writeObject(String)`; a null
      versionTag is `DSCODE.NULL=41`.
    Remaining unknowns (need a docker validation pass): the `EntrySnapshot` fixed-id value, the
    leading boolean's meaning, and whether a null versionTag is accepted. Captured in the disabled
    `getEntryReturnsValueOrNull` test. Low ROI for a rarely-used op vs. the internal-serialization risk.
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
  `docs/TRANSACTIONS.md`. **Remaining:** per-region TTL is not applied to transactionally-committed
  writes (no per-op expiry in Couchbase transactions); transactional putIfAbsent/replace/remove(k,v)
  buffer as plain put/remove (no in-tx compare semantics); JTA `TX_SYNCHRONIZATION` (opcode 90) and
  tx failover are not handled.
- [~] **Continuous Queries (CQ)** — registration + event delivery. **P1 DONE**: EXECUTECQ (42)
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
  See `docs/CONTINUOUS_QUERIES.md`. **Remaining:** multiple CQs per event, CQ stats, durable CQs.
- [~] **Register interest / subscriptions / events** — client subscription queue and server→client
  notifications (a subsystem; prerequisite for CQ and listeners). **P1 + P2 DONE** (server→client push
  works end-to-end), validated against a real Geode 1.15 client by 8 gates in
  `ProtoGemCouchSubscriptionIntegrationTest`: the shim accepts the feed (mode 101) + control (107)
  connections; **register-interest** records per-client interest and `KEYS_VALUES` returns the region's
  initial image (GII via a `VersionedObjectList`); **events** push CLIENT_MARKER + LOCAL_CREATE /
  UPDATE / DESTROY / INVALIDATE (EventID + versionTag built via Geode's own classes) to interested
  feeds, so a `CacheListener` fires for create/update/destroy/invalidate by another client; with
  **create/update distinction**, **client-identity self-event suppression**, and **UNREGISTER**. See
  `docs/SUBSCRIPTIONS.md`; `tools/SubscriptionCapture` reproduces the captures. **Remaining (P3):**
  regex/key-list per-key event filtering (registers the whole region today), durable clients,
  redundancy/MAKE_PRIMARY, PERIODIC_ACK draining / keepalive, and a cross-replica eventing backplane
  (single shim instance only today).
- [x] **Server-side functions — graceful rejection** (validated by
  `ProtoGemCouchFunctionIntegrationTest`). The shim has none of the user's `Function` classes, so it
  cannot *run* functions; it now rejects them the way a real server rejects an unregistered function
  id — `GET_FUNCTION_ATTRIBUTES` (the probe `FunctionService.onServer/onRegion.execute()` sends first)
  and the `EXECUTE_FUNCTION` / `EXECUTE_REGION_FUNCTION` (+ single-hop) opcodes all return a
  `REQUESTDATAERROR` carrying "The function is not registered for function id …", so the client raises
  a clean `ServerOperationException`/`FunctionException` instead of hanging or seeing the connection
  drop. See `docs/FUNCTIONS.md`; `tools/FunctionCapture` reproduces the capture. **Remaining:** actually
  *executing* functions is out of scope for a stateless shim (would require loading user code).
- [ ] **Partitioned-region metadata / single-hop** — bucket routing
  (`GET_CLIENT_PARTITION_ATTRIBUTES` is only stubbed today).

### 3b. Value-type / serialization parity

- [ ] `DataSerializable` (custom).
- [x] **PDX field querying** — OQL `WHERE` / projection / `ORDER BY` on PDX object fields (validated:
  `ProtoGemCouchQueryIntegrationTest.queryPdxByFieldAndProject`), **continuous-query predicates on PDX
  fields** (validated: `ProtoGemCouchCqIntegrationTest.cqListenerFiresForPredicateMatchingPdxObject`),
  and the **reverse PDX lookup** (GET_PDX_TYPE_BY_ID, opcode 92) so a second client can decode a PDX
  value it did not write (e.g. a CQ/subscription event value). One shared PDX-aware field resolver
  backs both the QUERY and CQ paths. See `docs/OQL.md` / `docs/CONTINUOUS_QUERIES.md`. **Remaining:**
  PDX fields whose type is OBJECT/array (only scalars are queryable), and PDX schema evolution.
- [ ] Full PDX registry discovery + schema evolution.
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
  `java.time` values, and non-`ArrayList` `List`s. Top-level forms of all types remain supported.
- [ ] Arbitrary object graphs; complete DataSerializer marker coverage.

### 3c. Protocol completeness

- [ ] Full opcode coverage + a captured golden-wire regression library per opcode.
- [ ] Protocol version negotiation across Geode/GemFire client versions.
- [ ] Client notification/subscription channel (prerequisite for interest/CQ/listeners).

### 3d. Cache semantics

- [ ] CacheLoader / CacheWriter, expiration/eviction listeners, callback events.

---

## Suggested sequencing

1. Keyset-metadata concurrency + multi-replica HA (correctness blocker for horizontal scale).
2. Kubernetes deployment + secret management + graceful-shutdown validation.
3. Multi-host capacity qualification + endurance soak.
4. Release pipeline + vuln-scan gating + alerting/tracing.
5. Parity expansion, prioritized by target-application need (typically atomic ops → OQL → functions
   → subscriptions/CQ).
