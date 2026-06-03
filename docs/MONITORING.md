# ProtoGemCouch Monitoring Stack

This document describes the bundled Prometheus + Grafana monitoring stack and how it is
wired to the shim. Everything is provisioned as code, so a single `docker compose up`
brings up a working, pre-connected dashboard with no manual setup.

For the underlying metric definitions, see [OBSERVABILITY.md](OBSERVABILITY.md).

---

## What is included

| Service | Image | Port | Purpose |
|---|---|---|---|
| `protogemcouch` | built from `Dockerfile` | 40405 / 8081 | The shim. Exposes `/metrics` on 8081. |
| `couchbase` | `couchbase:enterprise-7.6.2` | 8091-8094 / 11210 | Backend store. |
| `prometheus` | `prom/prometheus:v2.54.1` | 9090 | Scrapes the shim every 5s and stores time series. |
| `grafana` | `grafana/grafana:11.3.0` | 3000 | Dashboards over Prometheus. |

---

## How it is wired (automation)

There is no manual clicking. The connections are provisioned from version-controlled files:

```text
prometheus/prometheus.yml                            scrape job -> protogemcouch:8081/metrics
grafana/provisioning/datasources/datasource.yml      adds the Prometheus datasource (uid "prometheus")
grafana/provisioning/dashboards/provider.yml         loads dashboards from /var/lib/grafana/dashboards
grafana/dashboards/protogemcouch-observability.json  the dashboard model
```

Flow:

```text
shim /metrics  --scrape-->  Prometheus  --datasource-->  Grafana dashboard
```

Because Grafana auto-discovers the datasource and dashboard on startup, the dashboard is
live the moment the stack is healthy and the shim has received traffic.

---

## Quick start

### One command (recommended)

```bash
./scripts/observability-up.sh
```

This rebuilds the shim jar, starts the whole stack, waits for readiness, drives ~30s of
benchmark traffic so the panels have data, and prints the URLs.

Skip the traffic step if you want to drive it with your own Geode client:

```bash
./scripts/observability-up.sh --skip-traffic
```

### Manual

```bash
mvn clean package -DskipTests
docker compose up -d --build
```

Then open the URLs below.

---

## URLs

| What | URL |
|---|---|
| Grafana dashboard | http://127.0.0.1:3000/d/protogemcouch-observability |
| Prometheus targets | http://127.0.0.1:9090/targets |
| Shim Prometheus metrics | http://127.0.0.1:8081/metrics |

Grafana allows anonymous read-only viewing. To edit, log in with `admin` / `admin`.

Confirm Prometheus is scraping the shim: the `protogemcouch` target on the Prometheus
`/targets` page should be `UP`.

---

## Dashboard panels

The **ProtoGemCouch Observability** dashboard has an `Operation` filter variable (multi-select,
defaults to All) and the following panels:

```text
Request rate (total)        single-stat req/s
Error rate (total)          single-stat req/s (red when > 0)
Active connections          opened - closed
Unknown opcodes (total)     red when > 0

Request rate by operation   per-operation throughput
Error rate by operation     per-operation errors (stacked)

p95 latency by operation    from the latency histogram
p99 latency by operation    from the latency histogram
Overall latency p50/p95/p99 aggregate across selected operations
Response throughput         bytes/s out, by operation

Max response payload        largest response seen, by operation (watch GET_ALL / KEY_SET)
Request throughput          bytes/s in, by operation

Connection churn            opened/s vs closed/s
Handshake & unknown-opcode rate   plus request-error rate
```

The latency panels use the Prometheus histogram added in
`protogemcouch_operation_latency_seconds`, e.g.:

```promql
histogram_quantile(0.95, sum by (operation, le) (rate(protogemcouch_operation_latency_seconds_bucket[$__rate_interval])))
```

---

## Editing the dashboard

The dashboard provider has `allowUiUpdates: true`, so you can edit panels live in Grafana.
To persist changes back to the repo, export the dashboard JSON (Share -> Export -> Save to
file) and overwrite `grafana/dashboards/protogemcouch-observability.json`. Keep `"uid"` and
`"title"` stable so the provisioned dashboard is updated in place rather than duplicated.

---

## Tear down

```bash
docker compose down -v
```

The `-v` flag also removes the `prometheus_data` and `grafana_data` volumes, resetting all
stored metrics and Grafana state.
