#!/usr/bin/env bash
#
# Stepped capacity sweep against the shim NLB: run the benchmark at increasing concurrency, parse each
# PERF_RESULT, print a throughput / p99 / errors table, and flag the knee — the first step where errors
# appear, p99 crosses the SLO, or the throughput gain over the previous step falls below a threshold.
#
# The keyspace is seeded once (first step), then reused. Run the SAME script on each load-gen host
# simultaneously to drive aggregate load, and keep adding load-gen hosts until total throughput stops
# rising — only then is the measured ceiling real and not client-bound. Watch the Grafana "Host
# Metrics" and "Couchbase" dashboards to see WHICH resource saturates at the knee.
#
# Env overrides:
#   CONCURRENCY_STEPS   space-separated concurrency levels (default "8 16 32 64 128 256")
#   P99_SLO_MS          p99 latency SLO in ms; crossing it marks the knee (default 25)
#   KNEE_GAIN_PCT       min %% throughput gain over the previous step to keep climbing (default 10)
#   BENCH_DURATION_SECONDS, BENCH_PROFILE, BENCH_KEYSPACE, ...  passed through to run-benchmark.sh
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$SCRIPT_DIR/run-benchmark.sh"

STEPS="${CONCURRENCY_STEPS:-8 16 32 64 128 256}"
P99_SLO_MS="${P99_SLO_MS:-25}"
KNEE_GAIN_PCT="${KNEE_GAIN_PCT:-10}"

printf '%-12s %-16s %-12s %-10s\n' "concurrency" "ops_per_sec" "p99_ms" "errors"
printf '%-12s %-16s %-12s %-10s\n' "-----------" "-----------" "------" "------"

prev=0
knee=""
first=1
for c in $STEPS; do
  # Seed the keyspace only on the first step; reuse it thereafter.
  if [ "$first" = 1 ]; then seed=true; else seed=false; fi
  first=0

  out="$(BENCH_CONCURRENCY="$c" BENCH_SEED="$seed" "$RUN" 2>/dev/null | grep '^PERF_RESULT' | tail -1)"
  ops="$(sed -nE 's/.*ops_per_sec=([0-9.]+).*/\1/p' <<<"$out")"
  p99="$(sed -nE 's/.*max_p99_ms=([0-9.]+).*/\1/p' <<<"$out")"
  errs="$(sed -nE 's/.*errors=([0-9]+).*/\1/p' <<<"$out")"

  printf '%-12s %-16s %-12s %-10s\n' "$c" "${ops:-?}" "${p99:-?}" "${errs:-?}"

  if [ -z "$knee" ] && [ -n "$ops" ]; then
    over_slo="$(awk -v p="${p99:-0}" -v s="$P99_SLO_MS" 'BEGIN{print (p>s)?1:0}')"
    low_gain="$(awk -v a="$ops" -v b="$prev" -v k="$KNEE_GAIN_PCT" \
      'BEGIN{ if(b<=0){print 0} else {g=(a-b)/b*100; print (g<k)?1:0} }')"
    if [ "${errs:-0}" -gt 0 ] || [ "$over_slo" = 1 ] || { [ "$prev" != 0 ] && [ "$low_gain" = 1 ]; }; then
      knee="$c"
    fi
  fi
  prev="${ops:-$prev}"
done

echo
if [ -n "$knee" ]; then
  echo "KNEE near concurrency=$knee  (errors>0, or p99>${P99_SLO_MS}ms, or throughput gain <${KNEE_GAIN_PCT}% vs prior step)"
else
  echo "No knee within steps [$STEPS] — raise CONCURRENCY_STEPS and/or add load-gen hosts (you may be client-bound)."
fi
echo "Now read the Grafana 'ProtoGemCouch Host Metrics' + 'ProtoGemCouch Couchbase' dashboards to attribute the knee to a resource (shim CPU? Couchbase disk queue / OOM? network?)."
