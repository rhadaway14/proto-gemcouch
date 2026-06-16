#!/usr/bin/env bash
#
# Automated performance-regression gate. Runs the concurrency benchmark against a running shim and
# fails (non-zero exit) if throughput, tail latency, or error count cross the committed thresholds in
# scripts/perf-baseline.env. Intended to catch a gross performance regression before a release tag.
#
# Assumes the stack is already up (e.g. `docker compose up -d --build`) and the shaded jar is built
# (`mvn -DskipTests package`).
#
# Usage:
#   ./scripts/perf-gate.sh
#   PERF_DURATION_SECONDS=30 ./scripts/perf-gate.sh   # override any baseline.env value via env
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/perf-baseline.env"

HOST=${PERF_HOST:-127.0.0.1}
SHIM_PORT=${PERF_SHIM_PORT:-40405}
HEALTH_PORT=${PERF_HEALTH_PORT:-8081}
JAR=${PERF_JAR:-$ROOT_DIR/target/protogemcouch.jar}
REGION=${PERF_REGION:-perfgate$RANDOM}

if [ ! -f "$JAR" ]; then
    echo "perf-gate: jar not found at $JAR (build it with: mvn -DskipTests package)" >&2
    exit 2
fi

echo "perf-gate: waiting for shim readiness at http://$HOST:$HEALTH_PORT/ready"
ready=0
for _ in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://$HOST:$HEALTH_PORT/ready" 2>/dev/null)" = "200" ]; then
        ready=1
        break
    fi
    sleep 2
done
if [ "$ready" -ne 1 ]; then
    echo "perf-gate: shim did not become ready" >&2
    exit 2
fi

echo "perf-gate: running benchmark (profile=$PERF_PROFILE concurrency=$PERF_CONCURRENCY warmup=${PERF_WARMUP_SECONDS}s measured=${PERF_DURATION_SECONDS}s)"
OUTPUT=$(BENCH_HOST="$HOST" BENCH_PORT="$SHIM_PORT" BENCH_REGION="$REGION" BENCH_PROFILE="$PERF_PROFILE" \
    BENCH_CONCURRENCY="$PERF_CONCURRENCY" BENCH_WARMUP_SECONDS="$PERF_WARMUP_SECONDS" \
    BENCH_DURATION_SECONDS="$PERF_DURATION_SECONDS" BENCH_KEYSPACE="$PERF_KEYSPACE" \
    BENCH_SEED_COUNT="$PERF_SEED_COUNT" \
    java -cp "$JAR" com.protogemcouch.benchmark.ConcurrentBenchmarkRunner 2>/dev/null)

RESULT=$(echo "$OUTPUT" | grep -E '^PERF_RESULT ' | tail -1)
if [ -z "$RESULT" ]; then
    echo "perf-gate: benchmark produced no PERF_RESULT line (run failed)" >&2
    echo "$OUTPUT" | tail -20 >&2
    exit 2
fi
echo "perf-gate: $RESULT"

ops=$(echo "$RESULT" | sed -nE 's/.*ops_per_sec=([0-9.]+).*/\1/p')
errors=$(echo "$RESULT" | sed -nE 's/.*errors=([0-9]+).*/\1/p')
p99=$(echo "$RESULT" | sed -nE 's/.*max_p99_ms=([0-9.]+).*/\1/p')

fail=0
if awk "BEGIN{exit !($ops < $PERF_MIN_OPS_PER_SEC)}"; then
    echo "perf-gate: FAIL throughput $ops ops/sec is below floor $PERF_MIN_OPS_PER_SEC" >&2
    fail=1
fi
if awk "BEGIN{exit !($p99 > $PERF_MAX_P99_MS)}"; then
    echo "perf-gate: FAIL worst p99 ${p99}ms is above ceiling ${PERF_MAX_P99_MS}ms" >&2
    fail=1
fi
if [ "${errors:-0}" -gt "$PERF_MAX_ERRORS" ]; then
    echo "perf-gate: FAIL $errors operation errors (max allowed $PERF_MAX_ERRORS)" >&2
    fail=1
fi

if [ "$fail" -eq 0 ]; then
    echo "perf-gate: PASS (ops_per_sec=$ops >= $PERF_MIN_OPS_PER_SEC, max_p99_ms=$p99 <= $PERF_MAX_P99_MS, errors=$errors)"
    exit 0
fi
echo "perf-gate: FAIL — see thresholds in scripts/perf-baseline.env" >&2
exit 1
