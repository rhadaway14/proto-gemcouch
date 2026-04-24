# ProtoGemCouch Launch Criteria

## Purpose

This document defines the minimum criteria required before ProtoGemCouch can be considered ready for use in a given scope.

Launch readiness must always be tied to a specific scope. ProtoGemCouch should not be described as a full general-purpose GemFire/Geode replacement without explicit evidence for that broader claim.

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
- logging, metrics, packaging, and soak testing completed

### Level 4 — Production ready
- deployment hardened
- security posture reviewed
- health/readiness strategy complete
- rollback tested
- runbooks complete
- support handoff possible

---

## Current assessed status

**Current recommendation: Level 3 — Scoped production candidate**

ProtoGemCouch has now demonstrated:

- stable CRUD-style and bulk-style behavior for the currently supported operation set
- strong short-run benchmark results
- successful soak results for read-heavy and write-heavy workloads
- repeatable Docker and Docker Compose deployment
- automated local Couchbase initialization
- structured logging and startup validation
- compatibility, performance, soak, deployment, and security documentation

ProtoGemCouch should still be described as scoped rather than general-purpose because:
- only a subset of Geode/GemFire operations is supported
- metadata operations (`SIZE`, `KEY_SET`) are much slower than CRUD paths
- the unit suite is improved but not yet fully complete for every edge case
- transport security and client-auth hardening are not yet fully built out

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

### Current supported operation class
Supported and validated for current scope:
- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- `GET_ALL`
- `PUT_ALL`
- `SIZE`
- `KEY_SET`
- `PING`
- `CONTROL`

### Blocking condition
Launch is blocked if the target application requires unsupported opcodes or semantics that are not yet implemented.

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
- [ ] Unit coverage fully complete for all handler edge cases
- [ ] Broader malformed-frame/negative-path coverage finalized

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
- request visibility by opcode
- startup/shutdown logs
- error visibility
- metrics visibility sufficient for diagnosis

Current status:
- [x] Structured logging added
- [x] Metrics registry added
- [x] Periodic metrics snapshots available
- [ ] External metrics endpoint not yet added
- [ ] More advanced alerting/dashboarding not yet added

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

Current status:
- [x] Secrets externalized
- [x] Passwords redacted in safe startup logging
- [x] `SECURITY.md` added
- [ ] TLS strategy fully implemented
- [ ] Dependency scanning in CI added
- [ ] Shim-side client authentication model defined

---

## Operational readiness criteria

Required:
- startup/shutdown runbook exists
- recovery guidance exists
- smoke validation exists

Current status:
- [x] `RUNBOOK.md` exists
- [x] `DEPLOYMENT.md` exists
- [x] smoke/run guidance exists
- [ ] Formal support handoff not yet performed
- [ ] Shim health/readiness endpoint not yet added

---

## Launch gate checklist

### Achieved
- [x] Scoped use case supported
- [x] Compatibility matrix complete for current supported ops
- [x] Current supported flows tested
- [x] Integration tests pass for current supported flows
- [x] Structured logging added
- [x] Basic metrics added
- [x] Startup validation hardened
- [x] Benchmarking completed
- [x] Soak testing completed
- [x] Docker packaging completed
- [x] Docker Compose deployment completed
- [x] Deployment docs completed
- [x] Security doc completed
- [x] Runbook completed

### Still open
- [ ] Finish remaining unit-test expansion and negative-path coverage
- [ ] Add shim health/readiness strategy
- [ ] Add TLS / transport hardening for broader deployment
- [ ] Add CI dependency/security scanning
- [ ] Confirm final release scope against target application opcode usage

---

## Current launch conclusion

ProtoGemCouch is ready to be described as:

> **A scoped production candidate for applications whose required behavior is covered by the currently supported CRUD, bulk, and server-side operation subset.**

ProtoGemCouch is **not yet ready to be described as a full production-grade GemFire/Geode replacement for arbitrary workloads**.