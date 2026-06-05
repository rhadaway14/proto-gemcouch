# Changelog

All notable changes to ProtoGemCouch are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/). Pre-1.0 releases may still change behavior between
minor versions as parity expands.

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
