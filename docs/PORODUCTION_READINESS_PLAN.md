# ProtoGemCouch PRODUCTION READINESS PLAN

## Summary

`ProtoGemCouch` has achieved a successful end-to-end CRUD and native contains demo milestone for the validated string-value sample path.

The next goal is to move from:

- working prototype
- validated sample-path compatibility
- targeted protocol captures and fixes

to:

- clearly scoped production-ready compatibility profile
- repeatable validation
- operational hardening
- supportable deployment model

This document defines the production-readiness path, phases, deliverables, and exit criteria.

---

## Current Verified Baseline

The current baseline already includes:

- successful Geode client handshake with the shim
- successful create/read/update/delete flow in the sample app
- successful backend persistence in Couchbase
- native Java String read behavior for the supported path
- deterministic Geode string request decoding for supported PUT values
- working destroy/remove reply handling for the validated sample flow
- native `containsKeyOnServer(...)` protocol path
- native server-side contains-value-for-key protocol path
- clean missing-document semantics for supported contains paths
- structured logging
- metrics summaries
- startup validation
- Docker-based deployment path
- initial benchmark / soak / operational documentation

This is a strong prototype baseline, but it is not yet broad enough to claim production readiness.

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

## Scope Assumption for v1

A realistic v1 production scope should be intentionally limited.

### Recommended v1 support boundary
- Geode client handshake
- string keys
- supported string values
- create
- read
- update
- delete
- contains key / existence semantics
- contains value-for-key semantics
- basic missing-document semantics
- defined behavior for unsupported features

### Not assumed for initial production scope
- full Geode server parity
- arbitrary Java object graphs
- PDX parity
- all bulk and collection operations
- listener/callback/event parity
- advanced distributed-region semantics

This scope should be refined and published as a formal support contract.

---

# Phase 1 - Compatibility Completion for v1 Scope

## Objective
Finish native compatibility for the minimum supported v1 operation set.

## Tasks

### 1.1 Native GET type fidelity
- Status: Complete for supported string values in the validated sample path.
- Supported string-value reads return as native Java-compatible `java.lang.String` values.
- Continue validating against additional real Geode client scenarios.

### 1.2 Native contains semantics
- Status: Complete for the validated sample path.
- `containsKeyOnServer(...)` is validated through native `CONTAINS` mode `0`.
- Server-side contains-value-for-key is validated through native `CONTAINS` mode `1`.
- Expected missing-document cases return `false`.

### 1.3 Missing/null response validation
- Status: Partially complete.
- Missing contains-key and contains-value-for-key behavior is validated.
- Missing GET behavior still needs broader reply-shape validation and regression coverage.

### 1.4 Operation-by-operation response validation
Validate all reply headers, part counts, part types, and payload shapes for supported operations:
- PUT
- GET
- REMOVE
- CONTAINS mode `0`
- CONTAINS mode `1`

### 1.5 Error reply semantics
- Validate the protocol behavior for unsupported operations and malformed requests.
- Decide whether to reply with explicit error frames or close the connection.

## Deliverables
- updated compatibility matrix
- native supported operation set working without client workarounds for the validated path
- regression tests for supported reply shapes
- updated docs describing supported v1 behavior

## Exit Criteria
- supported v1 operations pass with a real Geode client
- no required sample-client workaround remains for supported behaviors in the validated support profile
- response wire shapes are captured and regression-tested

---

# Phase 2 - Serialization and Type Strategy

## Objective
Define and harden the supported type model.

## Tasks

### 2.1 Decide serialization contract
Choose and document one of:
- native Geode serialization for supported types
- constrained translation layer for supported value classes
- limited compatibility profile with explicit exclusions

### 2.2 Stabilize request decoding
- Status: Complete for supported string PUT values.
- Supported string-like requests are handled predictably through deterministic Geode string decoding.
- DataSerializer fallback behavior should remain documented for unsupported or future object paths.

### 2.3 Validate supported value classes
Validate and document at least:
- String
- primitive-like simple values if in scope
- null / missing handling
- unsupported object behavior

### 2.4 Document unsupported types
Explicitly define behavior for:
- arbitrary Java objects
- PDX
- custom serializers
- collections/maps unless validated

## Deliverables
- serialization strategy decision
- supported type matrix
- tests for supported and unsupported types
- updated limitations doc

## Exit Criteria
- supported types behave consistently
- unsupported types fail in a defined and documented way
- no hidden fallback behavior remains undocumented

---

# Phase 3 - Regression and Integration Testing

## Objective
Make compatibility repeatable and safe to change.

## Tasks

### 3.1 Golden-wire tests
Turn real protocol captures into regression fixtures for:
- PUT
- GET
- REMOVE
- CONTAINS mode `0`
- CONTAINS mode `1`
- control frames
- any additional supported operations

### 3.2 Integration test harness
Build repeatable integration tests using:
- real Geode client
- shim
- Couchbase
- containerized test environment

### 3.3 End-to-end scenario tests
Add scenarios for:
- create/read/update/delete
- missing document reads
- contains key existing/missing
- contains value-for-key existing/missing
- repeated update/delete cycles
- reconnect behavior
- multiple consecutive client sessions

### 3.4 Negative tests
Add tests for:
- malformed frames
- unsupported operations
- serialization failures
- Couchbase misses and backend failures

## Deliverables
- repeatable integration suite
- golden-wire regression fixtures
- CI execution path for integration validation

## Exit Criteria
- supported compatibility claims are covered by automated tests
- protocol regressions are caught before release
- end-to-end validation is reproducible in CI/local environments

---

# Phase 4 - Failure Handling and Robustness

## Objective
Ensure safe behavior under real-world failure conditions.

## Tasks

### 4.1 Backend failure behavior
Define and validate behavior for:
- Couchbase unavailable at startup
- Couchbase unavailable during requests
- read/write timeouts
- bucket/scope/collection misconfiguration

### 4.2 Client/session failure behavior
Define and validate behavior for:
- connection drops
- partial reads
- malformed request frames
- unsupported opcode handling
- response serialization failures

### 4.3 Recovery and reconnect behavior
- Validate client reconnect behavior.
- Validate shim stability after repeated client failures.

### 4.4 Logging and metrics on failure
For each failure class, ensure:
- structured log event exists
- metric increments appropriately
- operational meaning is clear

## Deliverables
- failure behavior matrix
- operational runbook updates
- integration tests for major failure modes

## Exit Criteria
- major failure cases have deterministic behavior
- logs and metrics are sufficient for triage
- failure handling is documented and tested

---

# Phase 5 - Observability and Operations Hardening

## Objective
Make the shim operable in production.

## Tasks

### 5.1 Metrics expansion
Add/verify:
- per-operation request counts
- per-operation success/error counts
- latency histograms
- fallback decode usage counts
- unsupported operation counters
- connection lifecycle metrics
- expected-miss counters for read/contains paths if useful

### 5.2 Health and readiness semantics
- Ensure readiness reflects backend availability correctly.
- Define liveness behavior clearly.

### 5.3 Dashboards and alerts
Create:
- operational dashboards
- alert thresholds for errors, latency, backend failures, and reconnect churn

### 5.4 Runbook refinement
Update runbooks for:
- startup
- health triage
- backend failures
- client compatibility issues
- rollback and restart

## Deliverables
- observability dashboard definitions
- alert recommendations
- updated runbooks

## Exit Criteria
- operators can detect and diagnose major issues quickly
- health/readiness are meaningful
- metrics support troubleshooting and release confidence

---

# Phase 6 - Security and Deployment Hardening

## Objective
Prepare the service for safe deployment.

## Tasks

### 6.1 Secret handling
- remove any insecure config patterns
- ensure secrets are injected securely
- ensure logs redact sensitive values

### 6.2 Transport security
Decide and implement as needed:
- TLS to Couchbase
- TLS between client and shim
- certificate handling guidance

### 6.3 Image hardening
- minimize container surface area
- pin dependencies and base images
- enable vulnerability scanning

### 6.4 Deployment hardening
- graceful shutdown
- resource sizing guidance
- deployment health checks
- production compose / Kubernetes guidance if applicable

## Deliverables
- hardened Docker/deployment docs
- security checklist
- configuration guidance for production use

## Exit Criteria
- deployment guidance is production-safe
- secret and transport practices are documented and enforced
- image/config posture is acceptable for production review

---

# Phase 7 - Performance and Scale Qualification

## Objective
Quantify the supported production envelope.

## Tasks

### 7.1 Re-run benchmarks on current compatibility baseline
Measure:
- single-client latency
- concurrent throughput
- CRUD mix latency
- contains operation latency
- remove path latency
- reconnect churn behavior

### 7.2 Soak and stability testing
Run sustained tests for:
- memory stability
- connection stability
- backend latency tolerance
- long-duration request correctness

### 7.3 Capacity characterization
Document:
- maximum tested concurrency
- latency percentiles
- failure thresholds
- known bottlenecks

## Deliverables
- updated performance report
- updated soak results
- production sizing guidance

## Exit Criteria
- the supported envelope is measured and documented
- no major stability regressions appear under sustained load
- performance expectations are explicit

---

# Phase 8 - Release Management and Supportability

## Objective
Make shipping and supporting the service realistic.

## Tasks

### 8.1 Publish support contract
Document:
- supported Geode client versions
- supported operations
- supported types
- unsupported features
- known differences from native Geode

### 8.2 Release gating
Define mandatory pre-release checks:
- integration suite pass
- golden-wire tests pass
- benchmark/soak thresholds met
- docs updated
- limitations reviewed
- security/deployment checks passed

### 8.3 Versioning and changelog discipline
- semantic or defined release versioning
- changelog expectations
- upgrade notes

### 8.4 Support docs
- troubleshooting guide
- issue triage guide
- known error signatures
- escalation path

## Deliverables
- release checklist
- support contract
- changelog / release note template
- operational support docs

## Exit Criteria
- releases can be gated consistently
- support expectations are explicit
- operators and engineers have enough documentation to maintain the system

---

## Master Task List

## Completed current-baseline tasks
1. Make GET return native string values for the supported path.
2. Make native contains key semantics work for the supported path.
3. Make native contains value-for-key semantics work for the supported path.
4. Clean up expected Couchbase missing-document logging for supported contains paths.
5. Stabilize deterministic string PUT decoding for the supported path.

## Immediate next tasks
1. Convert real protocol captures into golden-wire regression tests.
2. Build repeatable real-client integration tests.
3. Validate GET_ALL and PUT_ALL against real Geode client behavior.
4. Validate KEY_SET and SIZE behavior.
5. Tighten not-found semantics for supported GET/read cases.

## Near-term hardening tasks
6. Define serialization/type support policy.
7. Expand failure-mode handling and tests.
8. Expand metrics and operational visibility.
9. Harden deployment and security configuration.

## Late-stage production tasks
10. Re-run performance and soak testing on the hardened compatibility baseline.
11. Publish support contract and release criteria.
12. Finalize runbooks, dashboards, and support docs.

---

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

---

## Recommended Next Step

Start with:

## Task 1 - Golden-wire and integration regression tests for the current validated baseline

This is now the highest-value next task because native String GET, native contains semantics, deterministic string PUT decoding, remove handling, and expected-miss logging are all validated for the current sample path. The next risk is regression. The project should preserve this baseline with automated tests before expanding into broader operations.