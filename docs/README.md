
# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

## Current Status

**ProtoGemCouch 1.0.0 — first general-availability release (2026-06-20).** The authoritative, maintained
references are:

- `docs/COMPATABILITY_MATRIX.md` — the supported-surface contract (what works today) + deliberate non-goals
- `docs/CURRENT_LIMITATIONS.md` — plain-English summary of what the shim is *not*
- `CHANGELOG.md` — released history; `docs/ROADMAP.md` — the post-1.0 (1.1.0) backlog

This file is a feature/encoding overview; where it disagrees with the matrix, the matrix wins.

Verification: `mvn -o test` (574 unit tests + coverage gate) and `mvn -o verify` (Docker-backed,
real-Geode-1.15-client integration suite, 257 tests). The signed `v*` release pipeline runs the full
`mvn verify`, the perf-regression gate, and a Trivy/SBOM/cosign image publish.

## Supported Operations

See `docs/COMPATABILITY_MATRIX.md` for the full contract. In brief, the validated surface includes:

```text
connect / handshake (+ protocol-version negotiation)
get / put / remove / containsKey / getEntry (in-transaction) / invalidate
putAll / getAll
sizeOnServer / keySetOnServer
clear / destroyRegion
OQL queries (WHERE / projection / ORDER BY / paging / parameters, incl. PDX field access)
transactions (begin / commit / rollback, read-your-writes, atomic commit)
continuous queries (CQ) + register-interest subscriptions (server-pushed events)
PDX (incl. schema evolution + registry discovery)
durable subscription clients (single-instance)
graceful rejection of server-side functions; unknown-opcode logging
```

## Supported Value Types

```text
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
Integer[]
Long[]
Boolean[]
Double[]
UUID[]
BigInteger[]
BigDecimal[]
Enum[]
Instant[]
LocalDate[]
LocalDateTime[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
UUID
BigInteger
BigDecimal
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```

## Primitive Array Support

Primitive arrays are supported structurally using Geode DataSerializer primitive-array payloads.

Supported markers:

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
int[] value = new int[] {
    1,
    42,
    -7,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE
};

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

The shim decodes primitive arrays structurally, stores them as typed JSON array envelopes, and re-encodes them as Geode-compatible primitive-array payloads when returning data to the client.

## Wrapper and Utility Array Support

Wrapper and utility arrays are supported through generalized `0x34` object-array preservation.

Examples:

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

The shim stores and returns the full `0x34...` payload, allowing the Geode client to deserialize the original array type.

## Standalone Utility Value Support

Standalone utility values are supported through either dedicated opaque Geode marker preservation or the existing Java-serialized-object path.

Supported standalone utility markers:

```text
BigInteger              -> 0x5f
BigDecimal              -> 0x60
UUID                    -> 0x62
Enum                    -> 0x65
java.time.Instant       -> 0x2c Java serialized
java.time.LocalDate     -> 0x2c Java serialized
java.time.LocalDateTime -> 0x2c Java serialized
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

## ArrayList<Object> Support

`ArrayList<Object>` is supported as an opaque Geode DataSerializer list payload.

Observed wire shape:

```text
41 <length> <elements...>
```

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

Important decode rule:

```text
ArrayList<String> is decoded first into the structured stringArrayList format.
If that fails and the payload starts with 0x41, the value is treated as opaque ArrayList<Object>.
```

## Object[] Support

`Object[]` and component-specific object arrays are supported as opaque Geode DataSerializer payloads.

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

## Serializable POJO Support

Serializable POJOs are preserved opaquely.

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

## Main Packages

```text
com.protogemcouch.wire          protocol frame and response encoding
com.protogemcouch.ops           operation handlers
com.protogemcouch.serialization typed value decoding and representation
com.protogemcouch.couchbase     repository and Couchbase persistence
```

## Couchbase Document Examples

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
    "name": {
      "type": "string",
      "value": "rob"
    },
    "age": {
      "type": "integer",
      "value": 42
    },
    "intItems": {
      "type": "intArray",
      "value": [1, 42, -7],
      "length": 3
    }
  },
  "length": 3
}
```

## Running Tests

Local tests:

```powershell
mvn test
mvn clean test
```

Docker-backed serialization integration:

```powershell
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Docker must be running:

```powershell
docker ps
```

## Current Validation Scope

Validated:

```text
Primitive wrapper round trips
byte[] round trips
boolean[] round trips
char[] round trips
short[] round trips
int[] round trips
long[] round trips
float[] round trips
double[] round trips
String[] round trips
Wrapper / utility array round trips
ArrayList<String> round trips
HashMap<String,String> round trips
HashMap<String,Object> round trips
Serializable POJO round trips
Object[] round trips
ArrayList<Object> round trips
Standalone utility value round trips
java.util.Date round trips
Couchbase typed envelopes
GET_ALL VersionedObjectList-compatible responses
Docker-backed integration verification
```

Since this snapshot was written, the validated surface has expanded well beyond the list above —
DataSerializable, PDX (incl. schema evolution + registry discovery), transactions, OQL queries,
continuous queries, register-interest subscriptions, region lifecycle, nested complex values inside
`Map<String,Object>`, and full-surface soak testing are all covered. See
`docs/COMPATABILITY_MATRIX.md` for the current contract.

Out of scope by design (documented non-goals — see `docs/CURRENT_LIMITATIONS.md`):

```text
Server-side execution of user code (functions; server-side cache callbacks)
Full native Geode-server wire parity / general-purpose drop-in replacement
Field-level querying of customer Serializable POJOs / DataSerializable (no schema without the classes)
```

## Next

Post-1.0 work is tracked in `docs/ROADMAP.md` (the 1.1.0 backlog): OQL query pushdown (performance),
in-memory registry observability + bounds, and PDX nested/object/array field querying.
