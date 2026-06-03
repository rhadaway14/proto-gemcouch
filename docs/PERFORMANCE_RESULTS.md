# ProtoGemCouch Performance Results

## Purpose

This document records benchmark results for ProtoGemCouch using the Java concurrency harness against the current shim implementation.

These results are intended to establish and track baselines for:

- CRUD-style request performance
- bulk operation performance
- metadata operation performance
- concurrency behavior
- future regression comparison
- operational metrics validation through `/metrics/json` and `/metrics`

The repeatable process for running these benchmarks is documented separately in:

```text
PERFORMANCE_BASELINE.md
```

---

## Current baseline run

## Run: 2026-06-02 — robustness build baseline (feature/robustness)

Benchmark pass against the hardened build (all failure-mode robustness phases, with default config:
graceful EXCEPTION error mode, handler executor off the event loop with a bounded queue, idle
reaping, first-request deadline, and connection cap unlimited).

### Environment

- Target: `RawShimServer` at `127.0.0.1:40405` (Docker Compose: Couchbase enterprise 7.6.2 + shim)
- Harness: `ConcurrentBenchmarkRunner`, concurrency `16`, warmup `5s`, measured `30s`
- Keyspace `1000`, seeded; region `helloWorld`
- Single host (shim, Couchbase, and client co-located) — numbers are relative, not a capacity ceiling

### Throughput (measured phase, 0 errors across every profile)

| Profile | Ops/sec | Notes |
|---|---|---|
| read-heavy | 12,281 | GET/CONTAINS_KEY/GET_ALL |
| mixed | 7,010 | full CRUD + bulk + metadata mix |
| write-heavy | 5,966 | PUT/REMOVE dominant (each = upsert + keyset-metadata) |
| bulk-heavy | 4,200 | GET_ALL/PUT_ALL dominant |

### Per-operation latency (ms; measured phase)

read-heavy:

```text
GET          avg 1.22  p95 1.79  p99 2.47
CONTAINS_KEY avg 1.20  p95 1.76  p99 2.42
GET_ALL      avg 1.92  p95 2.75  p99 3.61
```

write-heavy:

```text
GET          avg 1.56  p95 2.28  p99 3.32
PUT          avg 2.80  p95 3.82  p99 5.07
REMOVE       avg 2.81  p95 3.82  p99 5.12
CONTAINS_KEY avg 1.64  p95 2.56  p99 3.49
PUT_ALL      avg 6.31  p95 8.08  p99 9.66
```

bulk-heavy:

```text
GET          avg 1.66  p95 2.47  p99 3.94
PUT          avg 3.02  p95 4.05  p99 5.62
GET_ALL      avg 2.78  p95 3.86  p99 5.57
PUT_ALL      avg 6.74  p95 8.48  p99 10.46
SIZE         avg 1.79  p95 2.59  p99 4.01
KEY_SET      avg 2.30  p95 3.27  p99 5.02
```

mixed:

```text
GET          avg 1.61  p95 2.41  p99 3.79
PUT          avg 2.80  p95 3.83  p99 5.44
REMOVE       avg 2.81  p95 3.85  p99 5.58
CONTAINS_KEY avg 1.66  p95 2.54  p99 3.97
GET_ALL      avg 2.66  p95 3.74  p99 5.37
PUT_ALL      avg 6.16  p95 7.88  p99 10.04
SIZE         avg 1.71  p95 2.48  p99 3.92
KEY_SET      avg 2.21  p95 3.16  p99 5.07
```

### Observations

- **Zero errors, zero shed, zero malformed frames** across all profiles — the hardening adds no
  measurable overhead on the happy path.
- Latency tiers reflect Couchbase round-trips per operation: reads (1 KV get) are fastest, writes
  (`PUT`/`REMOVE` = value upsert + keyset-metadata update) are ~2x, and bulk (`PUT_ALL`/`GET_ALL`,
  many sub-operations) are the slowest. `PUT_ALL` is the latency-dominant operation (~6 ms avg, p99
  ~10 ms) and the natural focus for future optimization (e.g. batched/parallel backend writes).
- Server-side histogram percentiles (`protogemcouch_operation_latency_seconds`, via Prometheus)
  tracked the client-side numbers and ran slightly lower (e.g. server p99: GET 2.4 ms, PUT_ALL
  10.0 ms), as expected since they exclude client and network overhead.

---

## Run: 2026-05-18 — current observability and serialization baseline

### Run artifact directory

```text
benchmarks/results/20260518-053634
```

### Command

```powershell
.\benchmarks\run-baseline.ps1 `
  -Profiles @("read-heavy", "write-heavy", "bulk-heavy", "mixed", "metadata-heavy") `
  -Concurrency 10 `
  -WarmupSeconds 15 `
  -DurationSeconds 120 `
  -SkipVerify
```

### Environment

- Benchmark client: Java concurrency harness
- Target: `RawShimServer`
- Host: `127.0.0.1:40405`
- Health/admin endpoint: `127.0.0.1:8081`
- Region: `helloWorld`
- Keyspace: `1000`
- Seed before run: `true`
- Seed count: `1000`
- Warmup: `15s`
- Measured duration: `120s`
- Progress reporting: `15s`
- Docker Compose restart before run: `true`
- Maven verification during this wrapper run: skipped by `-SkipVerify`

### Profiles tested

- `read-heavy`
- `write-heavy`
- `bulk-heavy`
- `mixed`
- `metadata-heavy`

### Artifact capture

For each profile, the baseline wrapper captured:

```text
<profile>-before-metrics.json
<profile>-before-metrics.prom
<profile>-benchmark-output.txt
<profile>-after-metrics.json
<profile>-after-metrics.prom
<profile>-shim-logs-tail.txt
<profile>-docker-stats.txt
```

The wrapper also captured:

```text
git branch / commit / status
Java version
Maven version
Docker version
Docker Compose version
initial / final metrics snapshots
initial / final shim logs
initial / final Docker stats
```

---

## Current executive summary

The 2026-05-18 run completed all five workload profiles with **0 benchmark errors**.

### Current high-level results

| Profile | Concurrency | Duration | Total operations | Successes | Errors | Throughput |
|---|---:|---:|---:|---:|---:|---:|
| `read-heavy` | 10 | 2m | 1,269,176 | 1,269,176 | 0 | 10,568.72 ops/sec |
| `write-heavy` | 10 | 2m | 600,187 | 600,187 | 0 | 4,997.35 ops/sec |
| `bulk-heavy` | 10 | 2m | 442,957 | 442,957 | 0 | 3,688.57 ops/sec |
| `mixed` | 10 | 2m | 738,862 | 738,862 | 0 | 6,152.36 ops/sec |
| `metadata-heavy` | 10 | 2m | 848,202 | 848,202 | 0 | 7,062.41 ops/sec |

### Current interpretation

ProtoGemCouch currently shows strong performance for:

- point reads
- point writes
- point removes
- server-side existence checks
- small `GET_ALL`
- small `PUT_ALL`
- current `SIZE`
- current `KEY_SET`

The most important change from the prior historical baseline is that `SIZE` and `KEY_SET` no longer appear to be hundreds-of-milliseconds operations in this run. In the current implementation and workload, both metadata operations were low-millisecond operations.

This means the previous conclusion that `SIZE` and `KEY_SET` are categorically slow metadata operations is now **superseded for the current implementation**, though they should still be monitored closely because their cost may grow with keyspace size, indexing strategy, repository implementation, and response size.

---

## Current results by profile

## Read-heavy

### Configuration

- Profile: `read-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`
- Keyspace: `1000`
- Seed count: `1000`

### Measured summary

- Total operations: **1,269,176**
- Successes: **1,269,176**
- Errors: **0**
- Throughput: **10,568.72 ops/sec**

### Latency highlights

| Operation | Successes | Errors | Avg | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| `GET` | 895,688 | 0 | 0.887 ms | 0.835 ms | 1.264 ms | 1.615 ms |
| `CONTAINS_KEY` | 224,108 | 0 | 0.874 ms | 0.823 ms | 1.250 ms | 1.603 ms |
| `GET_ALL` | 149,380 | 0 | 1.400 ms | 1.339 ms | 1.931 ms | 2.389 ms |

### Interpretation

The read-heavy profile is strong. Point reads and server-side existence checks remain sub-millisecond at p50 and stay below roughly 1.7 ms at p99. Small `GET_ALL` remains low-latency as well, with p99 under 2.4 ms.

---

## Write-heavy

### Configuration

- Profile: `write-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`
- Keyspace: `1000`
- Seed count: `1000`

### Measured summary

- Total operations: **600,187**
- Successes: **600,187**
- Errors: **0**
- Throughput: **4,997.35 ops/sec**

### Latency highlights

| Operation | Successes | Errors | Avg | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| `GET` | 89,679 | 0 | 1.152 ms | 1.085 ms | 1.642 ms | 2.282 ms |
| `PUT` | 330,904 | 0 | 2.099 ms | 2.005 ms | 2.878 ms | 3.708 ms |
| `REMOVE` | 89,793 | 0 | 2.106 ms | 2.013 ms | 2.893 ms | 3.718 ms |
| `CONTAINS_KEY` | 60,015 | 0 | 1.204 ms | 1.102 ms | 1.819 ms | 2.463 ms |
| `PUT_ALL` | 29,796 | 0 | 4.715 ms | 4.555 ms | 6.210 ms | 7.326 ms |

### Interpretation

Write-heavy performance is stable with zero errors. Single-key writes and removes are low-millisecond operations. `PUT_ALL` is more expensive than single-key mutation paths, as expected, but remains under 8 ms at p99 in this workload.

---

## Bulk-heavy

### Configuration

- Profile: `bulk-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`
- Keyspace: `1000`
- Seed count: `1000`

### Measured summary

- Total operations: **442,957**
- Successes: **442,957**
- Errors: **0**
- Throughput: **3,688.57 ops/sec**

### Latency highlights

| Operation | Successes | Errors | Avg | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| `GET` | 44,271 | 0 | 1.160 ms | 1.067 ms | 1.607 ms | 2.233 ms |
| `PUT` | 43,936 | 0 | 2.194 ms | 2.078 ms | 3.002 ms | 3.834 ms |
| `GET_ALL` | 177,599 | 0 | 1.879 ms | 1.781 ms | 2.632 ms | 3.393 ms |
| `PUT_ALL` | 133,056 | 0 | 4.913 ms | 4.729 ms | 6.457 ms | 7.507 ms |
| `SIZE` | 22,018 | 0 | 1.269 ms | 1.178 ms | 1.746 ms | 2.363 ms |
| `KEY_SET` | 22,077 | 0 | 1.673 ms | 1.563 ms | 2.280 ms | 3.199 ms |

### Interpretation

The bulk-heavy profile is significantly stronger than the older historical baseline. `GET_ALL` and `PUT_ALL` remain low-latency for the current small-batch benchmark behavior. `SIZE` and `KEY_SET` are also low-latency in this run, which materially changes the prior interpretation of metadata operations.

Important caveat: the current Java benchmark harness uses small `GET_ALL` / `PUT_ALL` batches. Boundary-sized and large-response benchmarks should still be added or run separately for `150` and `253` entries.

---

## Mixed

### Configuration

- Profile: `mixed`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`
- Keyspace: `1000`
- Seed count: `1000`

### Measured summary

- Total operations: **738,862**
- Successes: **738,862**
- Errors: **0**
- Throughput: **6,152.36 ops/sec**

### Latency highlights

| Operation | Successes | Errors | Avg | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| `GET` | 295,277 | 0 | 1.104 ms | 1.030 ms | 1.544 ms | 2.080 ms |
| `PUT` | 148,560 | 0 | 2.061 ms | 1.963 ms | 2.758 ms | 3.518 ms |
| `REMOVE` | 36,965 | 0 | 2.049 ms | 1.956 ms | 2.750 ms | 3.536 ms |
| `CONTAINS_KEY` | 73,898 | 0 | 1.148 ms | 1.042 ms | 1.691 ms | 2.283 ms |
| `GET_ALL` | 73,680 | 0 | 1.833 ms | 1.750 ms | 2.542 ms | 3.252 ms |
| `PUT_ALL` | 36,895 | 0 | 4.623 ms | 4.474 ms | 5.966 ms | 6.996 ms |
| `SIZE` | 36,727 | 0 | 1.197 ms | 1.122 ms | 1.656 ms | 2.268 ms |
| `KEY_SET` | 36,860 | 0 | 1.559 ms | 1.464 ms | 2.106 ms | 3.024 ms |

### Interpretation

The mixed profile now provides a useful combined workload result because metadata operations did not dominate the run. Throughput remained above 6,100 ops/sec with zero errors. `PUT_ALL` is the most expensive operation in the mix, but still stayed below 7 ms at p99.

---

## Metadata-heavy

### Configuration

- Profile: `metadata-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`
- Keyspace: `1000`
- Seed count: `1000`

### Measured summary

- Total operations: **848,202**
- Successes: **848,202**
- Errors: **0**
- Throughput: **7,062.41 ops/sec**

### Latency highlights

| Operation | Successes | Errors | Avg | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| `SIZE` | 566,331 | 0 | 1.288 ms | 1.072 ms | 1.723 ms | 2.717 ms |
| `KEY_SET` | 281,871 | 0 | 1.669 ms | 1.418 ms | 2.166 ms | 3.265 ms |

### Interpretation

This is the largest change from the older benchmark record. `SIZE` and `KEY_SET` are no longer showing the previous hundreds-of-milliseconds behavior under the current implementation and benchmark setup.

These operations should be reclassified for the current baseline as:

```text
currently fast metadata operations under the tested 1,000-key local workload
```

They should **not** be generalized as always-fast for all workloads. `KEY_SET`, in particular, can become response-size-sensitive as key counts grow, so it should remain a monitored operation using the newly added request/response byte-size metrics.

---

## Current performance classes

## Class A: fast point and small-bulk operations

These are currently performing well:

- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- small `GET_ALL`
- small `PUT_ALL`

## Class B: currently fast metadata operations, but response-size-sensitive

These performed well in the current 1,000-key benchmark:

- `SIZE`
- `KEY_SET`

They should still be monitored carefully because their performance may depend on:

- keyspace size
- query/index implementation
- response byte size
- Couchbase cluster sizing
- local vs remote deployment
- concurrency
- whether the operation is used in a hot path

## Class C: still needs explicit boundary/load testing

These scenarios are not sufficiently covered by the current small-batch benchmark harness:

- `GET_ALL` with 150 keys
- `GET_ALL` with 253 keys
- `PUT_ALL` with 150 entries
- `PUT_ALL` with 253 entries
- `KEY_SET` with much larger keyspaces
- PDX simple load
- PDX mixed batch load

---

## Comparison to prior historical baseline

The previous performance record described two performance classes:

```text
fast KV-style operations
slow metadata/query-backed operations
```

In that older run, `SIZE` and `KEY_SET` were recorded around hundreds of milliseconds at concurrency 10 and multiple seconds at concurrency 50. The current 2026-05-18 run does **not** reproduce that behavior.

### Prior historical metadata result

| Operation/profile | Historical p50 | Historical p99 |
|---|---:|---:|
| `SIZE` in `metadata-heavy` at concurrency 10 | ~571 ms | not listed |
| `KEY_SET` in `metadata-heavy` at concurrency 10 | ~572 ms | not listed |
| `SIZE` in concurrency 50 warmup | ~2,776 ms | ~5,635 ms |
| `KEY_SET` in concurrency 50 warmup | ~2,780 ms | ~5,525 ms |

### Current metadata result

| Operation/profile | Current p50 | Current p99 |
|---|---:|---:|
| `SIZE` in `metadata-heavy` at concurrency 10 | 1.072 ms | 2.717 ms |
| `KEY_SET` in `metadata-heavy` at concurrency 10 | 1.418 ms | 3.265 ms |
| `SIZE` in `bulk-heavy` at concurrency 10 | 1.178 ms | 2.363 ms |
| `KEY_SET` in `bulk-heavy` at concurrency 10 | 1.563 ms | 3.199 ms |

### Interpretation

The older metadata-performance warning should be treated as historical. It may have reflected an earlier implementation, query/index path, logging behavior, environment, or repository strategy.

The current baseline should be used for current launch/readiness discussions.

---

## Operational guidance

### Recommended hot-path usage

ProtoGemCouch appears suitable for application paths dominated by:

- single-key reads
- single-key writes
- single-key deletes
- existence checks
- moderate small-batch `GET_ALL`
- moderate small-batch `PUT_ALL`

### Metadata operation guidance

`SIZE` and `KEY_SET` are currently fast under the tested local workload. However, they should still be treated as operations requiring monitoring and clear workload expectations.

Use the metrics endpoints to watch:

```text
protogemcouch_operation_latency_avg_ns{operation="SIZE"}
protogemcouch_operation_latency_max_ns{operation="SIZE"}
protogemcouch_operation_latency_avg_ns{operation="KEY_SET"}
protogemcouch_operation_latency_max_ns{operation="KEY_SET"}
protogemcouch_operation_response_bytes_max{operation="KEY_SET"}
```

### Bulk operation guidance

`GET_ALL` and `PUT_ALL` are currently fast for the small-batch benchmark harness. Boundary-sized benchmarks should be added for 150 and 253 entries to align performance testing with the serialization boundary tests.

---

## Risks and caveats

- These results are for the current shim implementation only.
- The benchmark used a local Docker Compose deployment.
- The benchmark used a keyspace of `1000`.
- The benchmark used small `GET_ALL` and `PUT_ALL` batches in the current Java harness.
- The benchmark skipped `mvn test` / `mvn clean verify` inside the wrapper because `-SkipVerify` was used.
- This run does not replace the need for longer soak testing.
- This run does not characterize remote Couchbase latency, cloud deployment latency, or larger keyspaces.
- The `KEY_SET` operation may become more response-size-sensitive as key counts grow.
- Request/response byte-size metrics are now available and should be included in future result analysis.

---

## Recommended next tests

1. 15-minute soak test for `read-heavy` at concurrency 10.
2. 15-minute soak test for `write-heavy` at concurrency 10.
3. 15-minute soak test for `bulk-heavy` at concurrency 10.
4. 15-minute soak test for `metadata-heavy` at concurrency 10.
5. Repeat `read-heavy` at concurrency 25.
6. Repeat `write-heavy` at concurrency 25.
7. Repeat `metadata-heavy` at concurrency 25 and 50.
8. Add explicit benchmark profiles for `GET_ALL` / `PUT_ALL` with 150 entries.
9. Add explicit benchmark profiles for `GET_ALL` / `PUT_ALL` with 253 entries.
10. Add a larger-keyspace `KEY_SET` benchmark.
11. Add PDX simple and PDX mixed-batch benchmark profiles.
12. Record request/response byte-size summaries alongside latency summaries.

---

## Current baseline conclusion

ProtoGemCouch currently has a strong baseline for point, small-bulk, mixed, and metadata-heavy workloads in the tested local Docker Compose environment.

The current best launch-readiness statement is:

> ProtoGemCouch is a scoped production candidate for applications whose required behavior is covered by the current supported operation set, especially workloads dominated by CRUD, small bulk operations, and currently validated metadata operations under known keyspace and deployment constraints.

It should still not be described as:

> A general-purpose Apache Geode server replacement for all operations, all data shapes, all keyspace sizes, and all deployment topologies.

The next benchmark milestone is:

```text
current-baseline-soak-and-boundary-profile-refresh
```

---

## Historical baseline notes

The older benchmark data remains useful as historical context, but it should not be used as the current performance claim.

The older baseline concluded:

```text
KV-style operations were fast.
SIZE and KEY_SET were materially slower.
Metadata-heavy workloads were not suitable for hot-path usage.
```

The current 2026-05-18 baseline materially changes the metadata-operation conclusion for the current implementation and test environment.
