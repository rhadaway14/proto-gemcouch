# CURRENT_LIMITATIONS

## Summary

`ProtoGemCouch` has reached a broader compatibility milestone, but it is not yet a full Apache Geode server replacement.

The current implementation now supports a validated end-to-end path for core region operations, Couchbase persistence, typed value envelopes, simple scalar values, primitive arrays, selected collections/maps, Serializable POJOs, `Object[]`, and `ArrayList<Object>`.

Broader Geode server behavior, advanced serialization modes, distributed semantics, and production-hardening gaps still remain.

---

## 1. Scope Is Still a Compatibility Profile, Not Full Geode Parity

Validated operation paths include:

- connect / handshake
- region access
- put
- get
- putAll
- getAll
- remove
- containsKey
- sizeOnServer
- keySetOnServer
- unsupported / unknown opcode logging

Not yet validated or implemented:

- queries
- transactions
- continuous queries
- interest registration
- server-side functions
- full partitioned-region metadata behavior
- listener/callback/event semantics
- full distributed-region semantics

---

## 2. Response Type Fidelity Has Improved but Is Not Universal

Validated value families include:

- primitive wrappers
- `java.util.Date`
- `byte[]`
- `boolean[]`
- `char[]`
- `short[]`
- `int[]`
- `long[]`
- `float[]`
- `double[]`
- `String[]`
- `ArrayList<String>`
- `HashMap<String,String>`
- `HashMap<String,Object>`
- Serializable POJOs
- `Object[]`
- `ArrayList<Object>`

Not yet validated:

- wrapper arrays such as `Integer[]`, `Long[]`, `Boolean[]`, `Double[]`
- BigDecimal / BigInteger
- UUID
- Enum
- `java.time` values
- PDX / PdxInstance
- DataSerializable
- arbitrary Java object graphs
- nested opaque object values inside structured map envelopes

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
- `ArrayList<Object>`

Opaque storage preserves compatibility but limits Couchbase-side queryability of the internal object structure.

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
```

Top-level `Object[]`, top-level `ArrayList<Object>`, and top-level Serializable POJOs are supported.

---

## 5. Geode DataSerializer Compatibility Is Still Selective

The shim uses deterministic decoding for many validated payloads and opaque preservation for more complex payloads.

Still not covered:

- every Geode DataSerializer marker
- custom DataSerializable implementations
- PDX / PdxInstance
- all nested Java serialization boundary cases
- all customer classloading scenarios

---

## 6. Public API Behavior Requires Careful Positioning

Native protocol paths for supported operations have been validated through real Geode client integration tests.

Some public Geode client API methods may resolve locally depending on client region configuration, shortcut type, and local state.

Support claims should remain precise:

```text
The shim validates specific native protocol paths and supported client behavior.
It does not guarantee all public Geode API behavior across all region configurations.
```

---

## 7. Production Readiness Is Still Partial

Already present:

- structured logging
- metrics
- startup validation
- Docker-backed integration environment
- benchmark and soak testing work
- deployment packaging
- runbooks and launch criteria
- compatibility matrix
- current limitations documentation
- repeatable test suite

Still missing for true production readiness:

- broader native Geode wire compatibility validation
- formal support contract
- stronger failure-mode tests
- performance characterization under realistic load
- concurrency and soak validation against the current compatibility baseline
- final deployment and security hardening

---

## Current Positioning

The project should currently be described as:

**A working Geode-to-Couchbase protocol shim prototype with successful end-to-end validation for core region operations and a broad supported type profile, including primitive wrappers, primitive arrays, selected collections/maps, Serializable POJOs, `Object[]`, and `ArrayList<Object>`, backed by Docker-based real-client integration tests, but not yet a fully native Geode-compatible server replacement.**

---

## Immediate Next Priority

The next highest-priority engineering task is:

## Expand wrapper-array and common Java utility type coverage

Recommended next targets:

- `Integer[]`
- `Long[]`
- `Boolean[]`
- `Double[]`
- UUID
- BigDecimal
- BigInteger
- Enum
- `java.time.Instant`
- `java.time.LocalDate`
- `java.time.LocalDateTime`
