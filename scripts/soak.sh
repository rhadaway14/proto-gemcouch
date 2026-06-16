#!/usr/bin/env bash
#
# Sustained-load soak runner. Drives a long mixed-profile workload against a running shim, samples
# server metrics and container memory at a fixed interval, and at the end renders a STABILITY VERDICT:
# the soak is about staying healthy over time (no error growth, no leak, no degradation), not raw
# throughput. Exits non-zero on a failing verdict so it can act as a soak gate.
#
# Assumes the stack is already up (e.g. `docker compose up -d --build`).
#
# Usage:
#   ./scripts/soak.sh
#   ./scripts/soak.sh --duration 3600 --concurrency 16 --sample-interval 30 --profile mixed
#
# Endurance note: a real endurance soak runs for HOURS against a dedicated Couchbase + separate load
# generators (see docs/SOAK_RESULTS.md). A short local run is a stability smoke, not a capacity number.

# errexit intentionally OFF: a transient sampling hiccup (slow curl / docker stats) must not abort a
# long measurement loop.
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

# Stability-verdict thresholds (override via env). Defaults are conservative leak/degradation guards.
SOAK_MAX_ERRORS="${SOAK_MAX_ERRORS:-0}"            # total request errors allowed
SOAK_MAX_SHED="${SOAK_MAX_SHED:-0}"                # total shed requests allowed (0 = not overloading)
SOAK_MAX_MEM_GROWTH_PCT="${SOAK_MAX_MEM_GROWTH_PCT:-25}"   # last vs early shim memory (leak guard)
SOAK_MIN_THROUGHPUT_RATIO="${SOAK_MIN_THROUGHPUT_RATIO:-0.6}"  # last-third vs first-third throughput
SOAK_MAX_CONN_GROWTH="${SOAK_MAX_CONN_GROWTH:-50}" # active-connection growth (connection-leak guard)
# Throughput stability is only a reliable gate on dedicated infra (a co-located dev box / shared CI
# runner shows contention-driven decline). By default it is reported as a WARNING; set this true on a
# dedicated rig to make it part of the pass/fail verdict.
SOAK_FAIL_ON_THROUGHPUT="${SOAK_FAIL_ON_THROUGHPUT:-false}"

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

metric() { curl -fs "$metrics_url" 2>/dev/null | awk -v n="$1" '$1==n {print $2; exit}'; }
metric_sum() { curl -fs "$metrics_url" 2>/dev/null | awk -v n="$1" 'index($0, n"{")==1 {s+=$2} END {printf "%.0f", s+0}'; }
shim_mem_raw() { docker stats --no-stream --format '{{.MemUsage}}' "$SHIM_CONTAINER" 2>/dev/null | awk '{print $1}'; }

# Parse a docker mem string ("256MiB", "1.5GiB", "512KiB") to MiB.
mem_to_mib() {
    local v="$1" num unit
    num=$(printf '%s' "$v" | grep -oE '^[0-9.]+'); unit=$(printf '%s' "$v" | grep -oE '[A-Za-z]+$')
    [[ -z "$num" ]] && { echo ""; return; }
    case "$unit" in
        GiB|GB) awk -v n="$num" 'BEGIN{printf "%.1f", n*1024}';;
        MiB|MB) awk -v n="$num" 'BEGIN{printf "%.1f", n}';;
        KiB|KB) awk -v n="$num" 'BEGIN{printf "%.3f", n/1024}';;
        *) echo "";;
    esac
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
first_active=""; last_active=""; last_requests=0
req_samples=(); mem_samples=()
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
    mem_raw=$(shim_mem_raw); mem_mib=$(mem_to_mib "$mem_raw")
    printf '%-9s %-10s %-8s %-8s %-7s %-7s %-7s %-12s\n' \
        "$t" "$requests" "$errors" "$shed" "$malformed" "$firstto" "$active" "${mem_raw:-n/a}"
    req_samples+=("${requests%.*}")
    [[ -n "$mem_mib" ]] && mem_samples+=("$mem_mib")
    [[ -z "$first_active" ]] && first_active="$active"
    last_active="$active"; last_requests="$requests"
done

wait "$BENCH_PID" || true

final_errors=$(metric protogemcouch_request_errors_total); final_errors=${final_errors%.*}; final_errors=${final_errors:-0}
final_shed=$(metric protogemcouch_requests_shed_total); final_shed=${final_shed%.*}; final_shed=${final_shed:-0}

echo
echo "============================================================"
echo "Soak summary (profile=${PROFILE}, ${DURATION}s)"
echo "============================================================"
echo "Total operation requests : ${last_requests}"
echo "Request errors           : ${final_errors}"
echo "Requests shed            : ${final_shed}"
echo "Malformed frames         : $(metric protogemcouch_malformed_frames_total)"
echo "First-request timeouts   : $(metric protogemcouch_connections_first_request_timeout_total)"
echo "Active connections       : first=${first_active} last=${last_active}"
echo "Shim memory (last)       : $(shim_mem_raw)"
echo
echo "--- Benchmark client-side latency summary ---"
tail -40 /tmp/soak-bench-output.txt

# ---------------------------------------------------------------- stability verdict
echo
echo "============================================================"
echo "Stability verdict"
echo "============================================================"
fail=0; reasons=()

# 1. Errors / shedding within bounds.
if [[ "${final_errors:-0}" -gt "$SOAK_MAX_ERRORS" ]]; then
    fail=1; reasons+=("request errors ${final_errors} > ${SOAK_MAX_ERRORS}")
fi
if [[ "${final_shed:-0}" -gt "$SOAK_MAX_SHED" ]]; then
    fail=1; reasons+=("requests shed ${final_shed} > ${SOAK_MAX_SHED}")
fi

# 2. Connection leak: active connections must not grow unbounded.
conn_growth=$(( ${last_active:-0} - ${first_active:-0} ))
if [[ "$conn_growth" -gt "$SOAK_MAX_CONN_GROWTH" ]]; then
    fail=1; reasons+=("active connections grew by ${conn_growth} > ${SOAK_MAX_CONN_GROWTH} (leak?)")
fi

# 3. Memory leak: last sample vs the earliest sample.
mem_verdict="n/a"
if [[ "${#mem_samples[@]}" -ge 2 ]]; then
    mem_first="${mem_samples[0]}"; mem_last="${mem_samples[${#mem_samples[@]}-1]}"
    mem_growth_pct=$(awk -v a="$mem_first" -v b="$mem_last" 'BEGIN{ if(a>0) printf "%.1f", (b-a)/a*100; else print "0" }')
    mem_verdict="${mem_first}MiB -> ${mem_last}MiB (${mem_growth_pct}%)"
    if awk -v g="$mem_growth_pct" -v m="$SOAK_MAX_MEM_GROWTH_PCT" 'BEGIN{exit !(g > m)}'; then
        fail=1; reasons+=("shim memory grew ${mem_growth_pct}% > ${SOAK_MAX_MEM_GROWTH_PCT}% (leak?)")
    fi
fi

# 4. Throughput degradation: last-third vs first-third per-interval throughput. Computed over the
# steady-state samples only (the first interval is dropped — it includes seeding + warmup). Reported
# as a warning unless SOAK_FAIL_ON_THROUGHPUT=true (it is contention-sensitive off dedicated infra).
tput_verdict="n/a"
n="${#req_samples[@]}"
if [[ "$n" -ge 7 ]]; then
    deltas=(); for ((i=2; i<n; i++)); do deltas+=( $(( ${req_samples[i]} - ${req_samples[i-1]} )) ); done
    nd=${#deltas[@]}; third=$(( nd / 3 )); [[ "$third" -lt 1 ]] && third=1
    early_sum=0; for ((i=0; i<third; i++)); do early_sum=$(( early_sum + ${deltas[i]} )); done
    late_sum=0;  for ((i=nd-third; i<nd; i++)); do late_sum=$(( late_sum + ${deltas[i]} )); done
    ratio=$(awk -v e="$early_sum" -v l="$late_sum" 'BEGIN{ if(e>0) printf "%.2f", l/e; else print "1" }')
    tput_verdict="early=${early_sum} late=${late_sum} ratio=${ratio}"
    if awk -v r="$ratio" -v m="$SOAK_MIN_THROUGHPUT_RATIO" 'BEGIN{exit !(r < m)}'; then
        if [[ "$SOAK_FAIL_ON_THROUGHPUT" == "true" ]]; then
            fail=1; reasons+=("throughput degraded: steady-state ratio ${ratio} < ${SOAK_MIN_THROUGHPUT_RATIO}")
        else
            reasons+=("WARNING throughput steady-state ratio ${ratio} < ${SOAK_MIN_THROUGHPUT_RATIO} (not gated; set SOAK_FAIL_ON_THROUGHPUT=true on dedicated infra)")
        fi
    fi
fi

echo "errors=${final_errors} shed=${final_shed} conn_growth=${conn_growth} mem=${mem_verdict} throughput[${tput_verdict}]"
echo "SOAK_VERDICT $([[ $fail -eq 0 ]] && echo PASS || echo FAIL) errors=${final_errors} shed=${final_shed} conn_growth=${conn_growth} mem_growth_pct=${mem_growth_pct:-na} throughput_ratio=${ratio:-na}"
for r in "${reasons[@]:-}"; do [[ -n "$r" ]] && echo "  - $r"; done
if [[ "$fail" -eq 0 ]]; then
    echo "RESULT: PASS — stable over ${DURATION}s"
    exit 0
fi
echo "RESULT: FAIL"
exit 1
