# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client
requests and translates them into Couchbase operations. The goal is to let an existing Java Geode client
application change only its connection endpoint while the shim handles protocol translation and persists
data in Couchbase.

## Current status

**ProtoGemCouch 1.2.0 — latest GA (2026-06-24)** (1.0.0 GA 2026-06-20; 1.1.0 2026-06-21). 1.2.0 adds
multi-replica durable-subscription HA, keyset-metadata sharding, hot TLS reload, structured nested
`java.time`, and resilience hardening. The authoritative, maintained references are:

- `docs/COMPATABILITY_MATRIX.md` — the supported-surface contract (what works today) + deliberate non-goals.
- `docs/CURRENT_LIMITATIONS.md` — plain-English summary of what the shim is *not*.
- `docs/CONFIGURATION.md` — consolidated environment-variable reference (every operator-facing setting + default).
- `CHANGELOG.md` — released history.
- `docs/ROADMAP.md` — the post-GA backlog (current focus: 1.3.0).

This file is a feature/encoding overview; where it disagrees with the matrix, the matrix wins.

Verification: `mvn -o test` (574 unit tests + coverage gate) and `mvn -o verify` (Docker-backed,
real-Geode-1.15-client integration suite, 257 tests). The signed `v*` release pipeline runs the full
`mvn verify`, the perf-regression gate, and a Trivy/SBOM/cosign image publish.

## Supported operations

See `docs/COMPATABILITY_MATRIX.md` for the full contract. In brief, the validated surface includes:

- Connect / handshake (with protocol-version negotiation).
- `get` / `put` / `remove` / `containsKey` / `getEntry` (in-transaction) / `invalidate`.
- `putAll` / `getAll`.
- `sizeOnServer` / `keySetOnServer`.
- `clear` / `destroyRegion`.
- OQL queries (`WHERE` / projection / `ORDER BY` / paging / parameters, incl. PDX field access).
- Transactions (begin / commit / rollback, read-your-writes, atomic commit).
- Continuous queries (CQ) and register-interest subscriptions (server-pushed events).
- PDX (incl. schema evolution and registry discovery).
- Durable subscription clients (single-instance).
- Graceful rejection of server-side functions; unknown-opcode logging.

## Supported value types

- **Scalars / wrappers:** `String`, `Boolean`, `Character`, `Byte`, `Short`, `Integer`, `Long`,
  `Float`, `Double`, `java.util.Date`.
- **JDK utility scalars:** `UUID`, `BigInteger`, `BigDecimal`, `Enum`, `java.time.Instant`,
  `java.time.LocalDate`, `java.time.LocalDateTime`.
- **Primitive arrays:** `byte[]`, `boolean[]`, `char[]`, `short[]`, `int[]`, `long[]`, `float[]`,
  `double[]`.
- **Typed / object arrays:** `String[]`, `Integer[]`, `Long[]`, `Boolean[]`, `Double[]`, `UUID[]`,
  `BigInteger[]`, `BigDecimal[]`, `Enum[]`, `Instant[]`, `LocalDate[]`, `LocalDateTime[]`, `Object[]`.
- **Collections / maps:** `ArrayList<String>`, `ArrayList<Object>`, `HashMap<String,String>`,
  `HashMap<String,Object>` (with recursively-nested supported values).
- **Opaque preservation** (round-trips exactly, not queryable on contents): `Serializable` POJOs,
  `PDX` / `PdxInstance`, custom `DataSerializable`.

## Primitive array support

Primitive arrays are decoded structurally from Geode DataSerializer primitive-array payloads, stored as
typed JSON array envelopes, and re-encoded as Geode-compatible payloads on the way back. Markers:

```text
boolean[]  -> 0x1a
char[]     -> 0x1b
byte[]     -> 0x2e
short[]    -> 0x2f
int[]      -> 0x30
long[]     -> 0x31
float[]    -> 0x32
double[]   -> 0x33
```

Example:

```java
int[] value = new int[] {1, 42, -7, Integer.MAX_VALUE, Integer.MIN_VALUE};
region.put("int-array-demo-key", value);
Object actual = region.get("int-array-demo-key");
```

Couchbase envelope:

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

## Wrapper and utility array support

Wrapper and utility arrays are preserved through generalized `0x34` object-array preservation: the shim
stores and returns the full `0x34...` payload so the Geode client deserializes the original array type.

```text
Integer[]        -> 0x34 ... java.lang.Integer ...
Long[]           -> 0x34 ... java.lang.Long ...
Boolean[]        -> 0x34 ... java.lang.Boolean ...
Double[]         -> 0x34 ... java.lang.Double ...
UUID[]           -> 0x34 ... java.util.UUID ...
BigInteger[]     -> 0x34 ... java.math.BigInteger ...
BigDecimal[]     -> 0x34 ... java.math.BigDecimal ...
Enum[]           -> 0x34 ... <enum-class> ...
Instant[]        -> 0x34 ... java.time.Instant ...
LocalDate[]      -> 0x34 ... java.time.LocalDate ...
LocalDateTime[]  -> 0x34 ... java.time.LocalDateTime ...
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 123
}
```

## Standalone utility value support

Standalone utility values are preserved through either a dedicated opaque Geode marker or the
Java-serialized-object path. Markers:

```text
BigInteger              -> 0x5f
BigDecimal              -> 0x60
UUID                    -> 0x62
Enum                    -> 0x65
java.time.Instant       -> 0x2c (Java serialized)
java.time.LocalDate     -> 0x2c (Java serialized)
java.time.LocalDateTime -> 0x2c (Java serialized)
```

Example:

```java
UUID value = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
region.put("uuid-demo-key", value);
Object actual = region.get("uuid-demo-key");
```

Couchbase envelope:

```json
{
  "type": "opaqueGeodeValue",
  "opaqueGeodeTypeName": "uuid",
  "valueBase64": "YhI+RWfomxLTpFZCZhQXQAA=",
  "length": 17
}
```

## ArrayList<Object> support

`ArrayList<Object>` is preserved as an opaque Geode DataSerializer list payload. Observed wire shape:

```text
41 <length> <elements...>
```

Decode rule: `ArrayList<String>` is decoded first into the structured `stringArrayList` format; if that
fails and the payload starts with `0x41`, the value is treated as an opaque `ArrayList<Object>`.

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

## Object[] support

`Object[]` and component-specific object arrays are preserved as opaque Geode DataSerializer payloads.
Observed wire shape:

```text
34 <length> 2b <component-type-string> <elements...>
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

## Serializable POJO support

`Serializable` POJOs are preserved opaquely — the Geode marker is stripped for storage and reattached on
return, so the client re-instantiates the object via its own class:

```text
Client sends:  2c ac ed 00 05 ...
Stored bytes:     ac ed 00 05 ...
Returned:      2c ac ed 00 05 ...
```

Couchbase envelope:

```json
{
  "type": "javaSerializedObject",
  "className": "com.example.CustomerProfile",
  "valueBase64": "rO0ABXNy...",
  "length": 218
}
```

## Architecture

```text
Java Geode Client
        |
        | Geode client protocol
        v
ProtoGemCouch Shim
        |
        | Couchbase Java SDK
        v
Couchbase
```

## Main packages

- `com.protogemcouch.wire` — protocol frame and response encoding.
- `com.protogemcouch.ops` — operation handlers.
- `com.protogemcouch.serialization` — typed value decoding and representation.
- `com.protogemcouch.couchbase` — repository and Couchbase persistence.

## Couchbase document examples

### Primitive array

```json
{
  "type": "booleanArray",
  "value": [true, false, true],
  "length": 3
}
```

```json
{
  "type": "charArray",
  "value": ["A", "Z", "0"],
  "length": 3
}
```

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

### Wrapper / utility array

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 123
}
```

### Standalone utility value

```json
{
  "type": "opaqueGeodeValue",
  "opaqueGeodeTypeName": "uuid",
  "valueBase64": "YhI+RWfomxLTpFZCZhQXQAA=",
  "length": 17
}
```

### ArrayList<Object>

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

### Object[]

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

### Serializable POJO

```json
{
  "type": "javaSerializedObject",
  "className": "com.example.CustomerProfile",
  "valueBase64": "rO0ABXNy...",
  "length": 218
}
```

### HashMap<String,Object>

```json
{
  "type": "stringObjectHashMap",
  "value": {
    "name": { "type": "string", "value": "rob" },
    "age": { "type": "integer", "value": 42 },
    "intItems": { "type": "intArray", "value": [1, 42, -7], "length": 3 }
  },
  "length": 3
}
```

## Running tests

Local unit tests:

```bash
mvn -o test
```

Docker-backed integration tests (Docker must be running):

```bash
docker ps
mvn -o verify
```

## Current validation scope

Validated end-to-end against a real Geode 1.15 client and a Docker-backed Couchbase:

- Primitive, wrapper, and utility-scalar round-trips (incl. `UUID` / `BigInteger` / `BigDecimal` /
  `Enum` / `java.time`).
- Primitive-array, typed/object-array, and `String[]` round-trips.
- `ArrayList<String>` / `ArrayList<Object>` and `HashMap<String,String>` / `HashMap<String,Object>`
  (with recursively-nested values) round-trips.
- `Serializable` POJO, `Object[]`, PDX (incl. schema evolution and registry discovery), and
  `DataSerializable` round-trips.
- OQL queries, transactions, continuous queries, and register-interest subscriptions.
- `GET_ALL` VersionedObjectList-compatible responses; large key-set boundary behavior.
- Full-surface soak and high-concurrency testing (see `docs/SOAK_RESULTS.md`).

Out of scope by design (documented non-goals — see `docs/CURRENT_LIMITATIONS.md`):

- Server-side execution of user code (functions; server-side cache callbacks).
- Full native Geode-server wire parity / a general-purpose drop-in replacement.
- Field-level querying of customer `Serializable` POJOs / `DataSerializable` (no schema without the
  classes).

## Next

Post-GA work is tracked in `docs/ROADMAP.md`. Shipped since 1.0: 1.1.0 (OQL pushdown, nested/array field
querying, registry observability) and 1.2.0 (multi-replica durable-subscription HA, keyset sharding, hot
TLS reload). Current focus is the **1.3.0** backlog (theme: parity completeness — full value-type
fidelity & queryability).
