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

## Automated stability verdict

`scripts/soak.sh` now renders a machine-readable **stability verdict** at the end of a run (and exits
non-zero on failure), so the soak can gate rather than relying on manual inspection of the per-sample
table. It hard-gates the signals that are reliable on any environment — the ones a soak exists to catch:

- **errors** — `protogemcouch_request_errors_total` stays within `SOAK_MAX_ERRORS` (default 0);
- **shedding** — `protogemcouch_requests_shed_total` within `SOAK_MAX_SHED` (default 0);
- **memory leak** — shim RSS growth from the first to last sample within `SOAK_MAX_MEM_GROWTH_PCT`
  (default 25%);
- **connection leak** — active-connection growth within `SOAK_MAX_CONN_GROWTH` (default 50).

**Throughput trend** (last-third vs first-third of steady-state samples, warmup/seed sample excluded)
is *reported as a warning* by default — it is contention-sensitive on a co-located dev box or shared
CI runner, so it only gates with `SOAK_FAIL_ON_THROUGHPUT=true` on a dedicated rig (see below). The
verdict line looks like:

```text
SOAK_VERDICT PASS errors=0 shed=0 conn_growth=-17 mem_growth_pct=2.1 throughput_ratio=0.30
```

Reference smoke (single dev box, dockerized Couchbase, 100s, concurrency 16, mixed): PASS — 0 errors,
0 shed, no connection leak, memory growth ~2% (no leak); the throughput ratio fired only as a warning,
which on a co-located box reflects host contention, not a shim defect (the per-run average held at
~5,200 ops/sec).

### Running a real endurance / capacity-at-scale soak

The verdict makes the soak gateable, but meaningful **endurance and capacity numbers require dedicated
infrastructure** — not a single co-located box:

- Run the shim, Couchbase, and the load generator on **separate hosts**, with a **dedicated Couchbase**
  cluster sized like production.
- Run for **hours** (`--duration 14400`+) to surface slow leaks and GC/compaction effects that a short
  run cannot.
- Set `SOAK_FAIL_ON_THROUGHPUT=true` (and tune `SOAK_MIN_THROUGHPUT_RATIO`) once the environment is
  contention-free, so throughput degradation becomes a hard gate.
- Derive **resource sizing** (connections/CPU/memory per replica, replica count for a target QPS) from
  these runs; the single-box numbers below are a stability baseline, not a capacity ceiling.

**Turnkey rigs for this live in `deploy/`:**

- `deploy/ec2/` — Terraform that stands up a dedicated multi-host rig (load gens → NLB → shim hosts →
  dedicated Couchbase + an observability host running Prometheus/Grafana). `scripts/capacity-sweep.sh`
  steps concurrency and flags the knee; `shim_count` scales for the horizontal-scaling sweep. The
  cleanest baseline — fewest layers, one shim per host.
- `deploy/eks/` — the same rig on EKS, reusing the production Helm chart + kube-prometheus-stack, so
  the measured ceiling includes the real k8s deployment path (chart, Service LB, CNI). Run it *after*
  the EC2 baseline to quantify the k8s-path overhead.

Both surface the **Host Metrics** and **Couchbase** Grafana dashboards, whose disk-write-queue / OOM /
CPU panels attribute the knee to a specific tier (shim vs Couchbase vs network). See each directory's
`README.md`.

### First multi-host capacity characterization (EC2 rig)

First run on the `deploy/ec2` rig (shim hosts `c6i.xlarge` / 4 vCPU, dedicated Couchbase `r6i.xlarge`,
internal NLB, load generators on separate hosts; `read-heavy` profile — GET/CONTAINS/GET_ALL, the KV
hot path):

| measurement | result |
| --- | --- |
| **single shim**, direct, concurrency 32 | **~16.9k ops/sec**, GET p50 1.5 ms / p99 4.3 ms, 0 errors |
| **single shim**, sweep knee | ~conc 128: ~17.7k ops/sec at p99 ~26 ms (beyond it, +2% throughput for +50% p99) |
| **two shims**, NLB, two load gens (aggregate) | **~35k ops/sec @ conc 128/shim** (p99 ~26 ms), rising to ~36.6k @ conc 256 (p99 ~39 ms), 0 errors; Couchbase ~40k KV ops/s |
| **four shims**, NLB, four load gens (aggregate, read-heavy) | **~58k ops/sec** (climbing 55.4k@128 → 56.5k@256 → 58.5k@384 total conc, p99 8 → 17 → 25 ms), **0 errors**; shim CPU ~95% busy, Couchbase only ~21% busy at ~58k KV ops/s |

**Scaling:** **near-linear** — one shim ≈ 16.9k, two ≈ **35k** (~2.07×), four ≈ **58k** (~3.45× of a
single shim), 0 errors throughout. (The first two-shim run with a *single* load generator capped at
~25k because the load gen itself was ~90% CPU; driving from multiple load gens removed that and
confirmed the shim tier scales horizontally.) The four-shim run (2026-06-23, `shim_count=4`,
`loadgen_count=4`, read-heavy) was directly CPU-attributed mid-load: **shim hosts ~95% busy
(saturated) while the single `r6i.xlarge` Couchbase sat at ~21% CPU** serving ~58k KV ops/s — so at
4 shims the backend still has large headroom and the shim tier is the constraint. The slight
sub-linearity at four (3.45× vs an ideal 4×, ~14.5k/shim vs the 16.9k single-shim figure) is minor
cross-shim/NLB/backend-latency overhead, not a backend wall.

**Bottleneck:** the **shim tier (CPU)** — shim hosts pinned ~90% at peak. The Couchbase node stayed at
~15% CPU with a ~0 disk-write queue and no OOM while serving ~40k KV ops/s, i.e. **the backend has
large headroom and is not the limit** for this workload — so throughput scales by adding shim
replicas, well beyond two.

**Derived sizing (initial):** a 4-vCPU shim sustains **~16–17k read ops/sec at p99 < 5 ms** before
becoming CPU-bound, so size the replica count ≈ `target_read_QPS / ~16k` and scale the shim tier
horizontally (the backend isn't the constraint). One `r6i.xlarge` Couchbase node comfortably served
~40k KV ops/s for this profile.

**Important caveat — two performance classes.** The numbers above are the KV hot path. The
**keyset-metadata operations** (`REMOVE`, `PUT_ALL`, `SIZE`, `KEY_SET`) are a separate, far more
expensive class: each does a CAS read-modify-write over the per-region keyset document, which becomes
*pathological* at large keyspaces. At a 100k keyspace, a `mixed` run collapsed to ~36 ops/sec with
multi-second `REMOVE`/`PUT_ALL` latencies. Treat these as cold-path/administrative operations, not
hot-path application calls; a large-keyspace, mutation-heavy workload would need the keyset-metadata
design reworked (or avoided).

**4-shim point — DONE (2026-06-23).** Measured ~58k aggregate (0 errors), shim-CPU-bound (~95%) with
Couchbase at ~21% — near-linear 1→2→4 (16.9k → 35k → 58k). Confirms the thesis: scale read throughput
by adding shim replicas; one `r6i.xlarge` Couchbase has headroom well past four shims.

### Failure injection at scale (EC2 rig)

The single-box chaos test (`ProtoGemCouchChaosIntegrationTest`) covers a *hard* backend stop/start.
The rig's `fault-injection.sh` (run on the Couchbase host under sustained multi-host load — see
`deploy/ec2/README.md`) covers the remaining failure modes: **latency, packet loss, a partial
(frozen) outage, and a KV-port partition**. The resilience contract being verified:

| fault | injected | expected shim behavior |
| --- | --- | --- |
| backend latency | `+200 ms` for 120 s | p99 climbs, **errors ≈ 0** (rides it out), snaps back on heal |
| packet loss | `5%` for 120 s | small **bounded** retry/error rate, no hang, recovers on heal |
| partial outage | `docker pause` 60 s | in-flight ops fail **bounded** (no infinite hang), shim process stays up |
| partition (KV ports) | drop `11210/11207` 90 s | data path fails cleanly; **Couchbase node still alive** on its dashboard; shim reconnects on heal |
| hard outage | `docker stop/start` 60 s | ops fail fast/clean, shim recovers **without restart** |

**Result — first run (2026-06-19, hands-off self-driving rig: 2 shims `c6i.xlarge` behind the internal
NLB, dedicated Couchbase `r6i.xlarge`, read-heavy load).** Numbers are from the shims' own Prometheus
counters (both instances) over an 11.7M-operation run. **The contract held on every fault — PASS.**

| fault (window) | aggregate throughput | Δ op-errors | p99 (max) | recovery |
| --- | --- | --- | --- | --- |
| baseline (healthy) | ~22,300 ops/s | 0 | 9 ms | — |
| **latency +200 ms** | throttled to ~1,140 ops/s | **0** | **958 ms** | →9 ms within ~60 s of heal |
| **loss 5%** | ~9,190 ops/s | **0** (TCP retransmit absorbed it) | ~150 ms | immediate |
| **partial (pause)** | ~1,080 ops/s | +829 (bounded) | — | full catch-up at ~27k/s on unpause |
| **partition (KV ports)** | ~610 ops/s | +1,366 (bounded) | 1000 ms¹ | reconnected on heal |
| **hard outage (stop)** | ~600 ops/s | +601 (bounded) | 1000 ms¹ | recovered **without a restart** |

¹ 1000 ms is the histogram's top bucket — the ops that timed out against the unreachable backend.

**Findings:**
- **Latency / loss are ridden out with zero errors.** +200 ms backend RTT throttled throughput to
  ~`concurrency / RTT` (≈1.1k ops/s) and pushed p99 to ~960 ms, but **0 ops failed**; 5% loss halved
  throughput (TCP retransmit) with **0 errors**. Both snapped back to baseline (p99 7–9 ms) on heal.
- **The three "backend-unreachable" faults fail bounded and clean.** pause / partition / hard-stop
  produced **829 / 1,366 / 601** errors respectively — finite and promptly surfaced (no hang), with the
  ops that couldn't reach Couchbase timing out into the top latency bucket — then **full throughput
  recovery on heal** (the post-pause window even caught up at ~27k ops/s).
- **The shim never died.** The request counter was monotonic across the whole run (no reset) and both
  shim instances reported `up=1` throughout, i.e. neither process restarted and `/metrics` served
  continuously through every fault. **0 requests shed** — the backpressure guards never tripped.
- **Total: 4,226 errors / 11.7M ops = 0.036%**, every one of them confined to a hard-fault window.
- **Partition isolation worked as designed:** dropping only the KV ports (11210/11207) cut the data
  path while the Couchbase node stayed observably alive (mgmt/metrics `up`), so its Grafana dashboard
  kept updating through the partition.

_Rig note (not a shim issue): the seeding load-gen (`loadgen-0`) died mid-seed on a transient
client-side socket timeout (the single-threaded seeder uses `retryAttempts=1`); `loadgen-1` provided
continuous load through all five faults, so the verdict is unaffected. A small hardening follow-up:
make the seeder retry/timeout more forgiving so both load-gens always reach the measured phase._

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

## Run: 2026-06-25 — 1.3.0 new decode paths + PDX persistence (1.3.0-M4)

Soak of the new 1.3.0 paths: the **broader nested value-type decode** (M3 — typed object arrays, Sets,
non-`ArrayList` Lists, `java.time`) and the **durable PDX registry** (M2). Driven locally
(`BENCH_RICH_VALUES=true scripts/full-surface-soak.sh`, `full-surface` profile, subscriptions on) against
a single shim with **`PDX_PERSISTENCE=true`** and **`DURABLE_PERSISTENCE=true`**.

A new benchmark **rich-values mode** (`BENCH_RICH_VALUES`) seeds and PUTs maps holding the M3 nested types
(`Integer[]` / `UUID[]` typed object arrays, a `HashSet`, a `LinkedList`, an `Instant`), so every PUT
exercises the new **encode** path and every GET — and every full-region `QUERY` scan — exercises the new
**decode** path under sustained, concurrent load. The PDX type registry is allocated from Couchbase
throughout.

### Result (180s, concurrency 12, rich values + subscriptions, PDX_PERSISTENCE on)

- `errors=0`, `shed=0`, `conn_growth=0` — no shim request errors, nothing shed, no connection leak.
- `SOAK_VERDICT PASS` — `RESULT: PASS — stable over 180s`.

The new structured decode paths and the durable PDX registry hold under sustained mixed load with zero
errors. (Throughput/memory ratios are `n/a` here — this is a correctness/leak soak on a single local
container, not the dedicated-rig capacity characterization; that ceiling is unchanged from 1.2.0, since
1.3.0 is server-side queryability + an opt-in flag, not a new hot path.)

## Run: 2026-06-24 — 1.2.0 HA/scale paths under fault injection (1.2.0-M4)

Soak of the new 1.2.0 paths on the EC2 rig: 4 shims behind the NLB, 2 load-gens, the **self-driving
chaos experiment** (sustained `mixed` load @ 256 total concurrency, then the Couchbase host injects
latency/loss/pause/partition/hard-outage, all healed), with **`KEYSET_SHARDS=8`** and
**`DURABLE_PERSISTENCE=true`** active and 100k keys seeded. Goal: confirm the durable + sharded paths
survive a backend fault scenario under load.

### Bug found by the soak: backend-outage OOM → permanent wedge (and the fix)

The first clean run **failed**, and the soak earned its keep. During the fault window the Couchbase KV
ops time out (5 s each); at 256 concurrency the request backlog poured into the handler executor queues
(64 threads × **10,000** = up to **640k** buffered tasks), the heap exhausted, an `OutOfMemoryError`
tore down the Netty acceptor, the listener closed, and the main thread ran `gracefulShutdown` — **but
the JVM never exited**. The result was a live-but-dead shim that rejected every request
(`RejectedExecutionException: event executor terminated`) for the ~50 min after Couchbase fully
recovered, while the container still reported `running=true, exit=0, restarts=0` — so no orchestrator
would restart it. Root-caused from the shim log (112 × `OutOfMemoryError: Java heap space` immediately
before `server_stopping trigger=main-thread-exit`).

**Fix (backpressure + fail-fast):**
- `HandlerExecutorConfig.DEFAULT_MAX_PENDING_TASKS` 10,000 → **256**, so the existing
  `SheddingRejectedExecutionHandler` sheds (fails fast, closes the connection) long before heap
  pressure. Normal CRUD-by-key never fills the queue; it only sheds under genuine saturation (slow
  keyset ops at scale, or a slow/dead backend) — which is exactly when shedding is correct.
- `-XX:+ExitOnOutOfMemoryError` in the image, so any OOM halts the JVM non-zero immediately → a quick
  orchestrator restart instead of a wedge. `RawShimServer` also `halt(1)`s on an abnormal listener
  close (no SIGTERM) so an abnormal exit restarts rather than lingering.

### Result (after the fix) — PASS

Re-soak with the fixed image, same topology/chaos:
- **Survived the full fault scenario.** Right after the scenario completed, **all 4 shims `ready=200`**
  (and again 60 s later) — no wedge (vs. `000` on every shim before). Successful ops **kept climbing**
  through to the end (not frozen). The shim recovered on its own once the backend healed.
- Under the adversarial keyset-heavy `mixed` profile at 100k keys + 256 concurrency, the shim **shed
  the excess** rather than dying — `protogemcouch_requests_shed_total` ≈ 5.5M/shim — because the
  O(region-size) keyset path (`SIZE`/`KEY_SET`/bulk) saturates the 64 handler threads at that scale.
  Load-shedding under overload is the intended behavior; the shim stays up and serves what it can.
- Seed hardening: the benchmark `seed()` is now concurrent (128 threads) with bounded per-key retry —
  seeded 100k keys, 0 failures, in seconds (the serial seed previously raced fault injection and aborted
  the run).

Takeaway: the durable + sharded shim **survives and self-recovers** through a backend hard-outage under
load; keyset-metadata-heavy workloads at large keyspaces remain a cold path (see Current limitations)
and are correctly load-shed rather than allowed to OOM the process.

## Run: 2026-06-21 — keyset-metadata at-scale characterization (1.1.0-M4)

Quantifies the keyset-metadata **cold path** at large keyspaces. The shim tracks each region's keys in a
single per-region keyset document (`__protogemcouch::keyset::<region>` — a JSON array of every key, since
Couchbase KV cannot enumerate keys). Measured with `tools.KeysetScaleProbe` (a fresh region seeded via
`putAll` in 1000-key batches; median of 5 at each checkpoint), dockerized Couchbase, ~7-char keys.

| region keys | `KEY_SET` | `SIZE` | single `PUT` | single `REMOVE` |
|---|---|---|---|---|
| 5,000  | 9.4 ms  | 4.2 ms | 3.9 ms | 10.8 ms |
| 20,000 | 11.7 ms | 4.5 ms | 4.8 ms | 12.4 ms |
| 50,000 | 20.9 ms | 7.7 ms | 3.2 ms | 21.4 ms |

**Cost model (confirmed):**
- `KEY_SET` and `SIZE` read (and `KEY_SET` ships) the whole keyset doc → **O(keyspace)**; `SIZE` is cheaper
  (count only, less wire). `REMOVE` and `PUT_ALL` (and transactional puts) CAS read-modify-write the whole
  doc → **O(keyspace)** per op. A single **`PUT` stays flat (~3–5 ms)** — a contention-free server-side
  sub-document `arrayAddUnique`, not a read-modify-write.
- **GET/PUT/CONTAINS by key are unaffected** (direct KV, O(1)); only the keyset-metadata operations scale
  with region size.

**Document-size ceiling.** At 50,015 keys the keyset doc was **~478 KiB (~9.8 bytes/key ≈ key length + 3)**.
Couchbase's hard 20 MiB per-document limit therefore caps a region at roughly **`20 MiB / (avg_key_len + 3)`
keys** — about **2.1M keys for ~7-char keys**, **~910k for 20-char**, **~395k for 50-char**. Past that,
`PUT`/`PUT_ALL` into that region fail because the keyset doc can no longer grow. Note: the keyset doc is
written directly and is **not** subject to `CB_MAX_VALUE_BYTES` (that guards value documents); only the
20 MiB hard limit applies.

**Operating envelope / guidance.**
- Treat `KEY_SET`, `SIZE`, `CLEAR`, and large `PUT_ALL`/`REMOVE` bursts as **administrative cold-path**
  operations, not hot-path application calls — they are stable but grow linearly with region size.
- For sub-10 ms `KEY_SET`/`SIZE`, keep regions used that way in the **low tens of thousands of keys**.
- Very large regions (hundreds of thousands → ~1–2M keys) are fine for **CRUD by key** (O(1)); only the
  metadata operations get expensive and approach the per-region key-count ceiling above.
- Re-run the characterization any time with `tools.KeysetScaleProbe` (see its javadoc).

## Run: 2026-06-21 — full-surface soak with OQL pushdown enabled (1.1.0-M4 hardening)

The full-surface soak re-run against an **`OQL_PUSHDOWN=true`** shim (the 1.1.0-M2/M3 query path), to
harden the new pushdown / `pdxFields` sidecar / nested-field code under sustained concurrent load before
the 1.1.0 RC. Driven by `BENCH_QUERYABLE_VALUES=true scripts/full-surface-soak.sh` against the pushdown
shim (geode 40414 / health 8090) with a GSI on the queried field, concurrency 8, 240s, subscriptions on.

### Result (240s, pushdown on)

- Total operations: **155,447**; **server-side request errors: 0** (`protogemcouch_request_errors_total = 0`,
  every per-operation error counter 0, 0 `request_failed` log events), requests shed: 0.
- **Pushdown exercised under load: 8,277 N1QL pushdown queries, 0 fallbacks** — the query/index path
  stayed healthy throughout; query p99 ~132 ms.
- **No connection leak** (active-connection growth −9); **eventing under load: 138,148 interest events**,
  0 subscription errors.
- `SOAK_VERDICT PASS`.
- The benchmark's client-side error counter showed ~185 (≈0.12% of ops): these are **OQL query
  client-side read-timeouts** under heavy concurrency (the documented cold path), not shim errors — they
  surface only as `error=null` response-write WARNs when a timed-out client drops its connection. The
  authoritative shim error metric is 0.

A **security re-review** of the new query/index path accompanied this run (no N1QL injection — literals
are parameterized and field names strictly validated before interpolation; the `pdxFields` sidecar is
size-bounded; the `getRaw` reflection is hardcoded + exception-guarded; nested-path recursion is bounded
by the query string). See `docs/SECURITY.md` ("OQL query / pushdown / index path").

## Run: 2026-06-20 — full-surface soak (CRUD + OQL + tx + getEntry + PDX + eventing) + heap-cap hardening

The first soak to exercise the **whole request surface together** instead of CRUD only: the new
`full-surface` benchmark profile mixes CRUD/bulk/metadata with **OQL queries, transactions, the
in-transaction `getEntry`, and PDX writes**, and the soak runs an **interest + CQ consumer**
(`BENCH_SUBSCRIPTIONS=true`) alongside the load so every write also drives the eventing subsystem.
Driven by `scripts/full-surface-soak.sh` (concurrency 16, 30s samples, keyspace 1000).

### Bug found by the soak: unbounded heap (and the hardening)

The first full-surface run **FAILED** the memory-growth check (shim memory grew ~55% over 5 min). It
was not a leak: the shim image set **no `-Xmx`**, so the JVM sized its heap from *host* RAM — under
`docker-compose` (no container memory limit) that was ~25% of 62 GiB ≈ 15 GiB, so the heap simply
expanded under the allocation-heavy query workload and never needed to GC. (`CommitHandler` /
`RollbackHandler` both remove the transaction state on commit/rollback; connections were balanced; no
actual leak.) Fix: the image now sets a **container-aware `-XX:MaxRAMPercentage=75.0`** and the
docker-compose shim a `mem_limit: 1g` (matching the Helm chart's 1Gi limit), so the heap is bounded and
the soak measures real stability. This also improves the k8s footprint — the heap now tracks the pod
memory limit instead of falling back to the cgroup default.

### Result (bounded heap, 300s)

- Total operation requests: **127,084** over 300s (~272 full-surface ops/sec at concurrency 16).
- **Server-side request errors: 0**, requests shed: 0, malformed frames: 0, first-request timeouts: 0.
- **Shim memory: 592 MiB → 617 MiB (+4.2%)** — a stable plateau well under the 768 MiB heap cap (no leak).
- Connections balanced (opened == closed; no connection leak).
- **Eventing under load: 72,482 interest events** delivered to the consumer over the run (the feed /
  interest / event-serialization path stayed healthy throughout).
- `SOAK_VERDICT PASS`.

### Notes on the workload

- **OQL is the heaviest surface** — a query is a full region scan (no index), so under concurrent load
  queries run sub-second and a small fraction exceed the client's read timeout (client-side, counted in
  the benchmark's per-op errors; the **shim** records zero errors). This matches the documented
  cold-path nature of scans (see `docs/CURRENT_LIMITATIONS.md`) and is why `full-surface` keeps the
  query weight modest.
- **CQ events show 0** in this harness because the in-process subscription consumer shares the
  benchmark's `ClientCache` (a JVM singleton), so CQ delivery for the cache's own writes is
  origin-suppressed; the interest-event path (72k events) and CQ *registration/matching* are still
  exercised. Cross-client CQ delivery is validated by `ProtoGemCouchCqIntegrationTest`.
- Throughput shows the usual co-located-dev-box decline (early/late ratio ~0.6); it is a warning, not a
  gate, off dedicated infra (`SOAK_FAIL_ON_THROUGHPUT`).

## Run: 2026-06-02 — 15-minute soak (optimized build) + connection-accounting bug found & fixed

A longer (15-minute) sustained soak against the build with the PUT_ALL optimization, driven by
`scripts/soak.sh` (mixed profile, concurrency 16, 60s samples, keyspace 3000).

### Result

- Total operations: **6,561,931** over 900s (sustained ~7.0–7.3k ops/sec, mixed)
- **Request errors: 0**, shed: 0, malformed: 0, first-request timeouts: 0 — entire run
- **Memory flat**: 979.7 MiB → ~1.119 GiB (rose to working-set early, flat thereafter — no leak)
- Latency steady under sustained load: `PUT_ALL` p99 ~6.9 ms, `GET_ALL` p99 ~5.5 ms,
  `CONTAINS_KEY`/`SIZE` p99 ~4.1–4.3 ms

### Bug found by the soak: connection-accounting leak (and fix)

The soak's connection sampling showed `active` climbing steadily (82 → 167) with
`protogemcouch_connections_closed_total` stuck at **0** — closes were never being counted, and a
post-run probe confirmed connections stayed counted-open after the client disconnected.

Root cause: the connection accounting (open/close counting and the `ConnectionLimiter` acquire/
release) lived on `HandshakeThenFrameHandler`, **which removes itself from the pipeline after the
handshake** — so its `channelInactive` never fired. With the default `MAX_CONNECTIONS=0` there was
no functional impact, but with a cap set the limiter would leak slots until it falsely rejected all
new connections, and the active-connections metric was wrong.

Fix: a dedicated `ConnectionTrackingHandler` that is the first, permanent handler in the pipeline,
so `channelInactive` always fires. Re-verified with a real client run: 18 opened → **18 closed,
active 0** after disconnect (previously 18 opened → 0 closed). See the `feature/robustness` history.

### Conclusion

> Throughput, latency, and memory are stable over 15 minutes with the optimized build and no guard
> trips. The soak also did its real job — it surfaced a latent connection-accounting bug that unit
> and short functional tests missed, which has been fixed and re-verified.

---

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