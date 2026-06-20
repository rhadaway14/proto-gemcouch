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

### Registry metrics (in-memory state)

Sampled **gauges** for the shim's live in-memory registries (read at scrape time), plus a counter for
PDX-registry cap rejections:

```text
protogemcouch_pdx_types                  # distinct PDX types currently registered
protogemcouch_pdx_enums                  # distinct PDX enums currently registered
protogemcouch_active_transactions        # client transactions currently buffered (in flight)
protogemcouch_subscription_feeds         # open server->client subscription feed channels
protogemcouch_registered_interests       # total registered interests across clients/regions
protogemcouch_registered_cqs             # total registered continuous queries across clients
protogemcouch_durable_clients            # durable subscription clients currently retained
protogemcouch_durable_queue_depth        # total queued (undelivered) events across durable clients
protogemcouch_pdx_registry_rejected_total  # PDX type/enum registrations rejected for hitting the cap
```

Useful for:

```text
spotting unbounded growth of the PDX type/enum registry (a client registering excessive distinct types)
tracking transaction / subscription / CQ load
catching a durable client falling behind (durable_queue_depth climbing) before it exhausts the queue
confirming the optional PDX registry cap is engaging (pdx_registry_rejected_total > 0)
```

The PDX type/enum registry is unbounded by default; set `MAX_PDX_TYPES` / `MAX_PDX_ENUMS` to cap it.
When a registration is rejected at the cap, the shim increments
`protogemcouch_pdx_registry_rejected_total` and emits a `pdx_registry_cap_exceeded` event on the
`protogemcouch.audit` stream. Alerts for these (`ProtoGemCouchPdxRegistryRejections`,
`ProtoGemCouchDurableQueueBacklog`) ship in `prometheus/protogemcouch-alerts.rules.yml`, and the
"PDX & subscription registry sizes" / "Transactions, feeds & durable queue" panels are on the
ProtoGemCouch Observability dashboard.

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

## Provisioned dashboards

Grafana auto-loads these dashboards (folder **ProtoGemCouch**), from `grafana/dashboards/`:

- **ProtoGemCouch Observability** — Prometheus metrics (request/error rates, latency percentiles,
  byte sizes, connections) by operation.
- **ProtoGemCouch Host Metrics** — host/OS metrics from `node_exporter` (CPU busy %, memory used %,
  load average, network throughput, disk I/O utilization, filesystem used %), with a per-host
  `instance` template variable. Locally it shows the single `node-exporter` container; on the
  multi-host capacity rig every shim / Couchbase / load-generator host runs `node_exporter` and is
  listed as a Prometheus target, so the dashboard attributes the capacity ceiling to a specific
  resource on a specific host.
- **ProtoGemCouch Couchbase** — backend metrics from Couchbase Server's built-in Prometheus endpoint
  (`/metrics` on 8091): KV operations/sec by type, command latency p50/p99, memory used, current
  items, connections + node CPU, the **disk-write queue depth**, and **errors / OOM / cache-miss**
  panels. The queue-depth and OOM panels are the signals that reveal when Couchbase (not the shim) is
  the capacity ceiling. It has a per-`bucket` template variable. The scrape uses the local dev
  credentials; on the capacity rig use a Prometheus `basic_auth` credentials file / secret per node.
- **ProtoGemCouch Logs & Traces** — built on the Loki and Jaeger datasources: log volume by level,
  completed operations and failed-request / malformed-frame rates derived from the logs, an audit-event
  breakdown, a **PDX registry cap rejections by kind** panel (the rate of `pdx_registry_cap_exceeded`
  audit events split by registry `kind` — types vs enums), live shim + audit log panels, and a
  recent-traces table (the traces table is populated only with the tracing overlay up; click a trace to
  open its span tree in Explore). It has a `container` variable to switch between the shim instances.

## Recommended dashboard panels

For custom dashboards, useful Grafana panels include:

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

A ready-to-use Prometheus alerting ruleset ships at `prometheus/protogemcouch-alerts.rules.yml`, loaded
by the bundled Prometheus (`rule_files:` in `prometheus/prometheus.yml`). The stack also ships an
**Alertmanager** (Compose `alertmanager` service, port 9093, config `alertmanager/alertmanager.yml`);
Prometheus forwards fired alerts to it (the `alerting:` block), and it routes them **by severity**
(`critical` → its own receiver with a shorter repeat interval, `warning`, `info`), with an inhibit rule
that suppresses downstream warnings/info when `ProtoGemCouchDown` is firing. The shipped receivers are
integration-free sinks so the stack runs without secrets — uncomment a Slack / PagerDuty / email /
webhook integration per receiver to deliver notifications. Validate routing with
`amtool check-config alertmanager/alertmanager.yml`.

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

## Log aggregation (Loki)

The shim's logs are structured **logfmt** (`event=… key=value …`), including the dedicated
`protogemcouch.audit` security stream. The observability stack ships them to **Loki** via **Promtail**
(which discovers the shim/Couchbase containers over the Docker socket and tails their output), and
**Grafana** has a provisioned **Loki** datasource — so logs and metrics/traces sit side by side.

Promtail lifts the SLF4J `level` and `logger` to labels and stores the logfmt body as the line, so
LogQL can parse the fields directly:

```logql
{container="protogemcouch-shim"}                                  # all shim logs
{container="protogemcouch-shim"} | logfmt | event="request_completed"   # completed ops
{container="protogemcouch-shim"} | logfmt | event="request_failed"      # failures (with error=…)
{logger="protogemcouch.audit"}                                     # the security audit stream
{logger="protogemcouch.audit"} | logfmt | event="malformed_frame"  # rejected frames
sum by (operation) (count_over_time(
  {container="protogemcouch-shim"} | logfmt | event="request_completed" [5m]))  # ops by operation
{container="protogemcouch-shim", level="ERROR"}                    # errors only
```

It comes up with the rest of the stack (`docker compose up -d` / `scripts/observability-up.sh`); Loki
is at `http://localhost:3100` and is queryable from Grafana (`http://localhost:3000`). Configs:
`loki/loki-config.yml`, `loki/promtail-config.yml`.

---

## Distributed tracing

The shim emits OpenTelemetry traces: a span per Geode operation (`geode.<OPERATION>`, e.g. `geode.PUT`,
`geode.QUERY`) with the Couchbase backend call nested under it (`couchbase.<op>` — `couchbase.get`,
`couchbase.putAll`, …). A trace therefore shows how much of an operation's latency was the shim vs. the
backend — the breakdown that metrics alone don't give. Failed operations record the exception and set
the span status to ERROR.

Each operation span carries `geode.opcode`, `geode.operation`, `geode.tx_id`, and `geode.parts`
attributes. When a request is rejected because a PDX type/enum registration would exceed the configured
registry cap (`MAX_PDX_TYPES` / `MAX_PDX_ENUMS`), the span is additionally tagged
`protogemcouch.pdx_registry_cap_exceeded=true`, so a rejected registration is queryable in traces —
not just visible in the `pdx_registry_cap_exceeded` audit event and the
`protogemcouch_pdx_registry_rejected_total` counter.

**Off by default.** Tracing is only initialized when configured via the standard `OTEL_*` environment;
otherwise the tracer is a no-op with no measurable overhead. Note: the Geode wire protocol carries no
trace context, so spans are rooted at the shim (per-request), not continued from the client.

| Env var | Purpose |
|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP collector endpoint (e.g. `http://jaeger:4317`); setting it turns tracing on |
| `OTEL_TRACES_EXPORTER` | `otlp` (default when an endpoint is set), or `none` to disable |
| `OTEL_SERVICE_NAME` | service name in the tracing backend (e.g. `protogemcouch`) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` (4317) or `http/protobuf` (4318) |
| `OTEL_SDK_DISABLED` | `true` forces tracing off regardless of the above |

Try it with the opt-in overlay (adds a Jaeger backend, turns on export, and provisions a Jaeger
datasource in Grafana — without changing the default trace-free stack):

```bash
docker compose -f docker-compose.yml -f docker-compose.tracing.yml up -d --build
# drive traffic, then view traces either in:
#   the Jaeger UI         -> http://localhost:16686   (service "protogemcouch")
#   Grafana (Explore)     -> http://localhost:3000    (Jaeger datasource, alongside Prometheus + Loki)
```

With the overlay up, traces sit next to metrics and logs in Grafana; "Logs for this span" jumps to the
Loki logs around that span's time window.

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
