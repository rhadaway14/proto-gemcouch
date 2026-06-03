# ProtoGemCouch Compatibility Matrix

## Current Milestone

```text
pdx-and-large-collection-boundary-support-complete
```

ProtoGemCouch now has validated end-to-end support for the current scoped Geode client compatibility profile, including:

```text
core CRUD-style operations
bulk PUT_ALL / GET_ALL operations
server-side metadata-style operations
typed scalar values
primitive arrays
wrapper / utility arrays
standalone utility values
Serializable POJOs
Object[]
ArrayList<Object>
PDX / PdxInstance round-tripping
large key-set and bulk collection boundary handling
```

The latest serialization hardening work added explicit coverage for collection count boundaries that previously caused client deserialization failures:

```text
keySetOnServer with more than 127 keys
keySetOnServer with more than 252 keys
getAll with more than 127 keys
getAll with more than 252 keys
putAll followed by getAll with more than 127 entries
putAll followed by getAll with more than 252 entries
```

## Latest Verification

Latest Docker-backed integration result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchPdxRegistryDiscoveryIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 3

ProtoGemCouchSerializationIntegrationTest
Tests run: 135, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3

BUILD SUCCESS
```

Recommended verification commands:

```powershell
mvn -Dtest=GemResponseWriterTest test
mvn clean verify
```

## Supported Operations

| Operation | Status | Notes |
|---|---:|---|
| connect / handshake | Supported | Java Geode client connects to the shim. |
| region access | Supported | Validated against the configured region, commonly `helloWorld`. |
| `put` | Supported | Supports all currently validated value families. |
| `get` | Supported | Returns Geode-compatible typed values. |
| `putAll` | Supported | Supports batch typed values and large-entry boundary coverage. |
| `getAll` | Supported | Uses a VersionedObjectList-compatible response payload with unsigned variable-length counts. |
| `remove` | Supported | Removes mapped Couchbase document. |
| `containsKey` / `containsKeyOnServer` | Supported | Repository-backed existence check. |
| `containsValueForKey` | Supported | Repository-backed value-present check. |
| `sizeOnServer` | Supported | Region document count. |
| `keySetOnServer` | Supported | Returns region keys using Geode list/array length encoding. |
| PDX type-id lookup | Partially supported | PDX round-trip is supported; full registry discovery semantics remain scoped/limited. |
| unknown opcode logging | Supported | Logs unknown frame details without crashing the process. |

## Supported Value Types

| Java / Geode Type | Marker / Shape | Runtime | Unit Tests | Integration Tests |
|---|---|---:|---:|---:|
| `String` | `0x57` | Yes | Yes | Yes |
| `Boolean` | `0x35` | Yes | Yes | Yes |
| `Character` | `0x36` | Yes | Yes | Yes |
| `Byte` | `0x37` | Yes | Yes | Yes |
| `Short` | `0x38` | Yes | Yes | Yes |
| `Integer` | `0x39` | Yes | Yes | Yes |
| `Long` | `0x3a` | Yes | Yes | Yes |
| `Float` | `0x3b` | Yes | Yes | Yes |
| `Double` | `0x3c` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | Yes | Yes | Yes |
| `byte[]` | `0x2e` or raw bytes | Yes | Yes | Yes |
| `boolean[]` | `0x1a` | Yes | Yes | Yes |
| `char[]` | `0x1b` | Yes | Yes | Yes |
| `short[]` | `0x2f` | Yes | Yes | Yes |
| `int[]` | `0x30` | Yes | Yes | Yes |
| `long[]` | `0x31` | Yes | Yes | Yes |
| `float[]` | `0x32` | Yes | Yes | Yes |
| `double[]` | `0x33` | Yes | Yes | Yes |
| `String[]` | `0x40` | Yes | Yes | Yes |
| `Integer[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Long[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Boolean[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Double[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `UUID[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigInteger[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigDecimal[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Enum[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Instant[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDate[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDateTime[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` string-only list | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... <component-type> ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | `0x41 ... mixed elements ...` | Yes | Yes | Yes |
| `UUID` | `0x62` opaque standalone utility | Yes | Yes | Yes |
| `BigInteger` | `0x5f` opaque standalone utility | Yes | Yes | Yes |
| `BigDecimal` | `0x60` opaque standalone utility | Yes | Yes | Yes |
| `Enum` | `0x65` opaque standalone utility | Yes | Yes | Yes |
| `java.time.Instant` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDate` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDateTime` | `0x2c` Java serialized | Yes | Yes | Yes |
| PDX / `PdxInstance` | `0x5d` opaque PDX payload | Yes | Yes | Yes |
| DataSerializable | TBD | Not yet | Not yet | Not yet |

## Collection Response Encoding Compatibility

ProtoGemCouch has two separate collection count encodings. These must not be mixed.

### Geode array/list length encoding

Used by manually encoded list-style payloads such as `keySetOnServer`.

```text
0..252   -> one byte containing the count
0xfe     -> two following bytes contain the count
0xfd     -> four following bytes contain the count
0xff     -> null array/list marker
```

Examples:

```text
150 -> 96
253 -> fe 00 fd
```

Implementation helper:

```java
writeGeodeArrayLength(...)
```

### VersionedObjectList count encoding

Used by `GET_ALL` responses. Geode deserializes this through `VersionedObjectList.fromData(...)`.

This uses unsigned variable-length integer encoding, not the normal array/list length encoding.

Examples:

```text
127 -> 7f
128 -> 80 01
150 -> 96 01
253 -> fd 01
```

Implementation helper:

```java
writeVersionedObjectListCount(...)
```

## Primitive Array Support

Primitive arrays use dedicated Geode DataSerializer markers:

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

Primitive arrays are decoded structurally and stored in Couchbase as typed JSON array envelopes.

## Wrapper and Utility Array Support

Wrapper and utility arrays use Geode's `0x34` object-array style envelope.

Examples:

```text
Integer[]        -> 0x34 ... java.lang.Integer ...
UUID[]           -> 0x34 ... java.util.UUID ...
BigDecimal[]     -> 0x34 ... java.math.BigDecimal ...
Instant[]        -> 0x34 ... java.time.Instant ...
LocalDate[]      -> 0x34 ... java.time.LocalDate ...
LocalDateTime[]  -> 0x34 ... java.time.LocalDateTime ...
```

Design decision:

```text
The shim preserves the entire 0x34 payload opaquely, regardless of component type.
Returning the original Geode payload lets the Geode client deserialize the exact original array type.
```

## Standalone Utility Value Support

Standalone utility values use a mix of dedicated Geode markers and Java serialization.

```text
BigInteger              -> 0x5f
BigDecimal              -> 0x60
UUID                    -> 0x62
Enum                    -> 0x65
java.time.Instant       -> 0x2c Java serialized
java.time.LocalDate     -> 0x2c Java serialized
java.time.LocalDateTime -> 0x2c Java serialized
```

## PDX / PdxInstance Support

PDX payloads are preserved opaquely and returned with their original Geode PDX marker.

```text
PdxInstance -> 0x5d <payload...>
```

Validated PDX paths include:

```text
put/get with simple PdxInstance
put/get with primitive and String array PDX fields
put/get with Object[] PDX fields
put/get with ArrayList<Object> PDX fields
put/get with nested Map PDX fields
put/get with UUID, BigInteger, BigDecimal, Instant, LocalDate, LocalDateTime, and Enum fields
putAll/getAll with PDX values
mixed primitive and PDX values in putAll/getAll
remove with PDX values
containsKeyOnServer with PDX-backed keys
keySetOnServer with PDX-backed keys
```

Current PDX scope remains a compatibility profile. Full PDX registry discovery, advanced schema evolution behavior, and all native Geode PDX server semantics are not yet claimed as general-purpose replacements.

## Structured Map Nested Value Support

Currently supported nested values in structured `HashMap<String,Object>` envelopes:

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

Not yet supported inside structured map envelopes:

```text
Object[]
Serializable POJO
ArrayList<Object>
PDX / PdxInstance
Opaque standalone utility values
Wrapper / utility arrays
```

Top-level `Object[]`, top-level wrapper/utility arrays, top-level `ArrayList<Object>`, top-level Serializable POJOs, top-level PDX values, and top-level standalone utility values are supported.

## Known Limitations

```text
Nested Object[] inside structured Map<String,Object>
Nested Serializable POJO inside structured Map<String,Object>
Nested ArrayList<Object> inside structured Map<String,Object>
Nested PDX / PdxInstance inside structured Map<String,Object>
Nested wrapper / utility arrays inside structured Map<String,Object>
Nested opaque standalone utility values inside structured Map<String,Object>
DataSerializable
Expiration / TTL behavior
Transactions
Queries
Continuous queries
Interest registration
Partitioned region metadata behavior
Server-side function execution
High-concurrency load and soak testing against the current PDX/boundary baseline
```

## Recommended Next Target

```text
observability hardening
```

Recommended observability targets:

```text
operation counters
success/error counters
latency tracking
response byte-size tracking
/metrics/json endpoint
Prometheus-format /metrics endpoint
connection close reason logging
serialization error diagnostics
```
