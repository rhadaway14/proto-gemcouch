# ProtoGemCouch Launch Criteria

## Purpose

This document defines the minimum criteria required before ProtoGemCouch can be considered ready for use in a given scope.

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

Still open before a broader claim:
- full protocol coverage
- broader negative-path/unit-test completion beyond current scope
- stronger transport/security hardening
- CI dependency/security scanning

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

### Still open
- [ ] Finish remaining unit-test expansion as desired
- [ ] Add TLS / transport hardening for broader deployment
- [ ] Add CI dependency/security scanning
- [ ] Confirm final release scope against target app opcode usage

---

## Current launch conclusion

ProtoGemCouch is ready to be described as:

> **A scoped production candidate for applications whose required behavior is covered by the currently supported CRUD, bulk, and server-side operation subset, with machine-checkable health/readiness and repeatable containerized deployment.**