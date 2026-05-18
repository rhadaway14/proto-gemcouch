# ProtoGemCouch Launch Criteria

## Purpose

This document defines the minimum criteria required before ProtoGemCouch can be considered ready for use in a given scope.

---

## Scope levels

### Level 1 — Prototype
- protocol exploration
- partial opcode support
- developer-run only
- no production claims

### Level 2 — Internal test ready
- repeatable startup
- supported operations documented
- unit/integration testing exists
- compatible with scoped internal validation flows

### Level 3 — Scoped production candidate
- target application/use case defined
- required operations validated
- compatibility gaps documented and accepted
- logging, packaging, startup validation, health checks, and repeatable deployment completed
- observability sufficient for internal/scoped validation

### Level 4 — Production ready
- deployment hardened
- security posture reviewed
- release process established
- rollback tested
- runbooks complete
- support handoff possible
- operational monitoring and alerting complete

---

## Current assessed status

**Current recommendation: Level 3 — Scoped production candidate**

ProtoGemCouch now demonstrates:

- stable CRUD-style and bulk-style behavior for the current supported operation set
- broad typed value compatibility coverage
- PDX / `PdxInstance` round-trip coverage
- large collection boundary coverage for `keySetOnServer`, `getAll`, and `putAll/getAll`
- repeatable Docker and Docker Compose deployment
- automated local Couchbase initialization
- structured logging, startup validation, health/readiness endpoints, JSON metrics, and Prometheus metrics
- compatibility, deployment, limitation, and launch documentation
- fast unit tests for risky response-writer byte-shape behavior
- Docker-backed real-client integration validation

Still open before a broader Level 4 / general-purpose production claim:

- high-concurrency and soak validation against the latest PDX/boundary/observability baseline
- stronger transport/security hardening
- final target-application opcode review
- formal support and operational handoff
- optional Prometheus histogram buckets and deeper latency decomposition

---

## Functional criteria

### Required for scoped launch

- all required opcodes for the target application are identified
- all required opcodes are documented in `COMPATIBILITY_MATRIX.md`
- all required opcodes are either supported or explicitly accepted as partially supported by stakeholders
- all unsupported Geode behavior is excluded from launch claims

### Current status

- [x] Supported opcode subset documented
- [x] Core current operations implemented
- [x] Bulk operation support implemented
- [x] Key metadata operations implemented
- [x] Semantic gaps documented
- [ ] Full broader protocol coverage
- [ ] General-purpose Geode compatibility claim

---

## Test criteria

### Unit tests

Required:

- `mvn test` passes
- core handlers covered
- factories/utilities covered
- response builder coverage exists
- risky wire encodings covered by fast tests

Current status:

- [x] Unit test framework established
- [x] Multiple handler tests added
- [x] Factory and utility tests added
- [x] Core response-writer tests added
- [x] Response-writer boundary encoding tests added
- [ ] Additional edge-path expansion can continue as needed

### Integration tests

Required:

- `mvn verify` passes
- current supported end-to-end flows validated
- real Geode client talks to the shim
- Docker-backed Couchbase persistence path validated

Current status:

- [x] Integration tests exist for core supported flows
- [x] Current scoped flows validated end-to-end
- [x] PDX / PdxInstance flows validated end-to-end
- [x] Large collection boundary flows validated end-to-end

Latest result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchPdxRegistryDiscoveryIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 3

ProtoGemCouchSerializationIntegrationTest
Tests run: 135, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3

BUILD SUCCESS
```

### Performance tests

Required for Level 3:

- benchmark evidence exists
- soak evidence exists for the current compatibility baseline
- known operating envelope exists
- high-concurrency behavior is characterized

Current status:

- [ ] Current PDX/boundary baseline needs renewed benchmark and soak evidence
- [ ] High-concurrency behavior needs characterization

---

## Compatibility documentation criteria

Required:

- `COMPATIBILITY_MATRIX.md` exists
- supported operations are documented
- supported value families are documented
- semantic gaps are documented
- unsupported operations are not implied to be supported

Current status:

- [x] Complete for current scope
- [x] PDX support documented
- [x] large collection boundary behavior documented
- [x] nested map limitations documented

---

## Observability criteria

Required:

- structured logs
- request visibility
- startup/shutdown logging
- machine-checkable liveness/readiness
- operation counters
- latency metrics
- error metrics
- externally consumable metrics endpoint

Current status:

- [x] Structured logging added
- [x] `/live` endpoint available
- [x] `/ready` endpoint available
- [x] Compose health checks available
- [x] Operation counters
- [x] Success/error/unknown counters
- [x] Latency metrics
- [x] JSON metrics endpoint at `/metrics/json`
- [x] Prometheus-format metrics endpoint at `/metrics`
- [x] Metrics registry unit tests added
- [x] Health HTTP endpoint unit tests added
- [ ] Prometheus histogram buckets
- [ ] Repository-level latency split
- [ ] Serialization latency split
- [ ] Request/response byte-size metrics

---

## Configuration and startup criteria

Required:

- config is externalized
- invalid config fails fast
- startup validates required runtime inputs
- startup behavior is documented

Current status:

- [x] Env-var-based startup config
- [x] Fast failure on missing required config
- [x] Startup validation present
- [x] Deployment docs updated

---

## Deployment criteria

Required:

- repeatable packaging exists
- repeatable deployment exists
- rollback path documented

Current status:

- [x] Dockerfile added
- [x] Docker Compose added
- [x] Automated Couchbase init added for local deployment
- [x] Deployment docs written
- [x] Rollback guidance documented

---

## Security criteria

Required for scoped candidate:

- no secrets in source
- secrets externalized
- sensitive values redacted in logs
- basic deployment security guidance written
- TLS / transport strategy defined for non-local deployment
- shim-side client authentication model defined or explicitly accepted as out of scope

Current status:

- [x] Secrets externalized through environment variables
- [x] Passwords should be redacted in startup logging
- [ ] TLS strategy fully implemented
- [ ] Shim-side client authentication model defined
- [ ] CI scan-enforcement policy finalized

---

## Operational readiness criteria

Required:

- startup/shutdown runbook exists
- recovery guidance exists
- smoke validation exists
- health/readiness strategy exists
- metrics strategy exists

Current status:

- [x] `DEPLOYMENT.md` exists
- [x] `docs/OBSERVABILITY.md` exists
- [x] health/readiness strategy exists
- [x] Compose deployment health checks available
- [x] JSON metrics endpoint exists
- [x] Prometheus metrics endpoint exists
- [x] Sample Prometheus scrape config exists
- [x] Sample Grafana Alloy scrape config exists
- [ ] Formal support handoff not yet performed

---

## Release process criteria

Required for stronger release confidence:

- CI build/test automation exists
- container build automation exists
- dependency/static analysis automation exists
- release checklist exists
- version/tag strategy exists

Current status:

- [ ] Confirm CI status against current repository state
- [ ] Confirm Docker image build automation against current repository state
- [ ] Confirm dependency/static analysis automation against current repository state
- [ ] Optional release/tag artifact publishing can be expanded later

---

## Launch gate checklist

### Achieved

- [x] Scoped use case supported
- [x] Compatibility matrix complete for current supported ops
- [x] Current supported flows tested
- [x] PDX round-trip support validated
- [x] Large collection boundary behavior validated
- [x] Structured logging added
- [x] Startup validation hardened
- [x] Health/readiness strategy added
- [x] Docker packaging completed
- [x] Docker Compose deployment completed
- [x] Automated local Couchbase init completed
- [x] Deployment docs completed
- [x] Current limitations documented
- [x] Response-writer unit tests added for risky encodings

### Still open

- [x] Add operation metrics and latency tracking
- [x] Add `/metrics/json`
- [x] Add Prometheus-format `/metrics`
- [ ] Add high-concurrency and soak testing for the current baseline
- [ ] Add TLS / transport hardening for broader deployment
- [ ] Define shim-side client authentication model
- [ ] Confirm final release scope against target app opcode usage

---

## Current launch conclusion

ProtoGemCouch is ready to be described as:

> **A scoped production candidate for applications whose required behavior is covered by the currently supported CRUD, bulk, key metadata, typed value, and PDX compatibility subset, with repeatable containerized deployment, machine-checkable health/readiness, JSON and Prometheus metrics endpoints, and Docker-backed real-client integration tests.**

It should not yet be described as:

> **A general-purpose Apache Geode server replacement.**

The current launch-readiness milestone is:

```text
observability-hardening-complete
```

Recommended next milestone:

```text
current-baseline-performance-and-soak-refresh
```
