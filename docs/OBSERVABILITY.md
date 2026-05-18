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
```

Useful for:

```text
client churn
unexpected disconnects
load profile changes
```

### Request metrics

```text
protogemcouch_handshake_requests_total
protogemcouch_unknown_opcodes_total
protogemcouch_request_errors_total
```

Useful for:

```text
new client behavior
unsupported protocol requests
error tracking
```

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
```

Useful for:

```text
request rate by operation
success/error rate by operation
latency per operation
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

## Suggested alerts

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

The current latency and byte-size metrics are summary-style values maintained in memory. They are useful for operational visibility and trend correlation, but they are not full Prometheus histograms.

Request byte metrics are estimated from the decoded `GemFrame` after the raw Netty frame has already been parsed. They are intended for trend analysis and operation correlation, not exact packet accounting.

Response byte metrics are captured centrally from outbound Netty `ByteBuf` writes and attributed to the operation currently being handled on the channel.

Future enhancements may include:

```text
Prometheus histogram buckets
Couchbase repository latency separation
serialization latency separation
handler-level last-error labels or structured event counters
```
