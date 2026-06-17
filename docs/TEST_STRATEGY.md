# ProtoGemCouch Test Strategy

## Purpose

This document describes the testing approach for ProtoGemCouch, including unit tests, integration tests, protocol discovery, regression protection, and performance testing.

---

## 1. Test goals

The goals of the test strategy are:

- prove correctness of supported operations
- catch protocol regressions early
- validate end-to-end client compatibility for scoped flows
- document semantic gaps explicitly
- build confidence for launch readiness

---

## 2. Test pyramid

ProtoGemCouch uses a layered strategy:

### Unit tests
Fast tests for:
- utilities
- factories
- registry construction
- handler behavior with mocked inputs
- response writer behavior

Command:

```powershell
mvn test
```

### Integration tests
Docker-backed, end-to-end against a **real Geode 1.15 client** and a **real Couchbase** brought up by
the build (`*IntegrationTest.java`, JUnit 5 `@Tag("integration")`). These prove wire-protocol
correctness and client compatibility for the scoped flows.

Command:

```powershell
mvn verify
```

### Property-based round-trip tests
Serialization fidelity (the shim's core contract) is also checked combinatorially, not just by
example: a seeded generator (`com.protogemcouch.testsupport.RandomValueGraphs`) produces randomized
`HashMap<String,Object>` graphs across the full structured supported matrix (scalars, arrays, nested
`Object[]`/`ArrayList`/`Map`, UUID/BigInteger/BigDecimal/enum), and two harnesses assert equals-level
fidelity — `CouchbaseRepositoryRoundTripPropertyTest` (in-process, 2,000 iterations across a real
JSON-text persistence boundary) and `ProtoGemCouchRoundTripPropertyIntegrationTest` (put/get +
putAll/getAll through a real client). Each iteration is seeded and prints its seed on failure for
exact reproduction.

---

## 3. Code coverage (measurement + gate)

Unit-test line coverage is measured by **JaCoCo** and **gated** in the build, so coverage cannot
silently regress.

### How it runs
- The JaCoCo agent attaches to the surefire (unit-test) JVM via `prepare-agent` (wired through
  surefire's `@{argLine}`).
- `jacoco:report` (bound to the `test` phase) writes the HTML/CSV/XML report to
  `target/site/jacoco/` — open `target/site/jacoco/index.html`.
- `jacoco:check` (also bound to `test`) **fails the build** if line coverage of the gated surface
  falls below `jacoco.line.coverage.min` in `pom.xml`.

So `mvn test` (and CI's `build-test.yml`, which runs on every push and PR) both measure **and**
enforce coverage. CI also uploads the report as the `jacoco-coverage-report` artifact (even on
failure, so a regression is diagnosable).

### What is in scope — and the integration-only caveat
The gate measures the **unit-testable production surface**. Two categories are deliberately excluded:

1. **Dev-only packages** — `tools`, `probe`, `benchmark`, `samples` (capture utilities, the soak/perf
   harness, sample apps). Not shipped request-path code; excluded from both the report and the gate.
2. **`CouchbaseRepository`** — the live-Couchbase backend implementation (~1,600 lines). It is
   exercised end-to-end by the Docker-backed **integration** suite (`mvn verify`), but those tests
   drive the shim **inside its container**, so JaCoCo on the surefire JVM cannot observe that
   execution — it would otherwise be counted as "missed" forever and drag the number to a meaningless
   ~49%. It is excluded from the **gate** but **kept in the report**, so the (genuinely
   integration-only) gap stays visible rather than being hidden.

This is the honest reading: a unit-coverage gate cannot credit work that only the dockerized
integration suite covers. The integration suite is the separate, complementary proof of the
wire/backend paths (see §2 and the per-feature integration tests).

### The floor is a ratchet, not a target
`jacoco.line.coverage.min` is set just **below** the current measured coverage of the gated surface
(currently **~0.62**; floor **0.60**). Raise it as coverage improves to lock in gains; **never lower
it without a written reason** in the commit. To find today's number quickly:

```powershell
mvn -o test
# then read target/site/jacoco/index.html, or the bundle line in the jacoco:check output
```

A handler/parser/serializer with low coverage in the report is a concrete invitation to add a unit
test — that is the point of keeping the full surface visible.