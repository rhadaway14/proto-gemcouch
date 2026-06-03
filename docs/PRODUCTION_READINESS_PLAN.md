# ProtoGemCouch PRODUCTION READINESS PLAN

## Summary

`ProtoGemCouch` has achieved a successful end-to-end compatibility milestone for core operations and a broad supported type profile.

The current baseline includes:

- real Geode client integration
- Docker-backed Couchbase persistence
- typed value envelopes
- primitive wrappers
- primitive arrays
- selected collections and maps
- Serializable POJO preservation
- Object[] preservation
- ArrayList<Object> preservation
- GET_ALL / PUT_ALL validation

The next goal is to move from a working prototype and validated compatibility profile to a clearly scoped, production-ready compatibility profile with repeatable validation, operational hardening, and a supportable deployment model.

---

## Current Verified Baseline

Latest Docker-backed integration result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchSerializationIntegrationTest
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 88, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

The current baseline already includes:

- successful Geode client handshake with the shim
- successful create/read/update/delete flow
- successful backend persistence in Couchbase
- native typed reads for supported values
- deterministic request decoding for validated value families
- working destroy/remove reply handling for the validated sample flow
- native `containsKeyOnServer(...)` protocol path
- native server-side contains-value-for-key protocol path
- clean missing-document semantics for supported contains paths
- PUT_ALL and GET_ALL validation for supported types
- primitive array family support
- Object[] support
- ArrayList<Object> support
- Serializable POJO support
- structured logging
- metrics summaries
- startup validation
- Docker-based deployment path
- initial benchmark / soak / operational documentation

---

## Production Readiness Goals

Production readiness for `ProtoGemCouch` means:

1. a clearly defined supported compatibility profile
2. repeatable real-client validation for all supported operations
3. no required sample-app workarounds for supported behaviors
4. defined behavior under failure conditions
5. strong observability and operational support
6. hardened deployment and security posture
7. performance characterized under realistic load
8. release criteria that can be enforced before shipping

---

## Recommended v1 Support Boundary

- Geode client handshake
- string keys
- core region operations
- put
- get
- putAll
- getAll
- remove
- contains key / existence semantics
- contains value-for-key semantics
- sizeOnServer
- keySetOnServer
- missing-document semantics
- supported scalar and array values
- supported simple collections/maps
- opaque preservation for selected complex object payloads
- defined behavior for unsupported features

## Supported v1 Value Profile

```text
String
Boolean
Character
Byte
Short
Integer
Long
Float
Double
java.util.Date
byte[]
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
```

## Not Assumed for Initial Production Scope

- full Geode server parity
- PDX parity
- DataSerializable parity
- all query semantics
- all transaction semantics
- listener/callback/event parity
- advanced distributed-region semantics
- arbitrary object graph structural introspection

---

# Phase 1 - Compatibility Completion for v1 Scope

## Current status

Substantially complete for the current supported operation and type profile.

Validated:

- PUT
- GET
- PUT_ALL
- GET_ALL
- REMOVE
- CONTAINS mode `0`
- CONTAINS mode `1`
- SIZE
- KEY_SET

## Remaining tasks

- Broaden response-shape regression coverage.
- Validate unsupported operation behavior.
- Validate malformed request behavior.
- Decide whether unsupported paths return explicit error frames or close the connection.

---

# Phase 2 - Serialization and Type Strategy

## Current status

Strong baseline established.

Supported:

- primitive wrappers
- primitive arrays
- Date
- byte[]
- String[]
- ArrayList<String>
- HashMap<String,String>
- HashMap<String,Object>
- Serializable POJO opaque preservation
- Object[] opaque preservation
- ArrayList<Object> opaque preservation

## Remaining tasks

Validate and decide support strategy for:

- Integer[]
- Long[]
- Boolean[]
- Double[]
- UUID
- BigDecimal
- BigInteger
- Enum
- java.time.Instant
- java.time.LocalDate
- java.time.LocalDateTime
- DataSerializable
- PDX / PdxInstance
- custom serializers
- arbitrary nested object graphs

---

# Phase 3 - Regression and Integration Testing

Tasks:

- Turn real protocol captures into regression fixtures.
- Maintain repeatable integration tests using real Geode client, shim, Couchbase, and containers.
- Add negative tests for malformed frames, unsupported operations, serialization failures, Couchbase misses, and backend failures.

---

# Phase 4 - Failure Handling and Robustness

## Status

In progress.

Done:

- Inbound frame hardening: payload/part-count/part-length bounds checks reject malformed or
  oversized frames (preventing OOM/DoS), with a `protogemcouch_malformed_frames_total` metric.
- Deterministic backend failure semantics: infrastructure failures are surfaced as
  `RepositoryException` and recorded as operation errors instead of being masked as empty
  results. See `docs/RUNBOOK.md` "Backend (Couchbase) failure behavior".

- Graceful error-response seam: post-decode operation failures flow through a single
  `ErrorResponsePolicy`. The default `close` policy preserves existing behavior; an opt-in
  `exception` policy (env `ERROR_RESPONSE_MODE=exception`) replies with a Geode EXCEPTION frame
  and keeps the connection open. The EXCEPTION wire shape is built and structurally tested but
  not yet the default, pending live-client validation in the integration suite.

- Event-loop isolation: request handlers run on a dedicated executor group (`HANDLER_THREADS`,
  default 64) instead of the Netty I/O event loop, so a slow Couchbase call no longer stalls
  other connections on the same loop. Couchbase KV and connect timeouts are now configurable
  (`CB_KV_TIMEOUT_MS`, `CB_CONNECT_TIMEOUT_MS`) to bound per-operation latency explicitly.

- Connection lifecycle guards: idle connections are reaped via an IdleStateHandler
  (`CONNECTION_IDLE_TIMEOUT_SECONDS`, default 300) and concurrent connections can be capped
  (`MAX_CONNECTIONS`, default unlimited), with `protogemcouch_idle_connections_closed_total` and
  `protogemcouch_connections_rejected_total` metrics. In-flight work is drained on shutdown via
  graceful shutdown of the handler executor group and event-loop groups.
- Failure-mode integration tests + CI gate: `ProtoGemCouchFailureIntegrationTest` validates, against
  a real Geode client and the Dockerized backend, that an oversized frame is rejected and the
  connection closed, that an abrupt client disconnect does not affect server health, and that a
  Couchbase outage surfaces as a recorded operation error (not a silent empty read) and recovers.
  A CI workflow (`.github/workflows/integration.yml`) runs `mvn verify` on main and pull requests,
  so the compatibility and failure suites are enforced before merge.
- Graceful errors validated and made default: the EXCEPTION frame is validated against a live Geode
  client (the client raises a ServerOperationException and keeps the connection open), so
  `ERROR_RESPONSE_MODE` now defaults to `exception` (set `close` to opt out). This also corrected
  the EXCEPTION message-type constant (2, not 22).

- Slowloris guard and handler-queue backpressure: a first-request deadline
  (`FIRST_REQUEST_TIMEOUT_SECONDS`, default 10) closes connections that never complete a request
  even while trickling bytes, and the handler executor uses a bounded queue
  (`HANDLER_MAX_PENDING_TASKS`, default 10000) that sheds requests under sustained overload rather
  than growing an unbounded backlog. Both are observable
  (`protogemcouch_connections_first_request_timeout_total`, `protogemcouch_requests_shed_total`).

Phase 4 robustness is complete. Remaining production-readiness work is in other phases (notably
performance/soak qualification, Phase 7).

Tasks:

- Define backend failure behavior.
- Define client/session failure behavior.
- Validate reconnect behavior.
- Ensure each failure class has structured logs and metrics.

---

# Phase 5 - Observability and Operations Hardening

Tasks:

- Expand per-operation metrics.
- Add decode strategy and opaque preservation counters.
- Validate health and readiness semantics.
- Create dashboards and alerts.
- Update runbooks.

---

# Phase 6 - Security and Deployment Hardening

## Status

Transport security implemented and validated against a real Geode client and a TLS-served Couchbase:

- Inbound Geode listener TLS (`TLS_ENABLED`), validated by `ProtoGemCouchTlsIntegrationTest`.
- Mutual TLS / client-certificate auth (`TLS_CLIENT_AUTH=require`), validated by
  `ProtoGemCouchMutualTlsIntegrationTest` (accept) and `...RejectionIntegrationTest` (reject).
- Couchbase (backend) TLS (`CB_TLS_ENABLED` / `couchbases://`), validated by
  `ProtoGemCouchBackendTlsIntegrationTest`.

Remaining: secret-manager integration, image digest pinning / vulnerability-scan enforcement,
Kubernetes deployment guidance, and health-port hardening.

Tasks:

- Secure secret handling.
- Decide TLS requirements.
- Harden images.
- Document Docker and Kubernetes deployment guidance.
- Define graceful shutdown and resource sizing.

---

# Phase 7 - Performance and Scale Qualification

## Status

In progress.

Done:

- Benchmark baseline re-run against the hardened build (2026-06-02): read-heavy ~12.3k ops/s,
  mixed ~7k, write-heavy ~6k, bulk-heavy ~4.2k at concurrency 16, 0 errors. PUT_ALL is the
  latency-dominant operation (~6 ms avg, p99 ~10 ms). See `PERFORMANCE_RESULTS.md`.
- putAll/getAll latency characterized (bulk-heavy and mixed profiles).
- Soak test run (3-minute mixed stability soak via the new `scripts/soak.sh`, which samples
  metrics and memory over time): stable throughput/latency, flat memory, no connection leak,
  no errors or guard trips. See `SOAK_RESULTS.md`.

Remaining:

- Multi-host / dedicated-Couchbase capacity ceiling (all runs so far are single-host, so they
  characterize relative behavior and stability, not a hardware capacity limit).
- Longer-duration endurance soak (hours) and primitive-array payload micro-profiling.

Tasks:

- Re-run benchmarks on the current compatibility baseline.
- Include putAll/getAll and primitive-array payload latency.
- Run soak tests.
- Document capacity envelope and bottlenecks.

---

# Phase 8 - Release Management and Supportability

Tasks:

- Publish support contract.
- Define release gating.
- Add changelog discipline.
- Finalize troubleshooting and issue triage docs.

---

## Completed Current-Baseline Tasks

1. Make GET return native string values for the supported path.
2. Make native contains key semantics work for the supported path.
3. Make native contains value-for-key semantics work for the supported path.
4. Clean up expected Couchbase missing-document logging for supported contains paths.
5. Stabilize deterministic string PUT decoding for the supported path.
6. Validate PUT_ALL and GET_ALL with real Geode client integration tests.
7. Add Serializable POJO opaque preservation.
8. Add Object[] opaque preservation.
9. Add ArrayList<Object> opaque preservation.
10. Add int[] structural support.
11. Add full primitive-array family structural support.

## Immediate Next Tasks

1. Add wrapper-array and common Java utility type support.
2. Convert more real protocol captures into golden-wire regression tests.
3. Expand negative tests for unsupported types and malformed frames.
4. Validate failure behavior under Couchbase outage and timeouts.
5. Re-run benchmark and soak tests against the current compatibility baseline.

## Production Readiness Exit Definition

`ProtoGemCouch` will be considered production ready for its defined v1 scope when all of the following are true:

- supported operations work with a real Geode client without workarounds
- supported types are clearly defined and validated
- protocol compatibility claims are backed by automated regression and integration tests
- failure behavior is deterministic and documented
- deployment and security guidance are production-safe
- observability is sufficient for live operations
- performance envelope is measured and documented
- release criteria are enforced before shipping
