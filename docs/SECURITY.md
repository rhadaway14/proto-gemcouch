
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

---

## Secrets handling

### Required secrets
The following values are sensitive and must not be hardcoded:

- `CB_PASSWORD`
- potentially `CB_USERNAME`, depending on environment sensitivity

### Rules
- do not commit secrets to source control
- do not place real credentials in docs
- do not hardcode credentials in Java source
- prefer environment variables or secret injection by the runtime platform

### Recommended secret sources
For real deployments, prefer:
- Docker runtime environment injection
- Kubernetes Secrets
- CI/CD secret stores
- cloud secret managers

---

## Logging safety

Current implementation should:
- redact password values in startup logging
- avoid printing secrets directly

Operational rule:
- never log raw secrets
- never paste real credentials into issue trackers, docs, or chat transcripts intended for wider sharing

---

## Couchbase credential guidance

Use least-privilege credentials where practical.

The ideal runtime account should have only the permissions needed for:
- KV reads/writes
- query access only if required by supported operations like `SIZE` and `KEY_SET`

Do not use a broad admin account in production unless strictly necessary.

---

## Transport security

### Current state
This project currently supports standard Couchbase connectivity using the configured connection string.

### Recommended next step
For higher-trust deployments:
- use secure Couchbase transport where applicable
- document certificate/trust requirements
- validate how the client should connect for TLS-enabled Couchbase clusters

### Shim-side transport
The current shim container exposes the plain protocol listener on `SHIM_PORT`.

If network trust boundaries require it, future work should include:
- TLS termination in front of the shim
- or native TLS support in the shim itself

---

## Network exposure guidance

Recommended:
- expose the shim only to the clients that need it
- restrict inbound network access to the shim port
- avoid publishing the shim broadly on untrusted networks
- isolate the shim and Couchbase on private networks whenever possible

---

## Container hardening

Current Docker image hardening includes:
- multi-stage build
- runtime on JRE only
- non-root application user

Recommended future improvements:
- pin exact base image digests
- run vulnerability scans on the built image
- track dependency CVEs
- add image signing if required by your deployment process

---

## Dependency security

ProtoGemCouch depends on:
- Couchbase Java SDK
- Apache Geode client libraries
- Netty
- SLF4J

Recommended practice:
- periodically review dependency versions
- run dependency scanning in CI
- update vulnerable libraries intentionally and with regression testing

---

## Operational security checklist

Before non-lab deployment, confirm:

- [ ] secrets are externalized
- [ ] no real secrets are committed
- [ ] logs are reviewed for secret leakage
- [ ] Couchbase credentials are least-privilege
- [ ] shim port exposure is restricted
- [ ] deployment image is built from a known source state
- [ ] dependency scan is completed
- [ ] rollback path exists

---

## Current limitations

This project is not yet a fully hardened security-reviewed product.

Known areas for future security work:
- stronger TLS story for all traffic paths
- secret-manager integration
- automated dependency scanning
- deployment policy guidance
- optional authentication/authorization model for shim clients

---

## Current conclusion

ProtoGemCouch is now packaged in a way that supports basic secure operational hygiene:
- no secrets in source
- no hardcoded passwords
- redacted startup logging
- non-root container runtime

Additional hardening is still recommended before broader production deployment.