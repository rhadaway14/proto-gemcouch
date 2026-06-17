# Changelog

All notable changes to ProtoGemCouch are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/). Pre-1.0 releases may still change behavior between
minor versions as parity expands.

## [Unreleased]

Observability/operability, SDK-parity, testing, and multi-host capacity work since 0.2.0. All parity
and round-trip changes are validated against a real Geode 1.15 client.

### Added
- **Distributed tracing** — OpenTelemetry, off by default (enabled via `OTEL_*`); a span per Geode
  operation with the Couchbase backend call nested under it, exported over OTLP. Opt-in
  `docker-compose.tracing.yml` overlay (Jaeger) + a provisioned Grafana Jaeger datasource.
- **Log aggregation** — structured logfmt logs (incl. the `protogemcouch.audit` stream) shipped to
  **Loki** by **Promtail** and queryable in Grafana.
- **Alerting** — Prometheus alerting rules (shim-down, backend errors, error rate, p99 SLO,
  connections-rejected, requests-shed, malformed-frame spike, slowloris) + an **Alertmanager** service
  with severity routing and an inhibit rule.
- **Grafana dashboards** — a **Logs & Traces** dashboard (Loki + Jaeger), a **Host Metrics** dashboard
  (node_exporter: CPU / memory / load / network / disk / filesystem), and a **Couchbase** dashboard
  (KV ops, command latency, memory, items, connections, disk-write queue, errors/OOM/cache-miss).
- **Host + backend metrics scraping** — node_exporter on every host and Couchbase's built-in Prometheus
  endpoint added as Prometheus targets.
- **Incident runbook** — `docs/RUNBOOK.md` incident response (severity tiers, triage, rollback) + a
  per-alert playbook for each alert and a support-handoff checklist.
- **Automated perf-regression gate** — the concurrency benchmark runs against a real shim + Couchbase
  on `v*` tags / weekly / on demand and fails on gross throughput / tail-latency / error regression
  (`scripts/perf-gate.sh`, `.github/workflows/perf-gate.yml`).
- **Automated soak stability verdict** — `scripts/soak.sh` renders a pass/fail `SOAK_VERDICT`
  (hard-gating request errors, shedding, memory growth, and connection growth; throughput trend
  reported) with a machine-readable line + exit code.
- **Code-coverage gate** — JaCoCo line-coverage measurement and a build gate (`jacoco:check`) that
  fails below the configured floor; report uploaded in CI.
- **Property-based round-trip tests** — a seeded generator (`RandomValueGraphs`) feeds an in-process
  persistence-codec harness (2,000 iterations across a real JSON-text boundary) and a real-client
  integration harness (put/get + putAll/getAll), asserting equals-level serialization fidelity; the
  seed is printed on failure for exact reproduction.
- **Nested complex types** inside `HashMap<String,Object>` values — a generic `Object[]`, `ArrayList`,
  nested `Map<String,Object>`, and the JDK scalar extras `UUID` / `BigInteger` / `BigDecimal` / `enum`
  (recursively) now decode **structurally** — the map stays queryable on its top-level fields and
  round-trips exactly — instead of collapsing to opaque bytes. (Nested customer POJOs / PDX / typed
  arrays / `java.time` still round-trip opaquely by design.)
- **`Region.destroyRegion()`** (DESTROY_REGION, opcode 11) — removes the region's entries + keyset
  metadata; the schemaless shim re-materializes the region on the next write.
- **Custom `DataSerializable` values** — round-trip opaquely (DSCODE `0x2d` preserved verbatim), so a
  client gets its object back via its own `fromData`; the shim never needs the class.
- **PDX schema evolution** — multiple versions of one PDX class (fields added/removed) coexist as
  distinct types; each instance round-trips with its own fields and OQL resolves fields per version.
- **Decoder fuzz tests + hardening** — random + hostile-header inputs; parts are now parsed from a
  slice bounded by the declared payload length, so a hostile part length can't over-read into a
  pipelined frame.
- **Multi-host capacity rigs** — `deploy/ec2` (Terraform: separate shim hosts behind an internal NLB,
  dedicated Couchbase, separate load generators, full obs stack) and `deploy/eks` (eksctl + the
  production Helm chart + kube-prometheus-stack), plus `capacity-sweep.sh` (stepped-load knee finder).
  First capacity characterization recorded in `docs/SOAK_RESULTS.md`: per-shim read ceiling ~16.9k
  ops/sec, **near-linear two-shim scaling** (~35k aggregate), shim-CPU-bound with large Couchbase
  headroom; initial resource-sizing guidance derived.

### Changed
- `GET_CLIENT_PARTITION_ATTRIBUTES` (the single-hop probe) is now a **documented graceful no-op** —
  single-hop is not applicable to a single-backend shim, so the client falls back to direct routing.
- Refreshed `docs/CURRENT_LIMITATIONS.md` (now defers the detailed surface to the compatibility matrix
  + roadmap so it can't drift) and pointed `docs/NEXT_STEPS.md` at `ROADMAP.md`.

## [0.2.0] - 2026-06-15

A large parity + production-hardening release. Everything listed under 0.1.0's "Known limitations"
for transactions, continuous queries / subscriptions, server-side functions, `getEntry`, parameterized
queries, struct `ORDER BY`, result paging, configurable durability, and `putAll` partial failures is
now implemented and validated against a real Geode 1.15 client. Published as a scanned, SBOM-attested,
cosign-signed image at `docker.io/rhadaway14/protogemcouch:0.2.0` (and `latest`).

### Added
- **Transactions:** client transactions (`begin → put/get/remove → commit`/`rollback`); commit returns
  a real `TXCommitMessage`, rollback discards buffered writes.
- **Register-interest / subscriptions:** the server→client event feed (mode-101 feed + mode-107
  control). A real `CacheListener` fires for create / update / destroy / invalidate by another client,
  with `KEYS_VALUES` initial image (GII), per-client interest, create/update distinction, self-event
  suppression, and `UNREGISTER`.
- **Continuous Queries:** register a CQ + `CqListener`; matching mutations push CQ
  create/update/destroy events (incl. "stops-matching" → DESTROY); **CQ predicates over PDX object
  fields**; `executeWithInitialResults` returns the current matching set; `STOPCQ`/`CLOSECQ`;
  `PERIODIC_ACK` draining.
- **Server-side functions:** graceful rejection — `GET_FUNCTION_ATTRIBUTES` /
  `EXECUTE_FUNCTION` / `EXECUTE_REGION_FUNCTION` return a clean `ServerOperationException` (the shim
  cannot run user function code) instead of hanging or dropping the connection.
- **OQL:** parameterized queries (`$1..$N`, opcode 80); `ORDER BY` on struct/multi-field projections;
  result paging (chunked, large result sets); `getEntry` (opcode 89).
- **PDX:** reverse type lookup (`GET_PDX_TYPE_BY_ID`, opcode 92) so a second client can decode a PDX
  value it did not write (e.g. a pushed CQ/subscription event value).
- **Large-value limits:** `CB_MAX_VALUE_BYTES` (default Couchbase's 20 MiB ceiling) rejects oversized
  values up front with a clean error, before any backend write, leaving size/keyset untouched.
- **Durability & partial failures:** configurable Couchbase write durability (`CB_DURABILITY`);
  `putAll` is partial-failure aware (successful entries persist and are counted; failures are named).
- **Resilience tests:** chaos suite — a real Couchbase container stop/start under concurrent load
  (clean prompt failures, automatic recovery, exact keyset/size) and a stateless shim restart.

### Changed
- **Geode client pinned to 1.15.1** (matched to the capture server; the chart/compose image is pinned
  in lockstep). Validated on 1.15.1.
- **Keyset add path is now contention-free:** single-key adds use an atomic server-side sub-document
  `arrayAddUnique` instead of a CAS read-modify-write; removes keep CAS with jittered backoff.

### Fixed
- **Keyset lost update under high concurrency:** the CAS add path could exhaust its retry budget under
  heavy single-document contention and silently drop a key (`size` 119/120). The contention-free
  sub-document add eliminates it (validated by repeated 120-way concurrent-put runs).

### Security
- **TLS protocol/cipher policy:** the Geode listener and health HTTPS endpoint pin an explicit,
  auditable allowlist — `TLS_PROTOCOLS` (default `TLSv1.3,TLSv1.2`; legacy SSLv3/TLS 1.0/1.1 excluded)
  and optional `TLS_CIPHERS` — instead of JVM defaults.
- **Audit logging:** security events (connection rejections, slowloris timeouts, malformed frames,
  TLS/mTLS handshake rejections) go to a dedicated `protogemcouch.audit` stream with an `audit=true`
  marker, routable to its own sink.
- **Vulnerability-scan enforcement:** CodeQL `security-and-quality` suite; documented triage SLA;
  `.trivyignore` exception file (CVE + justification + expiry); two-layer gating model.
- **Certificate rotation:** the Helm chart mounts the inbound-TLS keystore/truststore from a Secret and
  rolls pods on change; zero-downtime rolling-restart rotation + mTLS CA-rotation ordering documented.

[0.2.0]: https://github.com/rhadaway14/proto-gemcouch/releases/tag/v0.2.0

## [0.1.0] - 2026-06-05

First tagged release of the Apache Geode / GemFire → Couchbase protocol shim: a Netty server that
speaks the Geode client/server wire protocol and translates operations onto a Couchbase backend.
Published as a scanned, SBOM-attested, cosign-signed image at
`docker.io/rhadaway14/protogemcouch:0.1.0` (and `latest`).

### Supported Geode client operations
- **Core entry ops:** `get`, `put`, `remove`, `containsKey`, `containsValueForKey`.
- **Bulk ops:** `getAll`, `putAll` (concurrent value writes + batched keyset metadata).
- **Region metadata:** `size`, `keySet` (cross-process CAS-safe keyset metadata).
- **Atomic ops (CAS-backed):** `putIfAbsent`, `replace(k,v)`, `replace(k,old,new)`, `remove(k,v)` —
  Geode-accurate storage and return values.
- **Region ops:** `invalidate` (keep key, drop value), `clear`.
- **OQL queries:** `SELECT (* | field | field,…) FROM /region [alias] [WHERE …] [ORDER BY …]`:
  single-field and multi-field (struct) projections; `WHERE` with `AND`/`OR` and
  `= <> != < <= > >=` over string/number/boolean/null literals; `ORDER BY field [ASC|DESC]`;
  field access on map values **and PDX object fields** (via Geode's own PDX reader). Unsupported
  query shapes return a clean server error.
- **Value types:** strings, all primitive wrappers, `Date`, byte/primitive/object arrays,
  `ArrayList`/`HashMap` (String and String→Object), Java-serialized objects, and opaque PDX
  round-trip — validated against a real Geode client.

### Operability & deployment
- **Observability:** Prometheus latency histograms + per-operation/byte/error/connection metrics;
  provisioned Grafana + Prometheus stack; `/live`, `/ready`, `/metrics` health/admin endpoints.
- **Robustness:** frame-decoder DoS/OOM hardening; deterministic backend-failure semantics; graceful
  Geode `EXCEPTION` responses; blocking work off the Netty event loop; connection guards (idle
  reaping, max-connections, slowloris first-request deadline); handler-queue backpressure.
- **Transport security:** inbound TLS, mutual TLS, Couchbase backend TLS, HTTPS health port.
- **Entry TTL:** `CB_TTL_SECONDS` default + `CB_TTL_REGIONS` per-region overrides + `CB_TTL_MODE`
  (time-to-live vs idle/get-and-touch), with keyset eviction of expired keys.
- **Kubernetes:** Helm chart (multi-replica Deployment, Service, ConfigMap/Secret, probes, HPA, PDB,
  graceful `SIGTERM` drain); file-mounted secrets. Validated by a full in-cluster e2e.
- **Supply chain:** digest-pinned non-root image; CI builds the jar, scans the image (Trivy), and on
  the default branch / `v*` tags publishes to Docker Hub with an SBOM + SLSA provenance and a keyless
  cosign signature.

### Known limitations
- Geode operations not yet supported: `getEntry` (needs `EntrySnapshot` serialization), region
  lifecycle over the wire, transactions, continuous queries / subscriptions, server-side functions,
  single-hop partition metadata.
- OQL: no `ORDER BY` on struct projections, joins, parameterized queries (opcode 80), or result
  paging; POJO (Java-serialized) field access is not possible server-side (use PDX).
- Durability is Couchbase-default (no configurable `persistTo`/`replicateTo` yet); `putAll` does not
  yet surface partial failures.

[0.1.0]: https://github.com/rhadaway14/proto-gemcouch/releases/tag/v0.1.0
