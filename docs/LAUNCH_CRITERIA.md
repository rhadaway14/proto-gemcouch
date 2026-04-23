
## `LAUNCH_CRITERIA.md`

```md id="ah72ls"
# ProtoGemCouch Launch Criteria

## Purpose

This document defines the minimum criteria required before ProtoGemCouch can be considered production-ready for a given target application scope.

---

## 1. Scope definition required

Launch approval must always be tied to a defined scope.

Examples:
- approved for a limited internal test environment
- approved for one application using a specific subset of operations
- approved for general production use

ProtoGemCouch must not be labeled "production-ready" without a clear scope.

---

## 2. Functional criteria

### Required
- all required opcodes for the target application are identified
- all required opcodes are documented in `COMPATIBILITY_MATRIX.md`
- all required opcodes are either:
  - Supported
  - or explicitly accepted as Partially Supported by stakeholders

### Must pass
- client handshake works reliably
- core CRUD behavior works for the scoped app flows
- bulk operations required by the target app work
- server-side operations required by the target app work

### Blocking issues
Launch is blocked if:
- a required opcode is unsupported
- a required opcode has unstable response behavior
- data correctness is uncertain
- semantic gaps are unknown rather than documented

---

## 3. Test criteria

### Unit tests
- `mvn test` passes
- handler tests cover supported operations
- response-writer tests cover supported response types
- utility and factory tests pass

### Integration tests
- `mvn verify` passes in the intended deployment-like environment
- integration tests exist for all supported critical operations
- regression tests exist for previously broken protocol flows

### Regression criteria
- any bug fixed in a supported opcode must add a test
- no release should ship with known regressions in supported operations

---

## 4. Compatibility documentation criteria

The following must exist and be current:
- `COMPATIBILITY_MATRIX.md`
- semantic gaps documented per operation
- unsupported operations clearly listed
- notes on callback/version/event fidelity where relevant

Launch is blocked if compatibility is assumed but not documented.

---

## 5. Observability criteria

Before production launch, the system must provide:

- structured logs
- request/operation visibility by opcode
- success/failure counts
- latency visibility
- unknown opcode visibility
- startup and shutdown logging

Minimum acceptable observability:
- operators can determine what operation failed
- operators can identify if the failure is protocol, configuration, or Couchbase related

---

## 6. Configuration and startup criteria

Before launch:
- required configuration is documented
- invalid configuration causes fast failure
- startup validates repository connectivity
- port conflicts fail clearly
- secrets are not hardcoded in code or docs

Launch is blocked if startup can partially succeed in a broken state.

---

## 7. Performance criteria

Before launch:
- concurrency testing has been performed
- soak testing has been performed
- acceptable throughput and latency targets are defined for the scoped app
- no known memory leak or unbounded resource growth exists in soak testing

Minimum evidence required:
- benchmark summary
- soak summary
- known capacity envelope

Launch is blocked if no performance envelope is known.

---

## 8. Security criteria

Before launch:
- secrets are provided externally
- logs do not leak credentials
- dependency review is completed
- required transport security decisions are documented
- Couchbase access uses least privilege practical for the deployment

Launch is blocked if credential handling is unsafe.

---

## 9. Deployment criteria

Before launch:
- deployment steps are documented
- rollback steps are documented
- artifact version is identifiable
- startup and shutdown procedures are documented
- at least one repeatable deployment path exists

Examples:
- local packaged run
- Docker container
- Kubernetes deployment

Launch is blocked if deployment is ad hoc or non-repeatable.

---

## 10. Runbook criteria

The following must exist:
- `RUNBOOK.md`
- startup procedure
- shutdown procedure
- smoke tests
- common failure modes
- recovery steps
- escalation guidance

Launch is blocked if operations knowledge exists only in conversation history or in one engineer’s head.

---

## 11. Production launch gates

All of the following must be true:

- [ ] scoped application use case defined
- [ ] compatibility matrix complete for scoped operations
- [ ] required opcodes supported or explicitly accepted
- [ ] unit tests passing
- [ ] integration tests passing
- [ ] structured logging added
- [ ] basic metrics added
- [ ] startup validation hardened
- [ ] concurrency testing completed
- [ ] soak testing completed
- [ ] deployment method documented
- [ ] rollback procedure documented
- [ ] runbook complete
- [ ] known semantic gaps reviewed and accepted
- [ ] no unresolved correctness issues for scoped operations

---

## 12. Launch readiness levels

### Level 1 — Prototype
- developer-run only
- incomplete compatibility
- basic tests only
- no production claims

### Level 2 — Internal test ready
- repeatable startup
- core supported operations documented
- unit and integration tests in place
- acceptable for lab or internal evaluation

### Level 3 — Scoped production candidate
- defined target app
- required operations validated
- semantic gaps documented and accepted
- observability, config validation, and soak testing completed

### Level 4 — Production ready
- deployment hardened
- security addressed
- monitoring and runbooks complete
- rollback tested
- operator handoff possible

---

## 13. Current recommended status

Based on the current project state, ProtoGemCouch should be described as:

**Internal test ready / scoped production candidate in progress**

It should not yet be described as a general production-ready GemFire/Geode replacement until:
- observability is added
- config hardening is completed
- performance and soak testing are completed
- deployment packaging is finalized
- runbooks are complete