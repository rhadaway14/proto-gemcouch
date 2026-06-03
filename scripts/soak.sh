#!/usr/bin/env bash
#
# Sustained-load soak runner. Drives a long mixed-profile workload against a running shim and
# samples server metrics and container memory at a fixed interval, so latency stability, error
# growth, connection balance, and memory growth can be observed over time.
#
# Assumes the stack is already up (e.g. `docker compose up -d --build`). Prints a per-sample table
# and a closing stability summary.
#
# Usage:
#   ./scripts/soak.sh
#   ./scripts/soak.sh --duration 600 --concurrency 16 --sample-interval 30 --profile mixed

# Note: errexit is intentionally NOT set. This is a long-running measurement loop; a transient
# sampling hiccup (a momentarily slow curl or docker stats) must not abort the soak.
set -uo pipefail

DURATION=300
WARMUP=10
CONCURRENCY=16
SAMPLE_INTERVAL=30
PROFILE=mixed
KEYSPACE=2000
SEED_COUNT=2000
HOST=127.0.0.1
SHIM_PORT=40405
HEALTH_PORT=8081
SHIM_CONTAINER=protogemcouch-shim

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration) DURATION="$2"; shift 2;;
        --warmup) WARMUP="$2"; shift 2;;
        --concurrency) CONCURRENCY="$2"; shift 2;;
        --sample-interval) SAMPLE_INTERVAL="$2"; shift 2;;
        --profile) PROFILE="$2"; shift 2;;
        --keyspace) KEYSPACE="$2"; shift 2;;
        --host) HOST="$2"; shift 2;;
        --shim-port) SHIM_PORT="$2"; shift 2;;
        --health-port) HEALTH_PORT="$2"; shift 2;;
        *) echo "Unknown option: $1" >&2; exit 1;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

metrics_url="http://${HOST}:${HEALTH_PORT}/metrics"

# Read a single global (label-less) counter/gauge value from /metrics.
metric() {
    curl -fs "$metrics_url" 2>/dev/null | awk -v n="$1" '$1==n {print $2; exit}'
}

# Sum a labelled counter family (e.g. operation requests across all operations).
metric_sum() {
    curl -fs "$metrics_url" 2>/dev/null | awk -v n="$1" 'index($0, n"{")==1 {s+=$2} END {printf "%.0f", s+0}'
}

shim_mem() {
    docker stats --no-stream --format '{{.MemUsage}}' "$SHIM_CONTAINER" 2>/dev/null | awk '{print $1}'
}

echo "Waiting for shim readiness at ${HOST}:${HEALTH_PORT} ..."
for i in $(seq 1 60); do
    if curl -fs "http://${HOST}:${HEALTH_PORT}/ready" >/dev/null 2>&1; then break; fi
    sleep 2
done

echo "Starting soak: profile=${PROFILE} duration=${DURATION}s concurrency=${CONCURRENCY} sample=${SAMPLE_INTERVAL}s"

BENCH_PROFILE="$PROFILE" BENCH_HOST="$HOST" BENCH_PORT="$SHIM_PORT" BENCH_REGION=helloWorld \
BENCH_CONCURRENCY="$CONCURRENCY" BENCH_WARMUP_SECONDS="$WARMUP" BENCH_DURATION_SECONDS="$DURATION" \
BENCH_KEYSPACE="$KEYSPACE" BENCH_SEED=true BENCH_SEED_COUNT="$SEED_COUNT" BENCH_PROGRESS_SECONDS=30 \
mvn -q -o exec:java -Dexec.mainClass=com.protogemcouch.benchmark.ConcurrentBenchmarkRunner \
    > /tmp/soak-bench-output.txt 2>&1 &
BENCH_PID=$!

printf '\n%-9s %-10s %-8s %-8s %-7s %-7s %-7s %-12s\n' \
    "t(s)" "requests" "errors" "shed" "malform" "1stReqTO" "active" "shimMem"

start=$(date +%s)
prev_requests=0
first_active=""
last_active=""
last_requests=0
while kill -0 "$BENCH_PID" 2>/dev/null; do
    sleep "$SAMPLE_INTERVAL"
    now=$(date +%s); t=$((now - start))
    requests=$(metric_sum protogemcouch_operation_requests_total); requests=${requests:-0}
    errors=$(metric protogemcouch_request_errors_total); errors=${errors:-0}
    shed=$(metric protogemcouch_requests_shed_total); shed=${shed:-0}
    malformed=$(metric protogemcouch_malformed_frames_total); malformed=${malformed:-0}
    firstto=$(metric protogemcouch_connections_first_request_timeout_total); firstto=${firstto:-0}
    opened=$(metric protogemcouch_connections_opened_total); opened=${opened:-0}
    closed=$(metric protogemcouch_connections_closed_total); closed=${closed:-0}
    active=$(( ${opened%.*} - ${closed%.*} ))
    mem=$(shim_mem); mem=${mem:-n/a}
    printf '%-9s %-10s %-8s %-8s %-7s %-7s %-7s %-12s\n' \
        "$t" "$requests" "$errors" "$shed" "$malformed" "$firstto" "$active" "$mem"
    [[ -z "$first_active" ]] && first_active="$active"
    last_active="$active"; last_requests="$requests"
done

wait "$BENCH_PID" || true

echo
echo "============================================================"
echo "Soak summary (profile=${PROFILE}, ${DURATION}s)"
echo "============================================================"
echo "Total operation requests : ${last_requests}"
echo "Request errors           : $(metric protogemcouch_request_errors_total)"
echo "Requests shed            : $(metric protogemcouch_requests_shed_total)"
echo "Malformed frames         : $(metric protogemcouch_malformed_frames_total)"
echo "First-request timeouts   : $(metric protogemcouch_connections_first_request_timeout_total)"
echo "Active connections       : first=${first_active} last=${last_active}"
echo "Shim memory (last)       : $(shim_mem)"
echo
echo "--- Benchmark client-side latency summary ---"
tail -40 /tmp/soak-bench-output.txt
