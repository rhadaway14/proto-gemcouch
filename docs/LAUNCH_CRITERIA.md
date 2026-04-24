
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
- logging, metrics, packaging, soak testing, and health checks completed

### Level 4 — Production ready
- deployment hardened
- security posture reviewed
- release process established
- rollback tested
- runbooks complete
- support handoff possible

---

## Current assessed status

**Current recommendation: Level 3 — Scoped production candidate**

ProtoGemCouch now demonstrates:
- stable CRUD-style and bulk-style behavior for the current supported operation set
- strong benchmark and soak-test evidence
- repeatable Docker and Docker Compose deployment
- automated local Couchbase initialization
- structured logging, metrics, startup validation, and health/readiness endpoints
- compatibility, performance, soak, deployment, and security documentation
- GitHub Actions automation for build, Docker image build, dependency submission, and CodeQL scanning

Still open before a broader claim:
- full protocol coverage
- broader negative-path/unit-test completion beyond current scope
- stronger transport/security hardening
- CI enforcement policy for scan findings
- optional release automation beyond artifact build

---

## Functional criteria

### Required for scoped launch
- all required opcodes for the target application are identified
- all required opcodes are documented in `COMPATIBILITY_MATRIX.md`
- all required opcodes are either:
  - supported
  - or explicitly accepted as partially supported by stakeholders

### Current status
- [x] Supported opcode subset documented
- [x] Core current operations implemented
- [x] Semantic gaps documented
- [ ] Full broader protocol coverage
- [ ] General-purpose compatibility claim

---

## Test criteria

### Unit tests
Required:
- `mvn test` passes
- core handlers covered
- factories/utilities covered
- response builder coverage exists

Current status:
- [x] Unit test framework established
- [x] Multiple handler tests added
- [x] Factory and utility tests added
- [x] Core response-writer tests added
- [ ] Additional edge-path expansion can continue as needed

### Integration tests
Required:
- `mvn verify` passes
- current supported end-to-end flows validated

Current status:
- [x] Integration tests exist for core supported flows
- [x] Current scoped flows validated end-to-end

### Performance tests
Required:
- benchmark evidence exists
- soak evidence exists
- known operating envelope exists

Current status:
- [x] Benchmark harness built
- [x] Workload profiles added
- [x] Short-run performance results documented
- [x] Soak runs completed and documented
- [x] CRUD vs metadata performance classes identified

---

## Compatibility documentation criteria

Required:
- `COMPATIBILITY_MATRIX.md` exists
- supported operations are documented
- semantic gaps are documented
- unsupported operations are not implied to be supported

Current status:
- [x] Complete for current scope

---

## Observability criteria

Required:
- structured logs
- request visibility
- startup/shutdown logging
- machine-checkable liveness/readiness

Current status:
- [x] Structured logging added
- [x] Metrics registry added
- [x] Periodic metrics snapshots added
- [x] `/live` endpoint added
- [x] `/ready` endpoint added
- [ ] External metrics endpoint not yet added

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
- CI dependency/static scanning in place

Current status:
- [x] Secrets externalized
- [x] Passwords redacted in safe startup logging
- [x] `SECURITY.md` added
- [x] GitHub Actions dependency submission added
- [x] GitHub Actions CodeQL analysis added
- [ ] TLS strategy fully implemented
- [ ] Scan-enforcement policy finalized
- [ ] Shim-side client authentication model defined

---

## Operational readiness criteria

Required:
- startup/shutdown runbook exists
- recovery guidance exists
- smoke validation exists
- health/readiness strategy exists

Current status:
- [x] `RUNBOOK.md` exists
- [x] `DEPLOYMENT.md` exists
- [x] `/live` and `/ready` implemented
- [x] Compose deployment health checks available
- [ ] Formal support handoff not yet performed

---

## Release process criteria

Required for stronger release confidence:
- CI build/test automation exists
- container build automation exists
- dependency/static analysis automation exists
- release checklist exists

Current status:
- [x] Build/test workflow added
- [x] Docker image build workflow added
- [x] Dependency/code scanning workflow added
- [x] Release checklist exists
- [ ] Optional release/tag artifact publishing can be expanded later

---

## Launch gate checklist

### Achieved
- [x] Scoped use case supported
- [x] Compatibility matrix complete for current supported ops
- [x] Current supported flows tested
- [x] Structured logging added
- [x] Basic metrics added
- [x] Startup validation hardened
- [x] Health/readiness strategy added
- [x] Benchmarking completed
- [x] Soak testing completed
- [x] Docker packaging completed
- [x] Docker Compose deployment completed
- [x] Automated local Couchbase init completed
- [x] Deployment docs completed
- [x] Security doc completed
- [x] Runbook completed
- [x] GitHub Actions CI/security scanning added

### Still open
- [ ] Finish remaining unit-test expansion as desired
- [ ] Add TLS / transport hardening for broader deployment
- [ ] Review and triage CI security findings continuously
- [ ] Confirm final release scope against target app opcode usage

---

## Current launch conclusion

ProtoGemCouch is ready to be described as:

> **A scoped production candidate for applications whose required behavior is covered by the currently supported CRUD, bulk, and server-side operation subset, with machine-checkable health/readiness, repeatable containerized deployment, and CI-based build/security scanning.**