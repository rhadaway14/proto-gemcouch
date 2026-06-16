# ProtoGemCouch Observability

## Purpose

This document describes the runtime observability endpoints exposed by ProtoGemCouch and how to use them for local validation, troubleshooting, Prometheus scraping, Grafana dashboards, and alerting.

ProtoGemCouch exposes observability endpoints on the configured health/admin HTTP port.

Default:

```text
HEALTH_PORT=8081
```

---

## Endpoints

| Endpoint | Format | Purpose |
|---|---|---|
| `/live` | JSON | Liveness check. Confirms the shim process is alive. |
| `/ready` | JSON | Readiness check. Confirms the shim is ready to receive traffic. |
| `/metrics/json` | JSON | Human/debug-friendly runtime metrics. |
| `/metrics` | Prometheus text | Prometheus/Grafana Agent/Alloy scrape endpoint. |

---

## Liveness

```bash
curl http://127.0.0.1:8081/live
```

Example response:

```json
{
  "endpoint": "live",
  "ok": true,
  "status": "ready"
}
```

Use this for container liveness checks.

---

## Readiness

```bash
curl http://127.0.0.1:8081/ready
```

Example response:

```json
{
  "endpoint": "ready",
  "ok": true,
  "status": "ready"
}
```

Use this for load-balancer readiness, startup validation, and smoke checks.

The shim should only be considered ready after:

```text
configuration is valid
repository connection is established
Netty server is bound to SHIM_PORT
```

---

## JSON metrics

```bash
curl http://127.0.0.1:8081/metrics/json
```

Example response:

```json
{
  "connections": {
    "opened": 12,
    "closed": 11
  },
  "requests": {
    "handshakeRequests": 12,
    "unknownOpcodes": 0,
    "requestErrors": 0
  },
  "operations": [
    {
      "opcode": 666,
      "operation": "GET_ALL",
      "requests": 20,
      "successes": 20,
      "errors": 0,
      "unknown": 0,
      "avgLatencyNs": 1189200,
      "minLatencyNs": 450000,
      "maxLatencyNs": 4033000,
      "lastLatencyNs": 900000,
      "lastError": null,
      "lastUpdatedEpochMs": 1779098400000
    }
  ]
}
```

Use `/metrics/json` for local debugging and quick inspection.

---

## Prometheus metrics

```bash
curl http://127.0.0.1:8081/metrics
```

Example output:

```text
# HELP protogemcouch_connections_opened_total Total client connections opened.
# TYPE protogemcouch_connections_opened_total counter
protogemcouch_connections_opened_total 12

# HELP protogemcouch_operation_requests_total Total requests by operation.
# TYPE protogemcouch_operation_requests_total counter
protogemcouch_operation_requests_total{opcode="666",operation="GET_ALL"} 20
```

Use `/metrics` for Prometheus, Grafana Agent, Grafana Alloy, or any compatible scraper.

---

## Metric families

### Connection metrics

```text
protogemcouch_connections_opened_total
protogemcouch_connections_closed_total
protogemcouch_connections_rejected_total
protogemcouch_idle_connections_closed_total
protogemcouch_connections_first_request_timeout_total
protogemcouch_requests_shed_total
```

Useful for:

```text
client churn
unexpected disconnects
load profile changes
connections rejected at the max-connections cap (capacity pressure)
connections reaped for being idle (dead peers / leaked connections)
connections closed for not completing a first request (slowloris-style behaviour)
requests shed because the handler queue was full (sustained overload / backend slowness)
```

A nonzero `protogemcouch_requests_shed_total` rate means the handler queue is saturating — the
backend is too slow or the shim is under-provisioned for the load; investigate backend latency and
consider raising `HANDLER_THREADS`. A nonzero `protogemcouch_connections_first_request_timeout_total`
rate suggests clients (or probes/abuse) opening connections without completing a request.

### Request metrics

```text
protogemcouch_handshake_requests_total
protogemcouch_unknown_opcodes_total
protogemcouch_request_errors_total
protogemcouch_malformed_frames_total
```

Useful for:

```text
new client behavior
unsupported protocol requests
error tracking
malformed / oversized frame rejection (possible corruption or abuse)
```

`protogemcouch_malformed_frames_total` increments whenever an inbound frame is rejected by
the decoder for violating the configured frame limits (oversized payload, too many parts,
or an out-of-bounds part length). A sustained nonzero rate indicates a misbehaving client,
protocol corruption, or a probe/abuse attempt; the offending connection is closed.

### Per-operation metrics

```text
protogemcouch_operation_requests_total{opcode="...",operation="..."}
protogemcouch_operation_successes_total{opcode="...",operation="..."}
protogemcouch_operation_errors_total{opcode="...",operation="..."}
protogemcouch_operation_unknown_total{opcode="...",operation="..."}
protogemcouch_operation_latency_avg_ns{opcode="...",operation="..."}
protogemcouch_operation_latency_min_ns{opcode="...",operation="..."}
protogemcouch_operation_latency_max_ns{opcode="...",operation="..."}
protogemcouch_operation_latency_last_ns{opcode="...",operation="..."}
protogemcouch_operation_request_bytes_total{opcode="...",operation="..."}
protogemcouch_operation_request_bytes_last{opcode="...",operation="..."}
protogemcouch_operation_request_bytes_max{opcode="...",operation="..."}
protogemcouch_operation_request_bytes_avg{opcode="...",operation="..."}
protogemcouch_operation_response_bytes_total{opcode="...",operation="..."}
protogemcouch_operation_response_bytes_last{opcode="...",operation="..."}
protogemcouch_operation_response_bytes_max{opcode="...",operation="..."}
protogemcouch_operation_response_bytes_avg{opcode="...",operation="..."}
protogemcouch_operation_last_updated_epoch_ms{opcode="...",operation="..."}
protogemcouch_operation_latency_seconds_bucket{opcode="...",operation="...",le="..."}
protogemcouch_operation_latency_seconds_sum{opcode="...",operation="..."}
protogemcouch_operation_latency_seconds_count{opcode="...",operation="..."}
```

The `protogemcouch_operation_latency_seconds` family is a true Prometheus histogram
(`# TYPE ... histogram`), so it supports `histogram_quantile()` for percentiles. Buckets
are cumulative, expressed in seconds, with bounds:

```text
0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, +Inf
```

Useful for:

```text
request rate by operation
success/error rate by operation
latency per operation
latency percentiles (p50/p95/p99) per operation
request payload size by operation
response payload size by operation
detecting risky operations such as GET_ALL, PUT_ALL, and keySetOnServer
```

---

## Recommended dashboard panels

Create Grafana panels for:

```text
request rate by operation
error rate by operation
average latency by operation
max latency by operation
request bytes by operation
response bytes by operation
unknown opcode count
connections opened and closed
handshake count
```

Recommended PromQL examples:

```promql
rate(protogemcouch_operation_requests_total[5m])
```

```promql
rate(protogemcouch_operation_errors_total[5m])
```

```promql
protogemcouch_operation_latency_avg_ns / 1000000
```

```promql
protogemcouch_operation_latency_max_ns / 1000000
```

p95 latency per operation (seconds), from the histogram:

```promql
histogram_quantile(0.95, sum by (operation, le) (rate(protogemcouch_operation_latency_seconds_bucket[5m])))
```

p99 latency per operation (seconds):

```promql
histogram_quantile(0.99, sum by (operation, le) (rate(protogemcouch_operation_latency_seconds_bucket[5m])))
```

```promql
rate(protogemcouch_operation_request_bytes_total[5m])
```

```promql
rate(protogemcouch_operation_response_bytes_total[5m])
```

```promql
protogemcouch_operation_response_bytes_max{operation="GET_ALL"}
```

```promql
increase(protogemcouch_unknown_opcodes_total[15m])
```

---

## Alerting rules

A ready-to-use Prometheus alerting ruleset ships at `prometheus/protogemcouch-alerts.rules.yml` and is
loaded by the bundled Prometheus (`rule_files:` in `prometheus/prometheus.yml`; mounted by the Compose
`prometheus` service). Wire an `alerting:` / Alertmanager target to route them in a real deployment.

| Alert | Fires when | Severity |
|---|---|---|
| `ProtoGemCouchDown` | `up{job="protogemcouch"} == 0` for 1m (shim unreachable) | critical |
| `ProtoGemCouchBackendErrors` | sustained `protogemcouch_request_errors_total` rate (e.g. Couchbase outage) | critical |
| `ProtoGemCouchHighErrorRate` | operation error ratio > 5% for 5m | warning |
| `ProtoGemCouchHighP99Latency` | aggregate p99 > 250ms (SLO) for 10m | warning |
| `ProtoGemCouchConnectionsRejected` | connections refused — `MAX_CONNECTIONS` reached | warning |
| `ProtoGemCouchRequestsShed` | handler queue saturated, requests shed | warning |
| `ProtoGemCouchMalformedFrameSpike` | > 10 malformed frames in 10m (probing/abuse) | warning |
| `ProtoGemCouchFirstRequestTimeouts` | slowloris first-request timeouts | info |

Thresholds are sensible defaults — tune to your SLO/capacity. The rules are validated in CI-style with
`promtool check rules` and unit tests in `prometheus/protogemcouch-alerts_test.yml`
(`promtool test rules`). The raw PromQL building blocks for custom alerts:

### Any request errors

```promql
increase(protogemcouch_request_errors_total[5m]) > 0
```

### Unknown opcode detected

```promql
increase(protogemcouch_unknown_opcodes_total[5m]) > 0
```

### High GET_ALL latency

```promql
protogemcouch_operation_latency_max_ns{operation="GET_ALL"} / 1000000 > 500
```

Tune thresholds based on workload.

### Large GET_ALL response

```promql
protogemcouch_operation_response_bytes_max{operation="GET_ALL"} > 10485760
```

Tune thresholds based on workload and expected batch sizes.

### Large keySetOnServer response

Depending on operation naming in the current opcode map, use the `KEY_SET` operation label:

```promql
protogemcouch_operation_response_bytes_max{operation="KEY_SET"} > 10485760
```

### No successful requests

```promql
sum(rate(protogemcouch_operation_successes_total[10m])) == 0
```

Use carefully in low-traffic environments.

---

## Docker Compose validation

Start the stack:

```bash
docker compose up -d --build
```

Validate endpoints:

```bash
curl http://127.0.0.1:8081/live
curl http://127.0.0.1:8081/ready
curl http://127.0.0.1:8081/metrics/json
curl http://127.0.0.1:8081/metrics
```

Run the integration suite:

```bash
mvn clean verify
```

---

## Current limitations

The metrics registry is currently in-process and resets on restart.

Operation latency is now also exposed as a true Prometheus histogram
(`protogemcouch_operation_latency_seconds`), so percentiles are available via
`histogram_quantile()`. The avg/min/max/last latency gauges and the byte-size metrics
remain summary-style values maintained in memory, useful for operational visibility and
trend correlation but not themselves histograms.

Request byte metrics are estimated from the decoded `GemFrame` after the raw Netty frame has already been parsed. They are intended for trend analysis and operation correlation, not exact packet accounting.

Response byte metrics are captured centrally from outbound Netty `ByteBuf` writes and attributed to the operation currently being handled on the channel.

Future enhancements may include:

```text
Couchbase repository latency separation
serialization latency separation
handler-level last-error labels or structured event counters
configurable histogram bucket bounds
```
