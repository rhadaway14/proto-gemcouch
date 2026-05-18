# ProtoGemCouch Benchmarks

This folder contains wrapper scripts for running repeatable ProtoGemCouch performance baseline passes.

The Java benchmark harness lives in:

```text
src/main/java/com/protogemcouch/benchmark/
```

or wherever the `com.protogemcouch.benchmark` package is located in the project.

Current harness entrypoint:

```text
com.protogemcouch.benchmark.ConcurrentBenchmarkRunner
```

## Scripts

```text
benchmarks/run-baseline.ps1
benchmarks/run-baseline.sh
```

Both scripts:

```text
capture git/build/environment metadata
optionally run mvn test
optionally run mvn clean verify
optionally restart Docker Compose
validate /live and /ready
capture /metrics/json and /metrics before and after each profile
run the Java benchmark harness for each profile
capture shim logs and docker stats
write artifacts to benchmarks/results/<timestamp>/
```

## PowerShell usage

Run the default baseline:

```powershell
.\benchmarks\run-baseline.ps1
```

Run a faster smoke baseline:

```powershell
.\benchmarks\run-baseline.ps1 `
  -Profiles @("read-heavy", "write-heavy") `
  -Concurrency 5 `
  -WarmupSeconds 5 `
  -DurationSeconds 30
```

Run without restarting Docker:

```powershell
.\benchmarks\run-baseline.ps1 -SkipDockerRestart
```

Run without Maven verification:

```powershell
.\benchmarks\run-baseline.ps1 -SkipVerify
```

## Bash usage

```bash
chmod +x benchmarks/run-baseline.sh
./benchmarks/run-baseline.sh
```

Override settings:

```bash
PROFILES="read-heavy write-heavy" \
BENCH_CONCURRENCY=5 \
BENCH_WARMUP_SECONDS=5 \
BENCH_DURATION_SECONDS=30 \
./benchmarks/run-baseline.sh
```

Skip Docker restart:

```bash
SKIP_DOCKER_RESTART=true ./benchmarks/run-baseline.sh
```

Skip Maven verification:

```bash
SKIP_VERIFY=true ./benchmarks/run-baseline.sh
```

## Output

Each run creates:

```text
benchmarks/results/<timestamp>/
```

Typical contents:

```text
baseline-config.json or baseline-config.env
git-branch.txt
git-commit.txt
git-status.txt
java-version.txt
maven-version.txt
docker-version.txt
docker-compose-version.txt
mvn-test.txt
mvn-clean-verify.txt
initial-metrics.json
initial-metrics.prom
<profile>-before-metrics.json
<profile>-before-metrics.prom
<profile>-benchmark-output.txt
<profile>-after-metrics.json
<profile>-after-metrics.prom
<profile>-shim-logs-tail.txt
<profile>-docker-stats.txt
final-metrics.json
final-metrics.prom
```

## Current benchmark profiles

Profiles are defined in `BenchmarkProfiles.java`.

Current profiles:

```text
read-heavy
write-heavy
bulk-heavy
mixed
metadata-heavy
```

## Notes

The current Java harness uses environment variables through `BenchmarkConfig.fromEnv()`.

Important variables:

```text
BENCH_PROFILE
BENCH_HOST
BENCH_PORT
BENCH_REGION
BENCH_CONCURRENCY
BENCH_WARMUP_SECONDS
BENCH_DURATION_SECONDS
BENCH_KEYSPACE
BENCH_SEED
BENCH_SEED_COUNT
BENCH_PROGRESS_SECONDS
```

Results should be summarized into:

```text
PERFORMANCE_RESULTS.md
```

The repeatable run process is documented in:

```text
PERFORMANCE_BASELINE.md
```
