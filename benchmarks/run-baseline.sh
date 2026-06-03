#!/usr/bin/env bash
set -euo pipefail

PROFILES="${PROFILES:-read-heavy write-heavy bulk-heavy mixed metadata-heavy}"
CONCURRENCY="${BENCH_CONCURRENCY:-10}"
WARMUP_SECONDS="${BENCH_WARMUP_SECONDS:-15}"
DURATION_SECONDS="${BENCH_DURATION_SECONDS:-120}"
KEYSPACE="${BENCH_KEYSPACE:-1000}"
SEED_COUNT="${BENCH_SEED_COUNT:-1000}"
HOST_NAME="${BENCH_HOST:-127.0.0.1}"
SHIM_PORT="${BENCH_PORT:-40405}"
HEALTH_PORT="${HEALTH_PORT:-8081}"
REGION="${BENCH_REGION:-helloWorld}"
SKIP_VERIFY="${SKIP_VERIFY:-false}"
SKIP_DOCKER_RESTART="${SKIP_DOCKER_RESTART:-false}"

timestamp="$(date +%Y%m%d-%H%M%S)"
repo_root="$(pwd)"
result_root="${repo_root}/benchmarks/results/${timestamp}"

mkdir -p "${result_root}"

section() {
  echo
  echo "============================================================"
  echo "$1"
  echo "============================================================"
}

run_and_capture() {
  local command="$1"
  local output_file="$2"

  echo "Running: ${command}"
  bash -lc "${command}" 2>&1 | tee "${result_root}/${output_file}"
}

capture_text() {
  local command="$1"
  local output_file="$2"

  echo "Capturing: ${command} -> ${output_file}"
  set +e
  bash -lc "${command}" > "${result_root}/${output_file}" 2>&1
  set -e
}

curl_capture() {
  local url="$1"
  local output_file="$2"

  echo "Fetching: ${url} -> ${output_file}"
  curl -fs "${url}" > "${result_root}/${output_file}"
}

capture_profile_metrics() {
  local profile="$1"
  local phase="$2"

  curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics/json" "${profile}-${phase}-metrics.json"
  curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics" "${profile}-${phase}-metrics.prom"
}

section "ProtoGemCouch baseline run"
echo "Result directory: ${result_root}"

section "Capturing environment"
capture_text "git rev-parse --abbrev-ref HEAD" "git-branch.txt"
capture_text "git rev-parse HEAD" "git-commit.txt"
capture_text "git status --short" "git-status.txt"
capture_text "java -version" "java-version.txt"
capture_text "mvn -version" "maven-version.txt"
capture_text "docker version" "docker-version.txt"
capture_text "docker compose version" "docker-compose-version.txt"
capture_text "docker ps" "docker-ps-before.txt"

cat > "${result_root}/baseline-config.env" <<EOF
timestamp=${timestamp}
profiles=${PROFILES}
concurrency=${CONCURRENCY}
warmupSeconds=${WARMUP_SECONDS}
durationSeconds=${DURATION_SECONDS}
keyspace=${KEYSPACE}
seedCount=${SEED_COUNT}
hostName=${HOST_NAME}
shimPort=${SHIM_PORT}
healthPort=${HEALTH_PORT}
region=${REGION}
skipVerify=${SKIP_VERIFY}
skipDockerRestart=${SKIP_DOCKER_RESTART}
EOF

if [[ "${SKIP_VERIFY}" != "true" ]]; then
  section "Running tests"
  run_and_capture "mvn test" "mvn-test.txt"
  run_and_capture "mvn clean verify" "mvn-clean-verify.txt"
fi

if [[ "${SKIP_DOCKER_RESTART}" != "true" ]]; then
  section "Restarting Docker Compose stack"
  run_and_capture "docker compose down -v" "docker-compose-down.txt"
  run_and_capture "mvn clean package -DskipTests" "mvn-package-skip-tests.txt"
  run_and_capture "docker compose up -d --build" "docker-compose-up.txt"

  echo "Waiting 20 seconds for services..."
  sleep 20
fi

section "Validating health endpoints"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/live" "live.json"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/ready" "ready.json"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics/json" "initial-metrics.json"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics" "initial-metrics.prom"

for profile in ${PROFILES}; do
  section "Running benchmark profile: ${profile}"

  capture_profile_metrics "${profile}" "before"

  BENCH_PROFILE="${profile}" \
  BENCH_HOST="${HOST_NAME}" \
  BENCH_PORT="${SHIM_PORT}" \
  BENCH_REGION="${REGION}" \
  BENCH_CONCURRENCY="${CONCURRENCY}" \
  BENCH_WARMUP_SECONDS="${WARMUP_SECONDS}" \
  BENCH_DURATION_SECONDS="${DURATION_SECONDS}" \
  BENCH_KEYSPACE="${KEYSPACE}" \
  BENCH_SEED="true" \
  BENCH_SEED_COUNT="${SEED_COUNT}" \
  BENCH_PROGRESS_SECONDS="15" \
  mvn -q exec:java -Dexec.mainClass=com.protogemcouch.benchmark.ConcurrentBenchmarkRunner \
    2>&1 | tee "${result_root}/${profile}-benchmark-output.txt"

  capture_profile_metrics "${profile}" "after"

  capture_text "docker logs protogemcouch-shim --tail 500" "${profile}-shim-logs-tail.txt"
  capture_text "docker stats --no-stream protogemcouch-shim protogemcouch-couchbase" "${profile}-docker-stats.txt"
done

section "Final capture"
capture_text "docker ps" "docker-ps-after.txt"
capture_text "docker logs protogemcouch-shim --tail 1000" "final-shim-logs-tail.txt"
capture_text "docker stats --no-stream protogemcouch-shim protogemcouch-couchbase" "final-docker-stats.txt"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics/json" "final-metrics.json"
curl_capture "http://${HOST_NAME}:${HEALTH_PORT}/metrics" "final-metrics.prom"

section "Baseline run complete"
echo "Artifacts saved to: ${result_root}"
