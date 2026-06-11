# CURRENT_LIMITATIONS

## Summary

`ProtoGemCouch` has reached a broader compatibility milestone, but it is still not a full Apache Geode server replacement.

The current implementation supports a validated end-to-end path for core region operations, Couchbase persistence, typed value envelopes, scalar values, primitive arrays, selected collections/maps, Serializable POJOs, `Object[]`, `ArrayList<Object>`, standalone Java utility values, wrapper/utility arrays, PDX / `PdxInstance` round-tripping, and large collection boundary behavior.

Broader Geode server behavior, advanced distributed semantics, full PDX registry behavior, and production-hardening gaps still remain.

---

## 1. Scope Is a Compatibility Profile, Not Full Geode Parity

Validated operation paths include:

- connect / handshake
- region access
- put
- get
- putAll
- getAll
- remove
- containsKey / containsKeyOnServer
- containsValueForKey
- sizeOnServer
- keySetOnServer
- unsupported / unknown opcode logging

Not yet validated or implemented:

- queries
- transactions
- continuous queries
- interest registration
- server-side function *execution* (the shim cannot run user `Function` code; it rejects function
  calls gracefully so clients get a clean exception — see `docs/FUNCTIONS.md`)
- full partitioned-region metadata behavior
- listener/callback/event semantics
- full distributed-region semantics
- full native Geode server replacement behavior

---

## 2. Response Type Fidelity Has Improved but Is Still Scoped

Validated value families include:

- primitive wrappers
- `java.util.Date`
- `byte[]`
- primitive arrays
- `String[]`
- wrapper arrays such as `Integer[]`, `Long[]`, `Boolean[]`, `Double[]`
- utility arrays such as `UUID[]`, `BigInteger[]`, `BigDecimal[]`, `Enum[]`, `Instant[]`, `LocalDate[]`, `LocalDateTime[]`
- `ArrayList<String>`
- `HashMap<String,String>`
- `HashMap<String,Object>`
- Serializable POJOs
- `Object[]`
- `ArrayList<Object>`
- UUID
- BigDecimal / BigInteger
- Enum
- `java.time` values
- PDX / `PdxInstance`

Still not broadly validated:

- DataSerializable
- arbitrary Java object graphs beyond the validated opaque preservation paths
- all nested Java serialization boundary cases
- all customer classloading scenarios
- full PDX registry discovery and all advanced PDX server semantics

---

## 3. Opaque vs. Structural Storage Is Intentional

Structural storage:

- primitive wrappers
- `java.util.Date`
- `byte[]`
- primitive arrays
- `String[]`
- `ArrayList<String>`
- `HashMap<String,String>`
- `HashMap<String,Object>`

Opaque storage:

- Serializable POJO
- `Object[]`
- wrapper / utility arrays
- `ArrayList<Object>`
- standalone opaque utility values such as UUID / BigInteger / BigDecimal / Enum
- PDX / `PdxInstance`

Opaque storage preserves client compatibility but limits Couchbase-side queryability of the internal object structure.

---

## 4. Structured Map Support Has Known Nested Gaps

`HashMap<String,Object>` supports a useful set of nested structured values:

```text
null
String
Boolean
Character
Byte
Short
Integer
Long
Float
Double
java.util.Date
byte[]
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
String[]
ArrayList<String>
```

The following are not yet supported inside structured `HashMap<String,Object>` envelopes:

```text
Object[]
Serializable POJO
ArrayList<Object>
wrapper / utility arrays
opaque standalone utility values
PDX / PdxInstance
```

Top-level `Object[]`, top-level `ArrayList<Object>`, top-level Serializable POJOs, top-level wrapper/utility arrays, top-level standalone utility values, and top-level PDX values are supported.

---

## 5. Collection Encoding Compatibility Is Explicit but Narrow

The shim now explicitly handles the two known collection count encodings used in the current supported paths:

```text
keySetOnServer / list-style payloads -> Geode array/list length encoding
GET_ALL / VersionedObjectList        -> unsigned variable-length integer count encoding
```

Validated boundaries:

```text
>127 keys / entries
>252 keys / entries
```

This does not imply complete coverage for every Geode collection type or every DataSerializer marker.

---

## 6. Geode DataSerializer Compatibility Is Still Selective

The shim uses deterministic decoding for many validated payloads and opaque preservation for more complex payloads.

Still not covered:

- every Geode DataSerializer marker
- custom DataSerializable implementations
- all nested Java serialization boundary cases
- all customer classloading scenarios
- full PDX registry discovery behavior

---

## 7. Public API Behavior Requires Careful Positioning

Native protocol paths for supported operations have been validated through real Geode client integration tests.

Some public Geode client API methods may resolve locally depending on client region configuration, shortcut type, and local state.

Support claims should remain precise:

```text
The shim validates specific native protocol paths and supported client behavior.
It does not guarantee all public Geode API behavior across all region configurations.
```

---

## 8. Production Readiness Is Still Partial

Already present:

- structured logging
- startup validation
- Docker-backed integration environment
- deployment packaging
- compatibility matrix
- current limitations documentation
- repeatable test suite
- response-writer unit coverage for risky collection encodings
- health/readiness endpoints

Still missing for true production readiness:

- external metrics endpoint
- operation latency/counter metrics
- broader native Geode wire compatibility validation
- formal support contract
- stronger failure-mode tests
- performance characterization under realistic load against the latest PDX/boundary baseline
- concurrency and soak validation against the latest compatibility baseline
- final deployment and security hardening

---

## Current Positioning

The project should currently be described as:

> **A working Geode-to-Couchbase protocol shim prototype with successful end-to-end validation for core region operations and a broad supported type profile, including primitive wrappers, primitive arrays, wrapper/utility arrays, standalone utility values, selected collections/maps, Serializable POJOs, `Object[]`, `ArrayList<Object>`, and PDX / `PdxInstance`, backed by Docker-based real-client integration tests, but not yet a fully native Geode-compatible server replacement.**

---

## Immediate Next Priority

The next highest-priority engineering task is:

```text
observability hardening
```

Recommended next targets:

- operation counters
- success/error counters
- latency tracking
- response byte-size tracking
- serialization error diagnostics
- connection close reason logging
- `/metrics/json`
- Prometheus-format `/metrics`
