# ProtoGemCouch Performance Results

## Purpose

This document records benchmark results for ProtoGemCouch using the Java concurrency harness against the current shim implementation.

These results are intended to establish a baseline for:
- CRUD-style request performance
- bulk operation performance
- metadata/query-backed operation performance
- concurrency behavior
- future regression comparison

---

## Test setup

### Environment
- Benchmark client: Java concurrency harness
- Target: `RawShimServer`
- Host: `127.0.0.1:40405`
- Region: `helloWorld`
- Keyspace: `1000`
- Seed before run: `true`
- Seed count: `1000`
- Warmup: `15s`
- Progress reporting: `15s`

### Workload profiles tested
- `read-heavy`
- `write-heavy`
- `bulk-heavy`
- `mixed`
- `metadata-heavy`

---

## Executive summary

ProtoGemCouch currently shows two clear performance classes:

### 1. Fast KV-style operations
These operations perform well and appear production-promising for scoped workloads:
- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- `GET_ALL`
- `PUT_ALL`

At concurrency 10:
- `read-heavy` sustained about **11,550 ops/sec** with **0 errors**, with `GET` p50 around **0.653 ms** and `CONTAINS_KEY` p50 around **0.660 ms**. :contentReference[oaicite:0]{index=0}
- `write-heavy` sustained about **8,735 ops/sec** with **0 errors**, with `PUT` p50 around **0.961 ms**, `REMOVE` p50 around **0.961 ms**, and `PUT_ALL` p50 around **2.668 ms**. :contentReference[oaicite:1]{index=1}
- `bulk-heavy` sustained about **168 ops/sec** with **0 errors**, with `GET_ALL` p50 around **3.647 ms** and `PUT_ALL` p50 around **3.950 ms**. :contentReference[oaicite:2]{index=2}

### 2. Slow metadata/query-backed operations
These operations are functionally stable but materially slower:
- `SIZE`
- `KEY_SET`

At concurrency 10:
- `metadata-heavy` sustained about **17.38 ops/sec** with **0 errors**
- `SIZE` p50 was about **571 ms**
- `KEY_SET` p50 was about **572 ms**. :contentReference[oaicite:3]{index=3}

At concurrency 50:
- `metadata-heavy` warmup sustained about **17.17 ops/sec**
- `SIZE` p50 increased to about **2776 ms**
- `KEY_SET` p50 increased to about **2780 ms**
- p99 exceeded **5.5 seconds** for both operations. :contentReference[oaicite:4]{index=4}

### High-level conclusion
ProtoGemCouch is currently well-suited for workloads dominated by KV-style region operations, but `SIZE` and `KEY_SET` should be treated as expensive metadata operations rather than hot-path application calls.

---

## Results by profile

## Read-heavy

### Configuration
- Profile: `read-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`

### Measured summary
- Total operations: about **1.39 million**
- Throughput: about **11,550 ops/sec**
- Errors: **0**. :contentReference[oaicite:6]{index=6}

### Latency highlights
- `GET`
    - p50: **0.653 ms**
    - p95: **0.915 ms**
    - p99: **1.755 ms**
- `CONTAINS_KEY`
    - p50: **0.660 ms**
    - p95: **0.932 ms**
    - p99: **1.733 ms**
- `GET_ALL`
    - average: **2.004 ms**
    - p50: **1.396 ms**
    - p95: **2.848 ms**
    - p99: **7.388 ms**. :contentReference[oaicite:7]{index=7}

### Interpretation
This is the strongest result set so far. KV-style reads are very fast and stable at concurrency 10, with zero observed errors and low tail latency. :contentReference[oaicite:8]{index=8}

---

## Write-heavy

### Configuration
- Profile: `write-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`

### Measured summary
- Total operations: about **1.05 million**
- Throughput: about **8,735 ops/sec**
- Errors: **0**. :contentReference[oaicite:9]{index=9}

### Latency highlights
- `PUT`
    - p50: **0.961 ms**
    - p95: **1.784 ms**
    - p99: **4.378 ms**
- `REMOVE`
    - p50: **0.961 ms**
    - p95: **1.731 ms**
    - p99: **3.839 ms**
- `PUT_ALL`
    - p50: **2.668 ms**
    - p95: **4.684 ms**
    - p99: **10.130 ms**. :contentReference[oaicite:10]{index=10}

### Interpretation
Write paths are also strong at concurrency 10. Bulk writes cost more than single-key writes, but remain in a low-millisecond range at the median and low-double-digit-millisecond range at p99. :contentReference[oaicite:11]{index=11}

---

## Bulk-heavy

### Configuration
- Profile: `bulk-heavy`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`

### Measured summary
- Total operations: about **20,246**
- Throughput: about **168 ops/sec**
- Errors: **0**. :contentReference[oaicite:12]{index=12}

### Latency highlights
- `GET_ALL`
    - p50: **3.647 ms**
    - p95: **7.304 ms**
    - p99: **14.634 ms**
- `PUT_ALL`
    - p50: **3.950 ms**
    - p95: **7.347 ms**
    - p99: **14.261 ms**
- `SIZE`
    - p50: **558.288 ms**
    - p95: **589.421 ms**
    - p99: **622.197 ms**
- `KEY_SET`
    - p50: **558.537 ms**
    - p95: **589.990 ms**
    - p99: **621.477 ms**. :contentReference[oaicite:13]{index=13}

### Interpretation
The bulk profile confirms that `GET_ALL` and `PUT_ALL` are still relatively fast, but throughput collapses once `SIZE` and `KEY_SET` are mixed in. These metadata operations dominate the profile cost. :contentReference[oaicite:14]{index=14}

---

## Mixed

### Configuration
- Profile: `mixed`
- Concurrency: `10`
- Warmup: `15s`
- Measured duration: `2m`

### Measured summary
- Throughput: about **175 ops/sec**
- Warmup and measured runs show fast point ops but expensive metadata ops in the same profile.

### Latency highlights
Warmup showed:
- `GET` p50 about **1.334 ms**
- `PUT` p50 about **1.424 ms**
- `REMOVE` p50 about **1.412 ms**
- `CONTAINS_KEY` p50 about **1.335 ms**
- `SIZE` p50 about **555 ms**
- `KEY_SET` p50 about **555 ms**. :contentReference[oaicite:16]{index=16}

### Interpretation
The mixed profile is not a clean representation of CRUD performance because the expensive metadata operations materially drag down overall throughput and tail latency. Mixed-profile results should therefore not be used as a proxy for hot-path application performance unless the real application calls `SIZE` and `KEY_SET` frequently.

---

## Metadata-heavy

### Configuration
- Profile: `metadata-heavy`
- Concurrency tested:
    - `10`
    - `50`
- Warmup: `15s`
- Measured duration: `2m`

### Concurrency 10 results
- Throughput: about **17.38 ops/sec**
- Errors: **0**
- `SIZE` p50: about **571 ms**
- `KEY_SET` p50: about **572 ms**. :contentReference[oaicite:18]{index=18}

### Concurrency 50 warmup results
- Throughput: about **17.17 ops/sec**
- Errors: **0**
- `SIZE` p50: about **2776 ms**
- `KEY_SET` p50: about **2780 ms**
- `SIZE` p99: about **5635 ms**
- `KEY_SET` p99: about **5525 ms**. :contentReference[oaicite:19]{index=19}

### Interpretation
`SIZE` and `KEY_SET` do not scale like point operations. Increasing concurrency from 10 to 50 did not materially increase throughput, but it did significantly increase latency into the multi-second range. These operations are therefore better classified as expensive administrative/query-backed operations.

---

## Performance classes

## Class A: fast KV-style operations
These operations are currently performing well:
- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- `GET_ALL`
- `PUT_ALL`

## Class B: expensive metadata operations
These operations are currently much slower:
- `SIZE`
- `KEY_SET`

---

## Operational guidance

### Recommended hot-path usage
ProtoGemCouch appears suitable for application paths dominated by:
- single-key reads
- single-key writes
- single-key deletes
- existence checks
- moderate bulk get/put behavior.

### Recommended non-hot-path usage
Use `SIZE` and `KEY_SET` sparingly:
- operational tooling
- admin workflows
- infrequent metadata inspection
- not per-request application logic.

---

## Risks and caveats

- These results are for the current shim implementation only.
- Metadata operations are backed by expensive query-like behavior and should not be conflated with point-operation performance.
- Logging warnings from Geode about missing Log4j implementation were present during benchmark runs, but the benchmark still completed and the main observed behavior was latency divergence between operation classes.

---

## Recommended next tests

1. 15-minute soak test for `read-heavy` at concurrency 10
2. 15-minute soak test for `write-heavy` at concurrency 10
3. 15-minute soak test for `metadata-heavy` at concurrency 10
4. Repeat `read-heavy` at concurrency 25
5. Repeat `write-heavy` at concurrency 25
6. Record CPU, memory, and error-rate observations alongside benchmark summaries

---

## Current baseline conclusion

ProtoGemCouch currently has a strong baseline for KV-style operations and a much slower, clearly separate performance profile for metadata operations. For scoped production-like use, the best candidate workloads are those dominated by CRUD and bulk region operations rather than frequent `SIZE` or `KEY_SET` calls. 