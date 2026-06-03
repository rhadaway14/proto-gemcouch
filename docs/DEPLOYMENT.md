# ProtoGemCouch Deployment

## Purpose

This document explains how to build and run ProtoGemCouch as a Docker container for `RawShimServer`.

ProtoGemCouch runs a Geode-compatible shim port and a separate health/admin port.

---

## Prerequisites

You need:

- Docker installed
- Maven installed for local builds
- Java compatible with the project build
- a reachable Couchbase cluster, or the included Docker Compose Couchbase service
- valid Couchbase credentials
- target bucket, scope, and collection already created, or automated initialization enabled through Docker Compose

---

## Runtime configuration

ProtoGemCouch uses environment variables at startup.

Required:

- `CB_CONNSTR`
- `CB_USERNAME`
- `CB_PASSWORD`
- `CB_BUCKET`
- `CB_SCOPE`
- `CB_COLLECTION`

Optional:

- `SHIM_PORT`  
  Default: `40405`
- `HEALTH_PORT`  
  Default: `8081`

`HEALTH_PORT` must be different from `SHIM_PORT`.

---

## Example `.env`

For local Docker Compose:

```env
CB_CONNSTR=couchbase://couchbase
CB_USERNAME=Administrator
CB_PASSWORD=password
CB_BUCKET=test
CB_SCOPE=_default
CB_COLLECTION=_default
SHIM_PORT=40405
HEALTH_PORT=8081
```

For an external Couchbase cluster:

```env
CB_CONNSTR=couchbase://your-couchbase-host
CB_USERNAME=your-user
CB_PASSWORD=your-password
CB_BUCKET=your-bucket
CB_SCOPE=your-scope
CB_COLLECTION=your-collection
SHIM_PORT=40405
HEALTH_PORT=8081
```

Do not commit real credentials to source control.

---

## Build the jar locally

```bash
mvn clean package
```

The Dockerfile expects the packaged jar at:

```text
target/protogemcouch.jar
```

---

## Build the Docker image

```bash
docker build -t protogemcouch:local .
```

---

## Run with Docker

```bash
docker run --rm \
  --name protogemcouch-shim \
  --env-file .env \
  -p 40405:40405 \
  -p 8081:8081 \
  protogemcouch:local
```

Expected exposed ports:

```text
40405 -> Geode client shim protocol port
8081  -> health/admin HTTP port
```

---

## Run with Docker Compose

For local development and integration testing, Docker Compose can start:

```text
Couchbase
Couchbase initialization container
ProtoGemCouch shim
```

Run:

```bash
docker compose up -d --build
```

Check container status:

```bash
docker ps
```

Follow shim logs:

```bash
docker logs -f protogemcouch-shim
```

Stop and remove local state:

```bash
docker compose down -v
```

---

## Run on Kubernetes (Helm)

A Helm chart is provided at `charts/protogemcouch`. It deploys the shim as a multi-replica,
horizontally scalable Deployment (the shim is stateless apart from Couchbase-backed state, validated
by the multi-replica integration test) with a Service, ConfigMap, Secret, probes, resource
limits, optional HPA, and a PodDisruptionBudget.

Install with chart-managed credentials:

```bash
helm install pgc charts/protogemcouch \
  --set couchbase.connectionString=couchbase://my-couchbase \
  --set couchbase.username=Administrator \
  --set couchbase.password='<password>'
```

Or with an externally managed Secret (Vault / external-secrets / sealed-secrets) that contains keys
`cb-username` and `cb-password`:

```bash
helm install pgc charts/protogemcouch \
  --set couchbase.connectionString=couchbase://my-couchbase \
  --set couchbase.existingSecret=my-couchbase-secret
```

Credentials are mounted as files and read via `CB_USERNAME_FILE` / `CB_PASSWORD_FILE`, so they never
appear in the container environment (see `docs/SECURITY.md`).

Key values (`charts/protogemcouch/values.yaml`):

```text
replicaCount                  number of shim replicas (or set autoscaling.enabled)
image.repository / image.tag  the published shim image
couchbase.*                   connection string, bucket/scope/collection, credentials / existingSecret
shim.*                        tunables (error mode, handler threads, connection limits)
resources                     requests/limits
autoscaling                   HPA (enabled, min/max, target CPU)
podDisruptionBudget           minAvailable for safe rollouts/drains
```

Geode clients connect to the Service on the Geode port (default `40405`). For TLS, supply a keystore
(see the TLS variables in `docs/RUNBOOK.md` / `docs/SECURITY.md`).

Validated with `helm lint` and `helm template`. (A live-cluster smoke test is a follow-up; it
requires a cluster.)

---

## Health, readiness, and metrics endpoints

The health/admin port is separate from the Geode shim protocol port.

Default:

```text
HEALTH_PORT=8081
```

Available endpoints:

| Endpoint | Format | Purpose |
|---|---|---|
| `/live` | JSON | Liveness check. Confirms the shim process is alive. |
| `/ready` | JSON | Readiness check. Confirms the shim is ready to receive Geode client traffic. |
| `/metrics/json` | JSON | Human/debug-friendly runtime metrics. |
| `/metrics` | Prometheus text | Prometheus, Grafana Agent, Grafana Alloy, or compatible scraper endpoint. |

Liveness:

```bash
curl -fs http://127.0.0.1:8081/live
```

Readiness:

```bash
curl -fs http://127.0.0.1:8081/ready
```

JSON metrics:

```bash
curl -fs http://127.0.0.1:8081/metrics/json
```

Prometheus metrics:

```bash
curl -fs http://127.0.0.1:8081/metrics
```

Expected behavior:

```text
/live returns success when the process is alive.
/ready returns success when configuration is valid, the repository is connected, and the shim server is bound.
/metrics/json returns current in-process runtime metrics as JSON.
/metrics returns Prometheus text format metrics.
```

Example `/metrics/json` categories:

```text
connections opened / closed
handshake request count
unknown opcode count
request error count
per-operation request / success / error / unknown counts
per-operation average / min / max / last latency
per-operation request byte totals / last / max / average
per-operation response byte totals / last / max / average
last error
last updated timestamp
```

Example Prometheus metric families:

```text
protogemcouch_connections_opened_total
protogemcouch_connections_closed_total
protogemcouch_handshake_requests_total
protogemcouch_unknown_opcodes_total
protogemcouch_request_errors_total
protogemcouch_operation_requests_total
protogemcouch_operation_successes_total
protogemcouch_operation_errors_total
protogemcouch_operation_unknown_total
protogemcouch_operation_latency_avg_ns
protogemcouch_operation_latency_min_ns
protogemcouch_operation_latency_max_ns
protogemcouch_operation_latency_last_ns
protogemcouch_operation_request_bytes_total
protogemcouch_operation_request_bytes_last
protogemcouch_operation_request_bytes_max
protogemcouch_operation_request_bytes_avg
protogemcouch_operation_response_bytes_total
protogemcouch_operation_response_bytes_last
protogemcouch_operation_response_bytes_max
protogemcouch_operation_response_bytes_avg
protogemcouch_operation_last_updated_epoch_ms
```

---

## Client connection

A Geode Java client should connect to the shim host and shim port.

Example:

```java
ClientCache cache = new ClientCacheFactory()
        .addPoolServer("127.0.0.1", 40405)
        .setPoolSubscriptionEnabled(false)
        .set("log-level", "warn")
        .create();

Region<String, Object> region = cache
        .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
        .create("helloWorld");
```

For PDX read-serialized validation:

```java
ClientCache cache = new ClientCacheFactory()
        .addPoolServer("127.0.0.1", 40405)
        .setPoolSubscriptionEnabled(false)
        .setPdxReadSerialized(true)
        .set("log-level", "warn")
        .create();
```

---

## Smoke test

After startup, run the current test suite:

```bash
mvn clean verify
```

Latest expected integration result:

```text
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

For fast response-writer coverage only:

```bash
mvn -Dtest=GemResponseWriterTest test
```


For observability endpoint unit coverage:

```bash
mvn -Dtest=MetricsRegistryTest,HealthHttpServerTest test
```

Sample scrape configs are available at:

```text
deploy/prometheus/protogemcouch-scrape.yml
deploy/grafana-alloy/protogemcouch-scrape.river
```

Observability usage is documented in:

```text
docs/OBSERVABILITY.md
```

---

## Current validated behavior

The current deployment has been validated for:

```text
put / get
putAll / getAll
remove
containsKey / containsKeyOnServer
containsValueForKey
sizeOnServer
keySetOnServer
PDX / PdxInstance round-tripping
large key-set and bulk operation boundary handling
```

Large collection boundary coverage includes:

```text
>127 keys / entries
>252 keys / entries
```

---

## Operational notes

Current observability:

```text
structured logs
startup validation logs
handler-level operation logs
periodic structured metrics snapshots
connection open/close counters
handshake request counters
per-opcode request/success/error/unknown counters
per-opcode average/min/max/last latency metrics
per-opcode request byte-size metrics
per-opcode response byte-size metrics
/live endpoint
/ready endpoint
/metrics/json endpoint
Prometheus-format /metrics endpoint
```

Known observability limitations:

```text
metrics are in-process and reset on restart
latency metrics are summary-style values, not Prometheus histograms
repository-level latency is not yet separated from handler/protocol latency
serialization latency is not yet separated from total operation latency
request byte metrics are decoded-frame estimates, not exact network packet accounting
response byte metrics are captured from outbound Netty buffers and may not include lower-level TCP/TLS framing overhead
```

---

## Rollback guidance

For local Docker Compose deployments:

```bash
docker compose down -v
git checkout <known-good-commit>
mvn clean package
docker compose up -d --build
```

For container-image deployments:

```text
redeploy the previous known-good image tag
verify /ready
run a small Geode client smoke test
monitor shim logs for protocol or serialization errors
```

---

## Security notes

- Do not commit `.env` files containing real credentials.
- Use environment variables or secret management for credentials.
- Restrict network access to the shim protocol port.
- Use a private network path between the shim and Couchbase.
- Add TLS / transport hardening before broader deployment.
- Define shim-side client authentication before broader deployment.
