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

### Couchbase (backend) transport

The shim connects to Couchbase over TLS when the connection string uses `couchbases://` or
`CB_TLS_ENABLED=true`. It trusts the cluster certificate supplied via `CB_TLS_CERT_PATH` (a PEM
file). Validated end-to-end by `ProtoGemCouchBackendTlsIntegrationTest` against a TLS-served
Couchbase.

| Env var | Default | Meaning |
|---|---|---|
| `CB_TLS_ENABLED` | `false` | Enable TLS to Couchbase (also implied by a `couchbases://` connection string). |
| `CB_TLS_CERT_PATH` | — | PEM certificate to trust (the Couchbase cluster cert / CA). |
| `CB_TLS_VERIFY_HOSTNAME` | `true` | Verify the Couchbase hostname against the certificate. Only disable for self-signed certs whose SAN does not match the host (e.g. local test setups). |

For production, use a Couchbase certificate whose SAN matches the connection hostname and leave
hostname verification enabled.

### Shim-side transport (inbound TLS)

The Geode protocol listener supports native TLS. When enabled, the shim terminates TLS on the
Geode port using a server keystore, validated against a real Geode SSL client
(`ProtoGemCouchTlsIntegrationTest`). It is **off by default**; enable it for any deployment that
crosses an untrusted network.

| Env var | Default | Meaning |
|---|---|---|
| `TLS_ENABLED` | `false` | Enable TLS on the Geode listener. |
| `TLS_KEYSTORE_PATH` | — | Path to the server keystore (required when enabled). |
| `TLS_KEYSTORE_PASSWORD` | — | Keystore password. |
| `TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type. |
| `TLS_CLIENT_AUTH` | `none` | `require` enables mutual TLS (client-certificate authentication). |
| `TLS_TRUSTSTORE_PATH` / `_PASSWORD` / `_TYPE` | — | Truststore for verifying client certs (required when client auth is `require`). |

Geode clients connect with `ssl-enabled-components=server` and a truststore trusting the shim's
certificate. See `docs/RUNBOOK.md` for the full variable list and `docker-compose.yml`
(`protogemcouch-tls`) for a working example.

Mutual TLS (`TLS_CLIENT_AUTH=require`) provides transport-level client authentication: only clients
presenting a certificate trusted by the shim's truststore can connect. This is the recommended
client-auth model for the shim (it does not implement the Geode application-level security
handshake).

### Health/admin endpoint

The health/admin server (`/live`, `/ready`, `/metrics`, `/metrics/json`) defaults to plain HTTP on
all interfaces, which is appropriate for a trusted operator/monitoring network. It can be hardened:

| Env var | Default | Meaning |
|---|---|---|
| `HEALTH_TLS_ENABLED` | `false` | Serve the admin endpoints over HTTPS, using the same keystore as `TLS_KEYSTORE_*`. |
| `HEALTH_BIND_ADDRESS` | all interfaces | Bind to a specific interface (e.g. `127.0.0.1` or an internal address) to restrict exposure. |

Enable HTTPS for metrics scraping over untrusted networks, and/or restrict the bind address. Keep
`HEALTH_PORT` access limited to operators and monitoring systems regardless (see network exposure
guidance below). The endpoints expose no credentials or sensitive payloads.

---

## Connection resource guards

To limit resource exhaustion from dead, slow, or excessive client connections:

| Env var | Default | Effect |
|---|---|---|
| `CONNECTION_IDLE_TIMEOUT_SECONDS` | 300 | Connections idle (no read/write) this long are closed and reaped. `0` disables. |
| `MAX_CONNECTIONS` | 0 (unlimited) | New connections beyond this concurrent count are rejected and closed. |
| `FIRST_REQUEST_TIMEOUT_SECONDS` | 10 | A connection must complete its handshake and first request within this long or it is closed. Not reset by trickled bytes, so it bounds slowloris-style connections. `0` disables. |
| `HANDLER_MAX_PENDING_TASKS` | 10000 | Per-handler-thread queue bound; once full, requests are shed (connection closed) instead of growing the backlog unbounded. `0` = unbounded. |

These guards are observable via `protogemcouch_connections_rejected_total`,
`protogemcouch_idle_connections_closed_total`, `protogemcouch_connections_first_request_timeout_total`,
and `protogemcouch_requests_shed_total`. Set `MAX_CONNECTIONS` to a value matched to the shim's
resources, and keep the idle and first-request timeouts enabled on untrusted networks.

The idle timeout reaps inactive connections; the first-request deadline additionally closes
slowloris-style connections that stay technically active by trickling bytes without ever completing
a request (which would otherwise keep resetting the idle timer). Together they bound both idle and
slow-but-never-complete connections.

---

## Network exposure guidance

Recommended:
- expose the shim only to the clients that need it
- restrict inbound access to `SHIM_PORT`
- restrict inbound access to `HEALTH_PORT`
- isolate the shim and Couchbase on private networks whenever possible

---

## Inbound frame validation

The Geode protocol encodes payload length, part count, and per-part length as raw 32-bit
integers supplied by the client. The frame decoder validates every one of these against
configurable limits before allocating memory or reading bytes, so a corrupt or hostile frame
cannot drive the shim into an oversized allocation and crash it with an `OutOfMemoryError`.

Limits (with defaults):

| Env var | Default | Meaning |
|---|---|---|
| `MAX_FRAME_BYTES` | 52428800 (50 MiB) | Maximum declared payload size, and per-part length cap. |
| `MAX_FRAME_PARTS` | 100000 | Maximum number of parts a single frame may declare. |

When a frame violates a limit, the shim:

- increments `protogemcouch_malformed_frames_total`
- logs a structured `malformed_frame` event with a reason code and the offending value
- closes the offending connection (the byte stream can no longer be trusted to be frame-aligned)

Tune the limits down to the smallest values that comfortably fit legitimate traffic for your
deployment to reduce the per-connection memory exposure further.

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