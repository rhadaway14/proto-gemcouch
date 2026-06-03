# ProtoGemCouch Soak Results

## Purpose

This document records long-duration stability testing for ProtoGemCouch.

The goal of soak testing is to validate:
- sustained throughput under load
- latency stability over time
- absence of growing error rates
- absence of obvious runtime instability
- safe operating envelopes for the currently supported workload profiles

---

## Environment

### Benchmark target
- `RawShimServer`
- Host: `127.0.0.1:40405`
- Region: `helloWorld`

### Benchmark harness
- Java concurrency harness
- Seed before run: `true`
- Seed count: `1000`
- Warmup: `15s`
- Progress interval: `15s`

### Profiles tested
- `read-heavy`
- `write-heavy`
- `metadata-heavy`

---

## Executive summary

The soak results show a very strong stability story for the KV-style paths:

- **read-heavy** workloads sustained high throughput with **0 errors**
- **write-heavy** workloads also sustained high throughput with **0 errors**
- **metadata-heavy** workloads were stable with **0 errors**, but remained much slower and clearly belong to a separate performance class from normal CRUD operations

The most important conclusion is:

> ProtoGemCouch currently appears stable and high-performing for sustained CRUD-style and bulk-style workloads, while `SIZE` and `KEY_SET` remain stable but expensive metadata operations that should not be treated as hot-path application calls.

---

## Soak run results

## Run: 2026-06-02 — robustness build stability soak

Short sustained-stability soak against the hardened build (`feature/robustness`, default config),
driven by `scripts/soak.sh`, which runs a continuous workload and samples server metrics and
container memory at a fixed interval. (The same tool runs arbitrarily long soaks via `--duration`;
this run is a 3-minute stability check, not a multi-hour endurance test.)

### Configuration

- Profile `mixed`, concurrency `16`, duration `180s`, sample interval `30s`, keyspace `2000`, seeded
- Target: Docker Compose stack (Couchbase enterprise 7.6.2 + shim) on a single host
- Command: `./scripts/soak.sh --duration 180 --sample-interval 30 --concurrency 16 --keyspace 2000`

### Per-sample stability (cumulative `requests` is shim-lifetime; ~220k per 30s window ≈ 7,300 ops/s)

```text
t(s)   requests   errors shed malform 1stReqTO active shimMem
30     2,933,102   0      0    0       0        147    1.182GiB
63     3,150,834   0      0    0       0        150    1.185GiB
95     3,371,230   0      0    0       0        153    1.185GiB
128    3,591,743   0      0    0       0        157    1.186GiB
160    3,814,104   0      0    0       0        160    1.187GiB
193    4,035,032   0      0    0       0        163    1.189GiB
225    4,063,398   0      0    0       0        164    1.189GiB  (tail/drain)
```

### Result

- Sustained throughput: **~7,300 ops/sec** (mixed profile)
- **Request errors: 0**, requests shed: 0, malformed frames: 0, first-request timeouts: 0 — for the
  entire run
- **Memory flat**: 1.182 → 1.189 GiB across the run (~7 MB, within normal heap noise) — no leak trend
- **Connections stabilized**: ~147 → ~164 then plateaued (Geode client pool establishing under
  concurrency 16); no unbounded growth
- Client-side latency steady and consistent with the benchmark baseline, e.g. `PUT_ALL` p99
  ~10.8 ms, `GET_ALL` p99 ~4.8 ms, `CONTAINS_KEY`/`SIZE` p99 ~3.4 ms

### Conclusion

> Under sustained mixed load the hardened build is stable: throughput holds, latency does not creep,
> memory is flat, no connection leak appears, and none of the robustness guards (error, shed,
> malformed, first-request-timeout counters) trip under healthy load. The new `scripts/soak.sh`
> makes this an easily repeatable, longer-duration check.

---

## 1. Read-heavy soak — 15 minutes @ concurrency 25

### Configuration
- Profile: `read-heavy`
- Concurrency: `25`
- Warmup: `15s`
- Measured duration: `15m`

### Result
- Total operations: **19,005,844**
- Successes: **19,005,844**
- Errors: **0**
- Throughput: **21,115.23 ops/sec** :contentReference[oaicite:1]{index=1}

### Latency
- `GET`
    - avg: **1.015 ms**
    - p50: **0.844 ms**
    - p95: **1.858 ms**
    - p99: **3.153 ms**
- `CONTAINS_KEY`
    - avg: **1.018 ms**
    - p50: **0.844 ms**
    - p95: **1.865 ms**
    - p99: **3.176 ms**
- `GET_ALL`
    - avg: **2.442 ms**
    - p50: **2.254 ms**
    - p95: **3.612 ms**
    - p99: **6.085 ms** :contentReference[oaicite:2]{index=2}

### Interpretation
This is an excellent sustained read-path result. Throughput remained high across the full 15-minute run, error count stayed at zero, and the latency profile remained tight. This is a strong indication that read-heavy KV-style workloads are a good fit for the current shim implementation. :contentReference[oaicite:3]{index=3}

### Status
**Pass**

---

## 2. Read-heavy soak — 1 hour @ concurrency 25

### Configuration
- Profile: `read-heavy`
- Concurrency: `25`
- Warmup: `15s`
- Measured duration: `1h`

### Result
- Total operations: **74,152,150**
- Successes: **74,152,150**
- Errors: **0**
- Throughput: **20,596.09 ops/sec** :contentReference[oaicite:4]{index=4}

### Latency
- `GET`
    - avg: **1.054 ms**
    - p50: **0.823 ms**
    - p95: **2.230 ms**
    - p99: **3.208 ms**
- `CONTAINS_KEY`
    - avg: **1.058 ms**
    - p50: **0.825 ms**
    - p95: **2.237 ms**
    - p99: **3.218 ms**
- `GET_ALL`
    - avg: **2.399 ms**
    - p50: **2.180 ms**
    - p95: **3.673 ms**
    - p99: **5.268 ms** :contentReference[oaicite:5]{index=5}

### Interpretation
This is the strongest soak result collected so far. The system sustained over 74 million successful operations with zero errors over a full hour, and latency stayed in a tight range. That strongly suggests good steady-state stability for read-dominant workloads. :contentReference[oaicite:6]{index=6}

### Status
**Pass**

---

## 3. Write-heavy soak — 15 minutes @ concurrency 10

### Configuration
- Profile: `write-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `15m`

### Result
- Total operations: **8,799,780**
- Successes: **8,799,780**
- Errors: **0**
- Throughput: **9,776.46 ops/sec** :contentReference[oaicite:7]{index=7}

### Latency
- `GET`
    - avg: **0.930 ms**
    - p50: **0.858 ms**
    - p95: **1.395 ms**
    - p99: **2.389 ms**
- `PUT`
    - p50 around **sub-1 ms to ~1 ms class**
- `REMOVE`
    - p50 around **sub-1 ms to ~1 ms class**
- `PUT_ALL`
    - present in this workload and remained low-millisecond class during the run :contentReference[oaicite:8]{index=8}

### Interpretation
The write-heavy soak at concurrency 10 was clean and stable, with zero errors and nearly 9.8k ops/sec sustained over 15 minutes. Mutation-heavy workloads appear healthy at this concurrency. :contentReference[oaicite:9]{index=9}

### Status
**Pass**

---

## 4. Write-heavy soak — 15 minutes @ concurrency 25

### Configuration
- Profile: `write-heavy`
- Concurrency: `25`
- Warmup: `15s`
- Measured duration: `15m`

### Result
- Total operations: **15,665,276**
- Successes: **15,665,276**
- Errors: **0**
- Throughput: **17,403.12 ops/sec** :contentReference[oaicite:10]{index=10}

### Latency
- `GET`
    - avg: **1.333 ms**
    - p50: **1.167 ms**
    - p95: **2.397 ms**
    - p99: **3.708 ms**
- `PUT`
    - avg: **1.335 ms**
    - p50: **1.165 ms**
    - p95: **2.388 ms**
    - p99: **3.722 ms**
- `REMOVE`
    - avg: **1.341 ms**
    - p50: **1.173 ms**
    - p95: **2.402 ms**
    - p99: **3.730 ms**
- `CONTAINS_KEY`
    - avg: **1.303 ms**
    - p50: **1.134 ms**
    - p95: **2.358 ms**
    - p99: **3.676 ms**
- `PUT_ALL`
    - avg: **3.395 ms**
    - p50: **3.205 ms**
    - p95: **4.637 ms**
    - p99: **7.167 ms** :contentReference[oaicite:11]{index=11}

### Interpretation
This is another very strong result. The write-heavy path scales well from concurrency 10 to 25, maintaining zero errors and good latency behavior. Bulk write operations remain predictably more expensive than single-key mutations, but still stay in a low-millisecond class. :contentReference[oaicite:12]{index=12}

### Status
**Pass**

---

## 5. Metadata-heavy baseline — concurrency 10

### Configuration
- Profile: `metadata-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`

### Result
- Total operations: **2,090**
- Successes: **2,090**
- Errors: **0**
- Throughput: **17.38 ops/sec** :contentReference[oaicite:13]{index=13}

### Latency
- `SIZE`
    - avg: **574.769 ms**
    - p50: **571.345 ms**
    - p95: **601.076 ms**
    - p99: **627.906 ms**
- `KEY_SET`
    - avg: **575.920 ms**
    - p50: **572.371 ms**
    - p95: **608.010 ms**
    - p99: **629.432 ms** :contentReference[oaicite:14]{index=14}

### Interpretation
This profile is stable, but clearly much slower than the CRUD profiles. `SIZE` and `KEY_SET` should continue to be treated as expensive metadata operations, not hot-path application operations. Stability is good, but throughput and latency are in a completely different class. :contentReference[oaicite:15]{index=15}

### Status
**Pass with caution**

---

## Overall findings

## Stable workload classes
The following workload classes currently look strong from a soak/stability perspective:
- read-heavy KV workloads
- write-heavy KV workloads
- moderate bulk operations inside those workloads

## Expensive but stable workload class
The following workload class is stable but expensive:
- metadata-heavy (`SIZE`, `KEY_SET`) :contentReference[oaicite:17]{index=17}

## Operational implication
ProtoGemCouch is currently best positioned for workloads dominated by:
- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- `GET_ALL`
- `PUT_ALL`

It is not appropriate to treat `SIZE` and `KEY_SET` as equivalent to the CRUD hot path from a performance perspective.

---

## Pass / fail summary

| Profile | Concurrency | Duration | Result |
|---|---:|---:|---|
| read-heavy | 25 | 15m | Pass |
| read-heavy | 25 | 1h | Pass |
| write-heavy | 10 | 15m | Pass |
| write-heavy | 25 | 15m | Pass |
| metadata-heavy | 10 | 2m baseline | Pass with caution |

---

## Recommendations

### Ready for next stage
Based on these soak findings, the project is ready to move into:
- deployment packaging
- Dockerization
- security hardening
- formal launch criteria review

### Additional soak tests worth running later
- write-heavy @ 25 for 1 hour
- read-heavy @ 50 for 15 minutes
- metadata-heavy @ 10 for 15 minutes
- mixed @ 10 or 25 for longer duration, if the real application uses that pattern

### Documentation updates
These soak findings should be considered alongside:
- `PERFORMANCE_RESULTS.md`
- `COMPATIBILITY_MATRIX.md`
- `LAUNCH_CRITERIA.md`

---

## Current conclusion

ProtoGemCouch now has strong evidence of sustained stability for read-heavy and write-heavy CRUD-oriented workloads, including a full one-hour read-heavy soak at concurrency 25 with zero errors and strong latency characteristics. Metadata operations remain functionally stable, but materially slower, and should remain outside hot application paths. 