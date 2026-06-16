# ProtoGemCouch Performance Baseline

## Purpose

This document defines the repeatable performance baseline process for ProtoGemCouch.

Use this document as the runbook for executing a consistent benchmark pass after meaningful implementation changes, such as:

```text
serialization changes
PDX compatibility changes
bulk operation changes
observability changes
repository implementation changes
deployment/runtime changes
```

Actual measured results should be recorded in:

```text
PERFORMANCE_RESULTS.md
```

This document answers:

```text
What should we run?
How should we run it?
What metrics should we capture?
What constitutes a useful baseline?
How do we compare future runs?
```

---

## Relationship to `PERFORMANCE_RESULTS.md`

Use the two documents this way:

| Document | Purpose |
|---|---|
| `PERFORMANCE_BASELINE.md` | Repeatable benchmark plan and runbook. |
| `PERFORMANCE_RESULTS.md` | Actual measured results from completed runs. |

`PERFORMANCE_BASELINE.md` should change infrequently.

`PERFORMANCE_RESULTS.md` should be updated each time a new formal benchmark run is completed.

---

## Automated regression gate

`scripts/perf-gate.sh` is the automated companion to this runbook. Against a running shim + Couchbase
it runs the concurrency benchmark (`com.protogemcouch.benchmark.ConcurrentBenchmarkRunner`, which now
emits a machine-readable `PERF_RESULT ops_per_sec=… errors=… max_p99_ms=…` line) and **fails** if
throughput, worst-operation p99, or error count cross the thresholds in `scripts/perf-baseline.env`.

```bash
docker compose up -d --build protogemcouch   # shim + Couchbase
mvn -DskipTests package                       # build the jar the benchmark client uses
./scripts/perf-gate.sh                        # exit 0 = pass, 1 = regression, 2 = setup error
```

The thresholds are **conservative gross-regression guards**, not tight SLOs — they catch a large
regression (a bottleneck that tanks throughput or blows up tail latency) while tolerating the wide
run-to-run variance of shared CI runners. The gate runs in CI via `.github/workflows/perf-gate.yml` on
release `v*` tags (part of the release gate), weekly, and on demand — not per-PR, where runner variance
would make it flaky. To make it a true regression detector (vs. a gross-regression guard), re-run this
runbook on a dedicated, representative environment, record results in `PERFORMANCE_RESULTS.md`, and
tighten `perf-baseline.env` accordingly.

---

## Current baseline milestone

Current target milestone:

```text
current-baseline-performance-and-soak-refresh
```

This refresh should be run after the following completed work:

```text
PDX / PdxInstance support
large collection boundary fixes
VersionedObjectList GET_ALL encoding fix
keySetOnServer collection length fix
/metrics/json endpoint
Prometheus-format /metrics endpoint
request byte-size metrics
response byte-size metrics
metrics unit tests
health endpoint unit tests
```

---

## Required preconditions

Before running the baseline, confirm:

```bash
mvn test
mvn clean verify
```

Expected current integration result:

```text
Tests run: 145
Failures: 0
Errors: 0
Skipped: 3
BUILD SUCCESS
```

The benchmark should not be run as a formal baseline if the full test suite is failing.

---

## Environment record

Each completed baseline run should record the following in `PERFORMANCE_RESULTS.md`.

### Git/build details

```text
date/time:
git branch:
git commit:
dirty working tree: yes/no
Java version:
Maven version:
Docker version:
Docker Compose version:
ProtoGemCouch image/tag:
```

Suggested commands:

```bash
git rev-parse --abbrev-ref HEAD
git rev-parse HEAD
git status --short
java -version
mvn -version
docker version
docker compose version
```

### Runtime environment

```text
host OS:
CPU model/count:
memory:
disk type:
local vs VM vs cloud instance:
Couchbase version:
Couchbase deployment mode:
Couchbase bucket:
Couchbase scope:
Couchbase collection:
shim port:
health/admin port:
```

### Default local environment

For the Docker Compose development baseline:

```text
Target shim: 127.0.0.1:40405
Health/admin endpoint: 127.0.0.1:8081
Region: helloWorld
Couchbase bucket: test
Couchbase scope: _default
Couchbase collection: _default
```

---

## Startup procedure

Start from a clean local state:

```bash
docker compose down -v
mvn clean package
docker compose up -d --build
```

Check containers:

```bash
docker ps
```

Follow the shim logs if needed:

```bash
docker logs -f protogemcouch-shim
```

Validate health endpoints:

```bash
curl -fs http://127.0.0.1:8081/live
curl -fs http://127.0.0.1:8081/ready
```

Validate metrics endpoints:

```bash
curl -fs http://127.0.0.1:8081/metrics/json
curl -fs http://127.0.0.1:8081/metrics
```

---

## Metrics capture

Capture metrics before and after each benchmark profile.

### Before profile

```bash
curl -fs http://127.0.0.1:8081/metrics/json > metrics-before-<profile>.json
curl -fs http://127.0.0.1:8081/metrics > metrics-before-<profile>.prom
```

### After profile

```bash
curl -fs http://127.0.0.1:8081/metrics/json > metrics-after-<profile>.json
curl -fs http://127.0.0.1:8081/metrics > metrics-after-<profile>.prom
```

At minimum, capture:

```text
connections opened
connections closed
handshake requests
unknown opcodes
request errors
operation requests
operation successes
operation errors
operation latency avg/min/max/last
operation request bytes total/last/max/avg
operation response bytes total/last/max/avg
```

---

## Required workload profiles

The current formal baseline should include these workload profiles.

## 1. Read-heavy

Purpose:

```text
Validate hot-path read and existence-check behavior.
```

Expected operation mix:

```text
GET
CONTAINS_KEY
GET_ALL
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
seed before run: true
seed count: 1000
keyspace: 1000
```

Recommended follow-up configuration:

```text
concurrency: 25
warmup: 15s
measured duration: 2m
```

Record:

```text
throughput
total operations
errors
GET p50/p95/p99
CONTAINS_KEY p50/p95/p99
GET_ALL p50/p95/p99
request/response byte metrics
```

---

## 2. Write-heavy

Purpose:

```text
Validate hot-path write, remove, and small bulk write behavior.
```

Expected operation mix:

```text
PUT
REMOVE
PUT_ALL
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
seed before run: true
seed count: 1000
keyspace: 1000
```

Recommended follow-up configuration:

```text
concurrency: 25
warmup: 15s
measured duration: 2m
```

Record:

```text
throughput
total operations
errors
PUT p50/p95/p99
REMOVE p50/p95/p99
PUT_ALL p50/p95/p99
request/response byte metrics
```

---

## 3. Bulk-heavy

Purpose:

```text
Validate bulk operation behavior and compare response size to latency.
```

Expected operation mix:

```text
GET_ALL
PUT_ALL
optional SIZE
optional KEY_SET
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
seed before run: true
seed count: 1000
keyspace: 1000
```

If the benchmark harness supports batch sizing, include:

```text
batch size: 10
batch size: 150
batch size: 253
```

Record:

```text
throughput
total operations
errors
GET_ALL p50/p95/p99
PUT_ALL p50/p95/p99
response bytes max for GET_ALL
response bytes average for GET_ALL
request bytes average for PUT_ALL
```

---

## 4. Boundary bulk: 150 entries

Purpose:

```text
Validate performance and response size across the >127 boundary.
```

This specifically protects the previously fixed `VersionedObjectList` count path.

Recommended scenario:

```text
putAll/getAll with 150 entries
keySetOnServer after at least 150 inserted keys
```

Record:

```text
GET_ALL latency
GET_ALL response bytes
PUT_ALL latency
PUT_ALL request bytes
KEY_SET latency
KEY_SET response bytes
errors
unknown opcodes
```

Expected result:

```text
0 errors
0 unknown opcodes
GET_ALL succeeds
PUT_ALL succeeds
KEY_SET succeeds
```

---

## 5. Boundary bulk: 253 entries

Purpose:

```text
Validate performance and response size across the Geode extended array/list length boundary.
```

Recommended scenario:

```text
putAll/getAll with 253 entries
keySetOnServer after at least 253 inserted keys
```

Record:

```text
GET_ALL latency
GET_ALL response bytes
PUT_ALL latency
PUT_ALL request bytes
KEY_SET latency
KEY_SET response bytes
errors
unknown opcodes
```

Expected result:

```text
0 errors
0 unknown opcodes
GET_ALL succeeds
PUT_ALL succeeds
KEY_SET succeeds
```

---

## 6. Metadata-heavy

Purpose:

```text
Characterize expensive metadata/query-backed operations.
```

Expected operation mix:

```text
SIZE
KEY_SET
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
seed before run: true
seed count: 1000
keyspace: 1000
```

Recommended stress configuration:

```text
concurrency: 50
warmup: 15s
measured duration: 2m
```

Record:

```text
throughput
total operations
errors
SIZE p50/p95/p99
KEY_SET p50/p95/p99
KEY_SET response bytes
unknown opcodes
request errors
```

Important interpretation rule:

```text
SIZE and KEY_SET should be treated as expensive metadata operations.
Do not blend metadata-heavy results into hot-path CRUD performance conclusions.
```

---

## 7. Mixed

Purpose:

```text
Show combined behavior when fast KV operations and expensive metadata operations are mixed.
```

Expected operation mix:

```text
GET
PUT
REMOVE
CONTAINS_KEY
GET_ALL
PUT_ALL
SIZE
KEY_SET
```

Record:

```text
throughput
operation distribution
per-operation p50/p95/p99
errors
metadata operation impact
request/response byte metrics
```

Interpretation rule:

```text
Mixed workload throughput may be dominated by SIZE and KEY_SET.
Report CRUD-style and metadata-style operation classes separately.
```

---

## 8. PDX simple

Purpose:

```text
Validate baseline performance for simple PdxInstance put/get behavior.
```

Expected operation mix:

```text
PUT PdxInstance
GET PdxInstance
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
```

Record:

```text
PUT p50/p95/p99
GET p50/p95/p99
request bytes
response bytes
errors
```

---

## 9. PDX mixed batch

Purpose:

```text
Validate baseline behavior for mixed primitive and PDX bulk flows.
```

Expected operation mix:

```text
PUT_ALL mixed primitive + PDX values
GET_ALL mixed primitive + PDX values
```

Recommended starting configuration:

```text
concurrency: 10
warmup: 15s
measured duration: 2m
```

Record:

```text
PUT_ALL latency
GET_ALL latency
GET_ALL response bytes
PUT_ALL request bytes
errors
```

---

## Soak tests

After short benchmark runs, execute soak runs.

Minimum recommended soak pass:

```text
read-heavy, concurrency 10, duration 15m
write-heavy, concurrency 10, duration 15m
bulk-heavy, concurrency 10, duration 15m
metadata-heavy, concurrency 10, duration 15m
```

Optional extended soak pass:

```text
read-heavy, concurrency 25, duration 30m
write-heavy, concurrency 25, duration 30m
bulk-heavy, concurrency 25, duration 30m
```

During soak runs, capture:

```text
start metrics JSON
end metrics JSON
shim logs
Couchbase logs if available
container CPU
container memory
error count
unknown opcode count
latency max
response byte max
connection churn
```

Suggested Docker stats command:

```bash
docker stats protogemcouch-shim protogemcouch-couchbase
```

---

## Acceptance criteria for scoped baseline

A run can be considered a valid baseline if:

```text
mvn test passes before the run
mvn clean verify passes before the run
the shim starts cleanly
/live returns 200
/ready returns 200
/metrics/json returns 200
/metrics returns 200
benchmark completes without harness failure
unknown opcode count remains 0 unless intentionally tested
request error count remains 0 for normal workloads
results are recorded in PERFORMANCE_RESULTS.md
```

For scoped production-candidate confidence:

```text
read-heavy has 0 errors
write-heavy has 0 errors
bulk-heavy has 0 errors
PDX simple has 0 errors
PDX mixed batch has 0 errors
metadata-heavy behavior is documented and not positioned as hot-path performance
```

---

## Regression comparison

When comparing a new run against a previous run, compare:

```text
throughput by profile
p50/p95/p99 latency by operation
request error count
unknown opcode count
GET_ALL response bytes
KEY_SET response bytes
PUT_ALL request bytes
CPU/memory utilization
```

Flag for investigation if:

```text
error count increases above 0
unknown opcode count increases above 0
GET/PUT p95 latency regresses by more than 25%
GET_ALL p95 latency regresses by more than 25%
PUT_ALL p95 latency regresses by more than 25%
metadata operation latency regresses by more than 25%
response byte size grows unexpectedly for same workload
request byte size grows unexpectedly for same workload
```

---

## Recommended result entry template

Use this template in `PERFORMANCE_RESULTS.md` for each formal run.

```markdown
## Run: YYYY-MM-DD — <short description>

### Build

- Branch:
- Commit:
- Dirty working tree:
- Java:
- Maven:
- Docker:
- Couchbase:
- ProtoGemCouch image/tag:

### Environment

- Host:
- CPU:
- Memory:
- Disk:
- Region:
- Keyspace:
- Seed count:

### Commands

```bash
<commands used>
```

### Metrics snapshots

```text
metrics-before files:
metrics-after files:
```

### Summary

| Profile | Concurrency | Duration | Throughput | Errors | Notes |
|---|---:|---:|---:|---:|---|
| read-heavy | 10 | 2m | TBD | TBD | TBD |
| write-heavy | 10 | 2m | TBD | TBD | TBD |
| bulk-heavy | 10 | 2m | TBD | TBD | TBD |
| metadata-heavy | 10 | 2m | TBD | TBD | TBD |

### Operation latency highlights

| Operation | p50 | p95 | p99 | Max | Notes |
|---|---:|---:|---:|---:|---|
| GET | TBD | TBD | TBD | TBD | TBD |
| PUT | TBD | TBD | TBD | TBD | TBD |
| GET_ALL | TBD | TBD | TBD | TBD | TBD |
| PUT_ALL | TBD | TBD | TBD | TBD | TBD |
| SIZE | TBD | TBD | TBD | TBD | TBD |
| KEY_SET | TBD | TBD | TBD | TBD | TBD |

### Byte-size highlights

| Operation | Request avg | Request max | Response avg | Response max | Notes |
|---|---:|---:|---:|---:|---|
| GET | TBD | TBD | TBD | TBD | TBD |
| PUT | TBD | TBD | TBD | TBD | TBD |
| GET_ALL | TBD | TBD | TBD | TBD | TBD |
| PUT_ALL | TBD | TBD | TBD | TBD | TBD |
| KEY_SET | TBD | TBD | TBD | TBD | TBD |

### Interpretation

TBD

### Follow-up actions

TBD
```

---

## Current known performance interpretation

Based on prior benchmark results, ProtoGemCouch has two known performance classes:

```text
Class A: fast KV-style operations
- GET
- PUT
- REMOVE
- CONTAINS_KEY
- GET_ALL
- PUT_ALL

Class B: expensive metadata operations
- SIZE
- KEY_SET
```

Do not collapse these into a single overall performance claim.

For scoped production-like use, the strongest candidate workloads are those dominated by CRUD and moderate bulk region operations, not frequent metadata operations.
