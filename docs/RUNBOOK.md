
# ProtoGemCouch Runbook

## Purpose

This runbook explains how to start, stop, validate, troubleshoot, and operate ProtoGemCouch.

---

## Preconditions

Before starting ProtoGemCouch, confirm:

- required environment variables are set
- Couchbase is reachable
- bucket, scope, and collection exist
- `SHIM_PORT` is available
- `HEALTH_PORT` is available

---

## Required configuration

Required:
- `CB_CONNSTR`
- `CB_USERNAME`
- `CB_PASSWORD`
- `CB_BUCKET`
- `CB_SCOPE`
- `CB_COLLECTION`

Optional:
- `SHIM_PORT` default `40405`
- `HEALTH_PORT` default `8081`
- `MAX_FRAME_BYTES` default `52428800` (inbound frame size cap; see `docs/SECURITY.md`)
- `MAX_FRAME_PARTS` default `100000` (inbound frame part-count cap)
- `ERROR_RESPONSE_MODE` default `exception` (`exception` = reply with a Geode EXCEPTION frame and
  keep the connection open, so the client raises a ServerOperationException; `close` = drop the
  connection on operation failure). The `exception` behavior is validated against a live Geode
  client in `ProtoGemCouchExceptionResponseIntegrationTest`.
- `HANDLER_THREADS` default `64` (size of the executor pool that runs request handlers off the
  Netty event loop; raise it if many connections may block on a slow backend at once)
- `CB_KV_TIMEOUT_MS` default `5000` (per-operation Couchbase KV timeout)
- `CB_CONNECT_TIMEOUT_MS` default `10000` (Couchbase connect timeout)
- `CB_TTL_SECONDS` default `0` (default entry time-to-live in seconds: when `> 0`, value writes get a
  Couchbase document expiry, emulating a Geode entry-time-to-live; `0` disables expiry)
- `CB_TTL_REGIONS` default empty (per-region TTL overrides, e.g. `sessions:1800,cacheA:60`; an exact
  region-name match wins over `CB_TTL_SECONDS`)
- `CB_TTL_MODE` default `ttl` (`ttl` = expiry counts from the last write, i.e. entry-time-to-live;
  `idle` = reads also refresh the expiry via get-and-touch, i.e. entry-idle-time)
- `CB_DURABILITY` default `none` (Couchbase write durability applied to all value writes:
  `none` = ack on the active node's memory; `majority`, `majorityAndPersistToActive`,
  `persistToMajority` = synchronous durability — these require a replicated cluster and will fail
  writes on a single-node cluster)
  - `putAll` is **partial-failure aware**: entries that succeed are persisted and counted in
    `size`/`keySet` even if others fail, and a partial failure is reported to the client as a
    PUT_ALL server error naming the failed keys (rather than silently dropping or rolling back).
  - When a region has a TTL, `size`/`keySet` verify which keys still exist and evict expired ones
    from the keyset metadata, so they stay correct (at the cost of an existence check per key).
- `CB_MAX_VALUE_BYTES` default `20971520` (max encoded value-document size; Couchbase's hard
  per-document ceiling is 20 MiB). A value whose encoded document exceeds this is rejected up front,
  before any backend write, with a clean `ServerOperationException` — so an oversized value never
  reaches Couchbase and never updates the region's keyset. In a `putAll` it is a per-key failure (the
  under-limit entries still persist). Set `0` to disable the check and rely on the backend to reject.
- `CONNECTION_IDLE_TIMEOUT_SECONDS` default `300` (close a connection with no read/write activity
  for this long; `0` disables idle reaping)
- `MAX_CONNECTIONS` default `0` (max concurrent client connections; new connections beyond this
  are rejected and closed; `0` means unlimited)
- `FIRST_REQUEST_TIMEOUT_SECONDS` default `10` (a connection must complete its handshake and first
  request within this long or it is closed; unlike the idle timeout this is not reset by trickled
  bytes, so it bounds slowloris-style connections; `0` disables it)
- `HANDLER_MAX_PENDING_TASKS` default `10000` (per-handler-thread queue bound; once full, further
  requests are shed and the connection closed, instead of letting the backlog grow unbounded;
  `0` means unbounded)
- `TLS_ENABLED` default `false` (terminate TLS on the Geode listener)
- `TLS_KEYSTORE_PATH` / `TLS_KEYSTORE_PASSWORD` / `TLS_KEYSTORE_TYPE` (server keystore; type default
  `PKCS12`; path+password required when `TLS_ENABLED=true`)
- `TLS_CLIENT_AUTH` default `none` (`require` enables mutual TLS / client-certificate auth)
- `TLS_TRUSTSTORE_PATH` / `TLS_TRUSTSTORE_PASSWORD` / `TLS_TRUSTSTORE_TYPE` (truststore for verifying
  client certs; required when `TLS_CLIENT_AUTH=require`)
- `TLS_PROTOCOLS` default `TLSv1.3,TLSv1.2` (comma-separated enabled-protocol allowlist for the Geode
  listener + health HTTPS; legacy SSLv3 / TLS 1.0 / 1.1 excluded; narrow to `TLSv1.3` to require 1.3)
- `TLS_CIPHERS` default unset (optional comma-separated cipher-suite allowlist; unset = provider strong
  defaults)
- `CB_TLS_ENABLED` default `false` (TLS to Couchbase; also implied by a `couchbases://` connstr)
- `CB_TLS_CERT_PATH` (PEM cert to trust for the Couchbase connection)
- `CB_TLS_VERIFY_HOSTNAME` default `true` (verify the Couchbase host against its cert; disable only
  for self-signed certs whose SAN does not match the host)
- `HEALTH_TLS_ENABLED` default `false` (serve the health/admin endpoints over HTTPS, using the same
  server keystore as `TLS_KEYSTORE_*`)
- `HEALTH_BIND_ADDRESS` (interface the health/admin server binds to; unset/blank = all interfaces.
  Set to e.g. `127.0.0.1` or an internal address to restrict exposure)

Geode clients connect to a TLS-enabled shim with `ssl-enabled-components=server` and a truststore
trusting the shim certificate. See `docs/SECURITY.md` and the `protogemcouch-tls` service in
`docker-compose.yml`.

Example:

```bash
export CB_CONNSTR=couchbase://127.0.0.1
export CB_USERNAME=Administrator
export CB_PASSWORD=password
export CB_BUCKET=test
export CB_SCOPE=_default
export CB_COLLECTION=_default
export SHIM_PORT=40405
export HEALTH_PORT=8081
```

---

## Backend (Couchbase) failure behavior

The shim distinguishes a legitimate **miss** from an infrastructure **failure**:

- A missing document (`get`, `containsKey`, `containsValueForKey`) or a region with no keys
  (`keySet`, `size`) returns a normal empty/absent result — this is correct and expected.
- An infrastructure failure (Couchbase unreachable, KV timeout, auth rejected, decode failure)
  is **not** masked as an empty result. The operation fails: it is recorded as an operation
  error and the request is terminated.

This matters because masking a failure as "empty" would let a Couchbase outage look to clients
like a cache that genuinely has no data, which can cause incorrect application decisions
(treating absent data as authoritative). Failing loudly is the safer behavior.

### What you will observe during a Couchbase outage

- Per-operation error counters rise: `protogemcouch_operation_errors_total` and
  `protogemcouch_request_errors_total` (visible on the Grafana dashboard and `/metrics`).
- Structured `repository_*_error` logs are emitted with the cause.
- By default the shim replies with a Geode **EXCEPTION** frame and keeps the connection open
  (validated against a live Geode client), so the client raises a `ServerOperationException` and can
  retry on the same connection. Set `ERROR_RESPONSE_MODE=close` to instead drop the connection on
  failure (the client then reconnects/retries).

### Operator actions

1. Confirm Couchbase health (cluster up, bucket reachable, credentials valid).
2. Check shim logs for `repository_*_error` events to identify the failing operation and cause.
3. Once Couchbase recovers, error rates should return to zero with no shim restart required.

---

## Incident response

### Severity

| Sev | Examples | Page? |
|---|---|---|
| **SEV1** | `ProtoGemCouchDown` (all replicas), `ProtoGemCouchBackendErrors` (Couchbase outage) — clients failing | yes, immediately |
| **SEV2** | `ProtoGemCouchHighErrorRate`, `ProtoGemCouchHighP99Latency`, `ProtoGemCouchRequestsShed`, `ProtoGemCouchConnectionsRejected` — degraded but serving | yes |
| **SEV3** | `ProtoGemCouchMalformedFrameSpike`, `ProtoGemCouchFirstRequestTimeouts` — abuse/misconfig, no user-facing impact | triage, no page |

### First response (any alert)

1. **Scope it.** Open the **ProtoGemCouch Observability** dashboard (Grafana): is it one replica or all? one operation or all? Confirm with `/ready` and `/live` per pod.
2. **Classify failure vs. miss.** Backend failures raise `protogemcouch_request_errors_total`; a genuine empty result does not (see "Backend failure behavior" above).
3. **Read the logs.** Grafana **Logs & Traces** dashboard (Loki), or `{container="protogemcouch-shim"} | logfmt | event="request_failed"` and the audit stream `{logger="protogemcouch.audit"}`.
4. **Read a trace** (if the tracing overlay/OTLP is on): a slow/failed operation's trace shows whether time/errors are in the shim or the `couchbase.*` backend span.
5. **Decide.** Stabilize first (scale out, shed, fail over Couchbase), then root-cause. The shim is stateless — a rolling restart is safe and loses no data (chaos-validated).

### Where to look

- **Metrics:** `/metrics` (Prometheus) and the Observability dashboard.
- **Logs:** Loki / `docker compose logs protogemcouch`; security events on the `protogemcouch.audit` stream.
- **Traces:** Jaeger / Grafana Explore (with the tracing overlay or an OTLP endpoint configured).
- **Health:** `GET /ready` (serving) and `/live` (process up) on the health port.

### Rollback

The image is pinned by tag/digest. To roll back, redeploy the previous good tag
(`docker.io/rhadaway14/protogemcouch:<prev>` — every release is tagged `vX.Y.Z`; see `CHANGELOG.md`)
and roll the Deployment. The shim is stateless, so rollback is a plain image swap + rolling restart.

---

## Incident playbooks

One per alert (`prometheus/protogemcouch-alerts.rules.yml`). Each: likely cause → diagnose → remediate.

### ProtoGemCouchDown — `up == 0` (SEV1)
- **Causes:** process crashed / OOM-killed, health port unreachable, image won't start, all replicas down.
- **Diagnose:** pod status / `docker ps`; `/live`; container logs for a startup `ConfigException` or stack trace; `kubectl describe`/`docker inspect` for OOMKilled.
- **Remediate:** restart the pod/container; if a config error, fix the env/secret and redeploy; if OOM, raise the memory limit; if it won't start, roll back to the last good image. Multi-replica + PDB should keep ≥1 serving during a rolling fix.

### ProtoGemCouchBackendErrors — sustained `request_errors` (SEV1)
- **Cause:** Couchbase unreachable / unhealthy / auth rejected / KV timeouts. See **Backend (Couchbase) failure behavior** above.
- **Diagnose:** Couchbase cluster health + bucket; shim `repository_*_error` logs for the cause; `CB_*` config (connstr, creds, TLS).
- **Remediate:** restore Couchbase (failover/rebalance/restart); fix credentials/TLS if rejected. Recovery is automatic — error rate returns to zero with **no shim restart** (chaos-validated). If timeouts persist under load, review `CB_KV_TIMEOUT_MS` and Couchbase capacity.

### ProtoGemCouchHighErrorRate — operation errors > 5% (SEV2)
- **Causes:** partial backend trouble, a specific failing operation, oversized values, or unsupported requests.
- **Diagnose:** dashboard error rate **by operation**; logs `event="request_failed"` for the error and opcode; check `ValueTooLargeException` (oversized values) and `protogemcouch_malformed_frames_total`.
- **Remediate:** address the dominant failing operation. Oversized values → confirm `CB_MAX_VALUE_BYTES` is intentional and the client isn't sending too-large values. Unsupported ops → check the compatibility contract (`docs/COMPATABILITY_MATRIX.md`).

### ProtoGemCouchHighP99Latency — p99 > 250ms (SEV2)
- **Causes:** Couchbase latency, handler-thread saturation, GC pressure, oversized payloads.
- **Diagnose:** a slow operation's **trace** (shim vs. `couchbase.*` span split); `protogemcouch_requests_shed_total` (queue saturation); JVM/GC and Couchbase latency.
- **Remediate:** if the backend span dominates → Couchbase capacity/indexing. If the shim dominates → raise `HANDLER_THREADS`, scale out replicas, or raise memory. Re-check against your SLO (tune the alert threshold to it).

### ProtoGemCouchConnectionsRejected — `MAX_CONNECTIONS` reached (SEV2)
- **Cause:** more concurrent client connections than the cap.
- **Diagnose:** `protogemcouch_connections_rejected_total` and current connections; audit stream `event="connection_rejected"`.
- **Remediate:** scale out replicas behind the LB; raise `MAX_CONNECTIONS` if the instance has headroom (watch memory). Confirm it isn't a client connection leak.

### ProtoGemCouchRequestsShed — handler queue saturated (SEV2)
- **Cause:** sustained load beyond handler-thread capacity (queue full → load shedding).
- **Diagnose:** `protogemcouch_requests_shed_total`; correlate with throughput and p99.
- **Remediate:** scale out; raise `HANDLER_THREADS` and/or `HANDLER_MAX_PENDING_TASKS` if the host has CPU headroom. Shedding is intentional back-pressure — fix capacity, don't disable it.

### ProtoGemCouchMalformedFrameSpike — bad frames rejected (SEV3)
- **Causes:** a misbehaving/incompatible client, a non-Geode client hitting the port, or probing/abuse.
- **Diagnose:** audit stream `{logger="protogemcouch.audit"} | logfmt | event="malformed_frame"` — `remote` (source) and `reason`/`offendingValue`.
- **Remediate:** if abuse/scanning, block the source at the network layer; if a real client, check its Geode version against the compatibility contract. The decoder rejects safely (fuzz-validated) — no shim action needed beyond addressing the source.

### ProtoGemCouchFirstRequestTimeouts — slowloris guard firing (SEV3)
- **Causes:** slow/half-open clients, an LB health check that connects without sending a request, or slowloris-style abuse.
- **Diagnose:** audit stream `event="connection_first_request_timeout"` + `remote`.
- **Remediate:** if it's an LB/monitor, point it at `/ready` (HTTP) instead of the Geode port; if abuse, block the source; tune `FIRST_REQUEST_TIMEOUT_SECONDS` only if legitimate clients are slow to send their first request.

---

## Common procedures

- **Restart / rolling restart.** Kubernetes: `kubectl rollout restart deploy/<release>-protogemcouch`. Compose: `docker compose restart protogemcouch`. The shim is stateless (data lives in Couchbase) and runs multiple replicas behind a PDB, so a rolling restart is zero-downtime and loses no data (chaos-validated).
- **Scale.** Adjust `replicaCount`/HPA (Helm) or run more instances behind the load balancer; the shim scales horizontally (cross-process-safe keyset, validated multi-replica).
- **Certificate rotation.** See `docs/SECURITY.md` → "Certificate rotation" (update the Secret + rolling restart; zero-downtime).
- **Config change.** Env-driven; apply via the ConfigMap/Secret and roll the Deployment (the chart's config checksum triggers the rollout).

---

## Support handoff

When escalating, capture:

- **Versions:** shim image tag/digest, Geode client version, Couchbase version.
- **Config:** the effective `CB_*` / `TLS_*` / `MAX_*` / `HANDLER_*` env (redact secrets).
- **Signals:** the firing alert(s); a metrics snapshot (`/metrics`); the relevant log window (Loki export or `docker compose logs`), especially `request_failed` / `repository_*_error` / audit events; and a representative slow/failed **trace ID** if tracing is on.
- **Scope:** which replicas/operations/clients are affected, and when it started.

References: `docs/OBSERVABILITY.md` (dashboards, metrics, LogQL, traces), `docs/SECURITY.md` (TLS/mTLS, audit, cert rotation), `docs/COMPATABILITY_MATRIX.md` (supported surface + non-goals), `CHANGELOG.md` (release/version), `docs/PRODUCTION_READINESS_PLAN.md`.