#!/usr/bin/env bash
#
# Bring up the full ProtoGemCouch observability stack (shim + Couchbase + Prometheus + Grafana),
# wait for readiness, optionally generate traffic, and print the dashboard URL.
#
# This is the one-command path to a working, pre-wired monitoring stack:
#   - rebuilds the shim jar so the container runs current code
#   - starts every service via Docker Compose
#   - waits for the shim to report ready
#   - (by default) drives a short benchmark so the dashboard has live data
#   - prints the Grafana / Prometheus / metrics URLs
#
# Usage:
#   ./scripts/observability-up.sh
#   ./scripts/observability-up.sh --skip-traffic
#   ./scripts/observability-up.sh --traffic-seconds 60 --profile read-heavy

set -euo pipefail

SKIP_TRAFFIC=false
TRAFFIC_SECONDS=30
HOST=127.0.0.1
HEALTH_PORT=8081
SHIM_PORT=40405
GRAFANA_PORT=3000
PROMETHEUS_PORT=9090
BENCH_PROFILE=mixed

usage() {
    grep '^#' "$0" | grep -v '#!/usr/bin/env' | sed 's/^# \{0,1\}//'
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-traffic) SKIP_TRAFFIC=true; shift;;
        --traffic-seconds) TRAFFIC_SECONDS="$2"; shift 2;;
        --host) HOST="$2"; shift 2;;
        --health-port) HEALTH_PORT="$2"; shift 2;;
        --shim-port) SHIM_PORT="$2"; shift 2;;
        --grafana-port) GRAFANA_PORT="$2"; shift 2;;
        --prometheus-port) PROMETHEUS_PORT="$2"; shift 2;;
        --profile) BENCH_PROFILE="$2"; shift 2;;
        -h|--help) usage;;
        *) echo "Unknown option: $1" >&2; exit 1;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

section() {
    echo
    echo "============================================================"
    echo "$1"
    echo "============================================================"
}

section "Building shim jar (so the container runs current code)"
# The Dockerfile copies target/protogemcouch.jar; build it before the image build.
mvn -q clean package -DskipTests

section "Starting stack (Couchbase + shim + Prometheus + Grafana)"
docker compose up -d --build

section "Waiting for the shim to become ready"
ready=false
for i in $(seq 1 40); do
    if curl -fs "http://${HOST}:${HEALTH_PORT}/ready" >/dev/null 2>&1; then
        echo "Shim is ready after ${i} attempt(s)."
        ready=true
        break
    fi
    sleep 3
done
if [[ "$ready" != true ]]; then
    echo "Shim did not become ready in time. Check: docker compose logs protogemcouch" >&2
    exit 1
fi

if [[ "$SKIP_TRAFFIC" != true ]]; then
    section "Generating ${TRAFFIC_SECONDS}s of '${BENCH_PROFILE}' traffic so the dashboard has data"
    BENCH_PROFILE="$BENCH_PROFILE" \
    BENCH_HOST="$HOST" \
    BENCH_PORT="$SHIM_PORT" \
    BENCH_REGION=helloWorld \
    BENCH_CONCURRENCY=8 \
    BENCH_WARMUP_SECONDS=3 \
    BENCH_DURATION_SECONDS="$TRAFFIC_SECONDS" \
    BENCH_KEYSPACE=500 \
    BENCH_SEED=true \
    BENCH_SEED_COUNT=500 \
    BENCH_PROGRESS_SECONDS=10 \
    mvn -q exec:java -Dexec.mainClass=com.protogemcouch.benchmark.ConcurrentBenchmarkRunner \
        || echo "Benchmark exited non-zero (stack is still up)."
else
    echo "Skipping traffic generation (--skip-traffic). Point a Geode client at ${HOST}:${SHIM_PORT} to populate metrics."
fi

section "Observability stack is up"
echo "Grafana dashboard : http://${HOST}:${GRAFANA_PORT}/d/protogemcouch-observability  (anonymous viewer; admin/admin to edit)"
echo "Prometheus        : http://${HOST}:${PROMETHEUS_PORT}/targets"
echo "Shim metrics      : http://${HOST}:${HEALTH_PORT}/metrics"
echo
echo "Tear down with:    docker compose down -v"
