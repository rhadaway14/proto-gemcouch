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
  document; it was updated via a non-atomic read-modify-write that lost updates under concurrent
  writers (or multiple shim replicas). **Fixed** with compare-and-swap + bounded retries
  (insert-when-absent / replace-with-CAS, re-read on conflict). Validated by
  `ProtoGemCouchKeysetConcurrencyIntegrationTest` (120 concurrent puts → exact `size`/`keySet`).
- [x] **Multi-replica validation** — `ProtoGemCouchMultiReplicaIntegrationTest` runs two shim
  replicas (`protogemcouch-replica`) sharing one Couchbase, drives concurrent puts across both via a
  multi-server Geode pool, and asserts `size`/`keySet` reflect every key (cross-process CAS) while
  confirming both replicas served traffic. The shim is otherwise stateless, so it scales
  horizontally behind a load balancer.
- [ ] **Durability/consistency options** — configurable Couchbase durability; partial-failure
  behavior for `putAll`.
- [ ] **TTL / expiration & eviction** — map Geode entry expiry to Couchbase TTL.
- [ ] **Large-value limits** — enforce a max value size and define oversized-value behavior.

### 2b. Deployment hardening

- [ ] **Kubernetes** — Helm chart / manifests, liveness+readiness probes, resource requests/limits,
  HPA, PodDisruptionBudget, rolling-update / zero-downtime config.
- [ ] **Graceful shutdown** validated under load (drain in-flight, LB deregistration, signal
  handling).
- [ ] **Image hardening** — pin base-image digests, confirm non-root, minimize image, SBOM.
- [ ] **Resource sizing guidance** tied to capacity tests.

### 2c. Security (remaining)

- [ ] **Secret management** — K8s Secrets / Vault / cloud secret managers; stop relying on
  plaintext env vars for `CB_PASSWORD`.
- [ ] **Vulnerability-scan enforcement** — make CodeQL/dependency findings gating; triage SLA.
- [ ] **TLS policy** — pin TLS 1.2/1.3 and cipher suites; certificate rotation story.
- [ ] **Audit logging** — distinct stream for auth failures / rejected connections.

### 2d. Scale & capacity qualification

- [ ] **Multi-host capacity ceiling** — dedicated Couchbase + separate load generators (all current
  numbers are single-host/relative).
- [ ] **Endurance soak** (hours) via `scripts/soak.sh`.
- [ ] **Failure injection at scale** — backend latency, partial outages, partitions under load.

### 2e. Operability

- [ ] **Alerting rules** deployed (Alertmanager rules from the PromQL in `OBSERVABILITY.md`).
- [ ] **Log aggregation** (structured JSON → ELK/Loki) and **distributed tracing** (OpenTelemetry).
- [ ] **Runbook completeness** — incident playbooks; formal support handoff.

### 2f. Release management & supportability

- [ ] **Versioned, tagged release builds** + **published Docker images**.
- [ ] **CHANGELOG** + semantic versioning + a support/compatibility contract.
- [ ] **Release gate** — `verify` + security scan + perf-regression check before tagging.

### 2g. Testing/quality (broaden)

- [ ] Decoder fuzz/negative tests.
- [ ] Property/round-trip tests across all value types at scale.
- [ ] Chaos tests (Couchbase kill/restart, shim restart mid-op).
- [ ] Coverage measurement + gate.

---

## 3. GemFire/Geode SDK parity (scope expansion)

> Today the shim is a **scoped compatibility profile** (core CRUD + bulk + key-metadata + broad
> value types + opaque PDX). Full GemFire-server parity is a much larger effort the launch criteria
> deliberately exclude from v1. These widen the supported client surface.

### 3a. Operations not yet supported

- [ ] Atomic ops: `putIfAbsent`, `replace`, `replace(old,new)`, `remove(key,value)` (CAS-backed).
- [ ] `invalidate` / `getEntry` / `clear`.
- [ ] Region lifecycle over the wire (create/destroy region, attributes).
- [ ] **Queries (OQL)** — query execution (translate to N1QL or evaluate in-shim). Largest single
  feature gap.
- [ ] **Transactions** — client begin/commit/rollback.
- [ ] **Continuous Queries (CQ)** — registration + event delivery (needs the subscription channel).
- [ ] **Register interest / subscriptions / events** — client subscription queue and server→client
  notifications (a subsystem; prerequisite for CQ and listeners).
- [ ] **Server-side functions** — `onRegion`/`onServer`/`onMembers` execution.
- [ ] **Partitioned-region metadata / single-hop** — bucket routing
  (`GET_CLIENT_PARTITION_ATTRIBUTES` is only stubbed today).

### 3b. Value-type / serialization parity

- [ ] `DataSerializable` (custom).
- [ ] Full PDX registry discovery + schema evolution + PDX field querying (currently opaque
  round-trip only).
- [ ] Nested complex types inside `HashMap<String,Object>` (`Object[]`, POJOs, `ArrayList<Object>`,
  wrapper/utility arrays, PDX) — top-level works, nested does not.
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
