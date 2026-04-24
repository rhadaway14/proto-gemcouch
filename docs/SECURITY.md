# ProtoGemCouch Security

## Purpose

This document records the current security posture and minimum deployment guidance for ProtoGemCouch.

---

## Current security model

ProtoGemCouch currently relies on:

- externalized runtime secrets via environment variables
- startup config validation
- redacted safe config logging
- Couchbase authentication using provided credentials
- separate health port for operational health checks
- GitHub Actions for automated build and security scanning

---

## Secrets handling

Sensitive values must not be hardcoded:
- `CB_PASSWORD`
- potentially `CB_USERNAME`

Rules:
- do not commit secrets to source control
- do not put real credentials in docs
- do not hardcode credentials in Java source
- prefer runtime secret injection

Recommended secret sources:
- Docker runtime environment injection
- Kubernetes Secrets
- CI/CD secret stores
- cloud secret managers

---

## Logging safety

Current implementation:
- redacts password values in startup logs
- avoids printing raw secrets directly

Operational rule:
- never log secrets
- never paste real credentials into tickets or shared docs

---

## Health endpoint security

ProtoGemCouch exposes:
- `/live`
- `/ready`

These endpoints are intentionally simple and do not expose credentials or detailed system internals.

Recommendations:
- do not expose `HEALTH_PORT` broadly on untrusted networks
- restrict access to operators, orchestrators, or trusted monitoring systems
- treat health endpoints as operational surfaces, not public APIs

---

## Couchbase credential guidance

Use least-privilege credentials where practical.

The runtime account should have only the permissions needed for:
- KV reads/writes
- query access only if required for `SIZE` and `KEY_SET`

Do not use a broad admin account in production unless strictly necessary.

---

## Transport security

### Current state
ProtoGemCouch supports standard Couchbase connectivity using the configured connection string.

### Recommended next step
For broader deployment:
- use secure Couchbase transport where applicable
- document certificate/trust requirements
- validate TLS-enabled Couchbase connections

### Shim-side transport
The GemFire protocol listener and health server are plain listeners today.

For higher-trust environments, future work should include:
- TLS termination in front of the shim
- or native TLS support
- access controls around the health port

---

## Network exposure guidance

Recommended:
- expose the shim only to the clients that need it
- restrict inbound access to `SHIM_PORT`
- restrict inbound access to `HEALTH_PORT`
- isolate the shim and Couchbase on private networks whenever possible

---

## Container hardening

Current hardening includes:
- multi-stage build
- JRE-only runtime image
- non-root application user

Recommended future improvements:
- pin base image digests
- run image vulnerability scans
- sign images if needed

---

## Dependency and code security scanning

GitHub Actions now provides automated scanning coverage through:
- build and test workflow
- Docker image build workflow
- dependency graph submission
- CodeQL analysis

### Current CI security posture
- Maven build runs automatically in CI
- dependency graph submission is automated
- CodeQL static analysis is automated
- scans run on push and pull request for mainline branches
- dependency/code scanning can also run on schedule

### Recommended operator practice
- review GitHub Security / Code Scanning alerts regularly
- do not ignore repeated dependency findings without triage
- update vulnerable dependencies intentionally and retest
- treat CI scan failures or alerts as release blockers when severity justifies it

---

## Operational security checklist

Before non-lab deployment:

- [ ] secrets are externalized
- [ ] no real secrets are committed
- [ ] logs reviewed for secret leakage
- [ ] health port exposure restricted
- [ ] Couchbase credentials are least-privilege
- [ ] shim port exposure restricted
- [ ] deployment image built from known source state
- [x] dependency/code scanning configured in CI
- [ ] CI security findings reviewed before release

---

## Current limitations

This is not yet a fully hardened security-reviewed product.

Future security work:
- stronger TLS story for all traffic
- secret-manager integration
- image vulnerability scanning/policy enforcement
- shim-side auth model if needed
- more restrictive health endpoint exposure guidance per environment

---

## Current conclusion

ProtoGemCouch supports basic secure operational hygiene:
- no secrets in source
- no hardcoded passwords
- redacted startup logging
- non-root container runtime
- simple health endpoints without sensitive payloads
- automated CI-based dependency and static code scanning

Additional hardening is still recommended before broader production deployment.