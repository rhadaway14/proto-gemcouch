#!/usr/bin/env bash
#
# Self-driving failure-injection experiment. Each rig host runs this on boot (launched detached by its
# cloud-init when chaos_experiment=true) so the whole run is hands-off: no operator SSH, no manual
# commands. Results are uploaded to the experiment S3 bucket; the operator fetches them with the AWS
# CLI and reads the Prometheus timeseries for the window.
#
# Roles:
#   chaos-autorun.sh loadgen     drive sustained load against the shim NLB for the whole window
#   chaos-autorun.sh couchbase   wait out a warmup, then run the fault scenario on this (CB) host
#
# Both roles synchronize on the SAME external event — the shim NLB accepting connections on 40405
# (the NLB only routes to health-checked shims) — so they line up without inter-host messaging.
#
# Env (exported by cloud-init from Terraform):
#   NLB_DNS, RESULTS_BUCKET, SHIM_IMAGE, AWS_DEFAULT_REGION
#   CHAOS_LOAD_DURATION (s), CHAOS_WARMUP (s)
#   BENCH_PROFILE, BENCH_CONCURRENCY, BENCH_KEYSPACE
#   LOADGEN_INDEX (loadgen role only)
#
set -uo pipefail

ROLE="${1:?usage: chaos-autorun.sh <loadgen|couchbase>}"
NLB_DNS="${NLB_DNS:?}"
RESULTS_BUCKET="${RESULTS_BUCKET:?}"
SHIM_IMAGE="${SHIM_IMAGE:-docker.io/rhadaway14/protogemcouch:latest}"
CHAOS_LOAD_DURATION="${CHAOS_LOAD_DURATION:-1500}"
CHAOS_WARMUP="${CHAOS_WARMUP:-180}"
BENCH_PROFILE="${BENCH_PROFILE:-read-heavy}"
BENCH_CONCURRENCY="${BENCH_CONCURRENCY:-64}"
BENCH_KEYSPACE="${BENCH_KEYSPACE:-100000}"

ts()  { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log() { echo "[$(ts)] $*"; }
s3cp() { aws s3 cp "$1" "s3://$RESULTS_BUCKET/$2" --only-show-errors || true; }
marker() { echo "$(ts)" | aws s3 cp - "s3://$RESULTS_BUCKET/$1" --only-show-errors || true; }

# Wait until the shim NLB accepts connections (a routable, health-checked shim exists).
wait_for_shim() {
  log "waiting for shim NLB ${NLB_DNS}:40405 ..."
  for _ in $(seq 1 120); do
    if (exec 3<>"/dev/tcp/${NLB_DNS}/40405") 2>/dev/null; then exec 3>&- 3<&-; log "shim reachable"; return 0; fi
    sleep 5
  done
  log "shim never became reachable; aborting role=$ROLE"; return 1
}

run_loadgen() {
  local idx="${LOADGEN_INDEX:-0}" logf="/var/log/pgc-chaos-load.log"
  wait_for_shim || { marker "loadgen-$idx.FAILED"; return 1; }
  # Only loadgen-0 seeds the shared keyspace; others reuse it.
  local seed=false; [ "$idx" = "0" ] && seed=true
  log "loadgen-$idx: ${CHAOS_LOAD_DURATION}s of '${BENCH_PROFILE}' @ conc ${BENCH_CONCURRENCY} (seed=$seed)" | tee "$logf"

  docker run --rm --network host \
    -e BENCH_HOST="$NLB_DNS" -e BENCH_PORT=40405 -e BENCH_REGION=helloWorld \
    -e BENCH_PROFILE="$BENCH_PROFILE" -e BENCH_CONCURRENCY="$BENCH_CONCURRENCY" \
    -e BENCH_WARMUP_SECONDS=10 -e BENCH_DURATION_SECONDS="$CHAOS_LOAD_DURATION" \
    -e BENCH_KEYSPACE="$BENCH_KEYSPACE" -e BENCH_SEED="$seed" -e BENCH_SEED_COUNT="$BENCH_KEYSPACE" \
    -e BENCH_PROGRESS_SECONDS=15 \
    --entrypoint java "$SHIM_IMAGE" \
    -cp /app/protogemcouch.jar com.protogemcouch.benchmark.ConcurrentBenchmarkRunner \
    >>"$logf" 2>&1 &
  local pid=$!
  # Stream the log to S3 periodically so partial results survive even if the run is cut short.
  while kill -0 "$pid" 2>/dev/null; do s3cp "$logf" "loadgen-$idx.log"; sleep 30; done
  wait "$pid"; s3cp "$logf" "loadgen-$idx.log"
  marker "loadgen-$idx.DONE"
  log "loadgen-$idx: complete"
}

run_couchbase() {
  local logf="/var/log/pgc-chaos-faults.log"
  wait_for_shim || { marker "couchbase.FAILED"; return 1; }
  log "couchbase: warmup ${CHAOS_WARMUP}s before injecting faults (let load ramp + dashboards fill)" | tee "$logf"
  sleep "$CHAOS_WARMUP"
  # The fault-injection script self-heals every window and on EXIT; stream its timeline to S3.
  ( bash /opt/pgc/deploy/ec2/scripts/fault-injection.sh scenario 2>&1 | tee -a "$logf" ) &
  local pid=$!
  while kill -0 "$pid" 2>/dev/null; do s3cp "$logf" "faults.log"; sleep 20; done
  wait "$pid"; s3cp "$logf" "faults.log"
  marker "COMPLETE"
  log "couchbase: fault scenario complete; backend healed"
}

case "$ROLE" in
  loadgen)   run_loadgen ;;
  couchbase) run_couchbase ;;
  *) echo "unknown role: $ROLE" >&2; exit 2 ;;
esac
