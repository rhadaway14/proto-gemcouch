#!/usr/bin/env bash
#
# Run ONE benchmark phase from this load-gen host against the shim NLB, by reusing the published image
# (the ConcurrentBenchmarkRunner main lives in the same fat jar). Emits the runner's machine-readable
# `PERF_RESULT ops_per_sec=… total=… errors=… max_p99_ms=…` line.
#
# Reads NLB_DNS / SHIM_IMAGE from /etc/pgc-rig.env (written by the load-gen cloud-init). Every BENCH_*
# knob can be overridden via the environment.
#
set -euo pipefail

# shellcheck disable=SC1091
[ -f /etc/pgc-rig.env ] && . /etc/pgc-rig.env

TARGET_HOST="${TARGET_HOST:-${NLB_DNS:?set NLB_DNS (in /etc/pgc-rig.env) or TARGET_HOST}}"
IMAGE="${SHIM_IMAGE:-docker.io/rhadaway14/protogemcouch:latest}"

exec docker run --rm --network host \
  -e BENCH_HOST="$TARGET_HOST" \
  -e BENCH_PORT="${BENCH_PORT:-40405}" \
  -e BENCH_REGION="${BENCH_REGION:-helloWorld}" \
  -e BENCH_PROFILE="${BENCH_PROFILE:-mixed}" \
  -e BENCH_CONCURRENCY="${BENCH_CONCURRENCY:-32}" \
  -e BENCH_WARMUP_SECONDS="${BENCH_WARMUP_SECONDS:-10}" \
  -e BENCH_DURATION_SECONDS="${BENCH_DURATION_SECONDS:-60}" \
  -e BENCH_KEYSPACE="${BENCH_KEYSPACE:-100000}" \
  -e BENCH_SEED="${BENCH_SEED:-true}" \
  -e BENCH_SEED_COUNT="${BENCH_SEED_COUNT:-100000}" \
  -e BENCH_PROGRESS_SECONDS="${BENCH_PROGRESS_SECONDS:-15}" \
  --entrypoint java "$IMAGE" \
  -cp /app/protogemcouch.jar com.protogemcouch.benchmark.ConcurrentBenchmarkRunner
