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
- [ ] **Large-value limits** — enforce a max value size and define oversized-value behavior.

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
- [ ] **Resource sizing guidance** tied to capacity tests.

### 2c. Security (remaining)

- [x] **Secret management** — credentials read from file mounts via `CB_USERNAME_FILE` /
  `CB_PASSWORD_FILE` (Kubernetes Secret volumes / Docker secrets) instead of env vars; the Helm
  chart mounts a chart-managed or external (`existingSecret`) Secret as files, so secrets stay out
  of the process environment. Vault / external-secrets integrate via `existingSecret`.
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
- [ ] Region lifecycle over the wire (create/destroy region, attributes).
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
  **Remaining:** ORDER BY on struct projections, joins, POJO (Java-serialized) field access (needs
  the domain classes — not feasible server-side; PDX is the queryable path), parameterized queries
  (opcode 80), result paging.
- [x] **Transactions (bounded first cut)** — client `begin`/`commit`/`rollback`. Transactional ops
  carry the tx id in the message header; the shim buffers writes per `(connection, txId)` in a
  `TransactionRegistry`, serves reads-your-writes from the buffer, applies the buffer to storage on
  COMMIT (returning a zero-region `TXCommitMessage`, captured + round-trip-validated via
  `TxCommitProbe`), and discards it on ROLLBACK. Validated against a real Geode 1.15 client by
  `ProtoGemCouchTransactionIntegrationTest` (commit persists, rollback discards, read-your-writes,
  buffered remove on commit). See `docs/TRANSACTIONS.md`. **Remaining:** commit is best-effort
  sequential (not cross-key atomic — Couchbase multi-doc ACID would close this); transactional
  putIfAbsent/replace/remove(k,v) buffer as plain put/remove (no in-tx compare semantics);
  `containsKey`/`getAll`/`size`/`keySet` inside a tx read committed state (no read-your-writes);
  JTA `TX_SYNCHRONIZATION` (opcode 90) and tx failover are not handled.
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
