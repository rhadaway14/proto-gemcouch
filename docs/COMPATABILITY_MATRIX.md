# ProtoGemCouch Compatibility Matrix

## Current Validation Status

Current completed milestone:

```text
java-serialized-pojo-support-complete
```

The project now supports Java Serializable POJO round-tripping without requiring the ProtoGemCouch shim to have the customer POJO classes on its classpath.

Latest Docker-backed serialization integration result:

```text
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"

ProtoGemCouchSerializationIntegrationTest
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

Latest focused typed-path unit result:

```text
mvn test "-Dtest=PutHandlerTest,PutAllHandlerTest,GetHandlerTest,GetAllHandlerTest,SerializablePojoShapeTest,GemResponseWriterTest"

BUILD SUCCESS
```

Previously completed milestone:

```text
string-object-map-support-complete
```

Previously validated Docker-backed result:

```text
ProtoGemCouchSerializationIntegrationTest
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

---

## Supported Operations

| Operation | Status | Notes |
|---|---:|---|
| Client connect / handshake | Supported | Shim accepts Geode client connections and routes supported operations. |
| Region access | Supported | Tested with `/helloWorld` / `helloWorld` region-style access. |
| `put` | Supported | Supports typed value decoding and Couchbase persistence for validated types. |
| `get` | Supported | Supports typed value response serialization for validated types. |
| `putAll` | Supported | Supports batch typed value decoding and Couchbase persistence for validated types. |
| `getAll` | Supported | Supports VersionedObjectList-compatible response payloads for validated types. |
| `remove` | Supported | Removes mapped Couchbase document. |
| `containsKey` / contains-style checks | Supported | Repository-backed key/value existence checks. |
| `sizeOnServer` | Supported | Region-size query path covered by unit tests and integration coverage. |
| `keySetOnServer` | Supported | Returns region keys using Geode-compatible list payload. |
| `GET_CLIENT_PARTITION_ATTRIBUTES` | Observed / acknowledged | Opcode is observed and logged; explicit partition metadata behavior is not implemented yet. |
| Unknown opcode handling | Supported | Logs unknown frame details and does not crash. |

---

## Supported Value Types

| Java / Geode Type | Geode DataSerializer Marker / Shape | Example Shape | Runtime Support | Unit Coverage | Integration Coverage |
|---|---:|---|---|---|---|
| `String` | `0x57` | `57 00 <len> <utf8>` | Yes | Yes | Yes |
| `Boolean` | `0x35` | `true -> 3501`, `false -> 3500` | Yes | Yes | Yes |
| `Character` | `0x36` | `'A' -> 360041` | Yes | Yes | Yes |
| `Byte` | `0x37` | `7 -> 3707` | Yes | Yes | Yes |
| `byte[]` | `0x2e` or raw payload | `2e050102030405` or `0102030405` | Yes | Yes | Yes |
| `String[]` | `0x40` | `40035700036f6e65455700057468726565` | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` | `41035700036f6e65295700057468726565` | Yes | Yes | Yes |
| `HashMap<String,String>` / `LinkedHashMap<String,String>` | `0x43` empty, `0x2c + Java serialization` non-empty | `4300`, `2caced0005...` | Yes | Yes | Yes |
| `HashMap<String,Object>` / `LinkedHashMap<String,Object>` | `0x43` empty, `0x2c + Java serialization` non-empty | `4300`, `2caced0005...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c + Java ObjectOutputStream bytes` | `2caced0005...` | Yes | Yes | Yes |
| Serializable POJO with null fields | `0x2c + Java ObjectOutputStream bytes` | `2caced0005...` | Yes | Yes | Yes |
| Serializable POJO with `Date` / `byte[]` fields | `0x2c + Java ObjectOutputStream bytes` | `2caced0005...` | Yes | Yes | Yes |
| Serializable POJO with nested map field | `0x2c + Java ObjectOutputStream bytes` | `2caced0005...` | Yes | Yes | Yes |
| `Short` | `0x38` | `7 -> 380007` | Yes | Yes | Yes |
| `Integer` | `0x39` | `7 -> 3900000007` | Yes | Yes | Yes |
| `Long` | `0x3a` | `7 -> 3a0000000000000007` | Yes | Yes | Yes |
| `Float` | `0x3b` | `7.25f -> 3b40e80000` | Yes | Yes | Yes |
| `Double` | `0x3c` | `7.25d -> 3c401d000000000000` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | `new Date(1000L) -> 3d00000000000003e8` | Yes | Yes | Yes |
| PDX object | TBD | TBD | Not yet | Not yet | Not yet |
| DataSerializable | TBD | TBD | Not yet | Not yet | Not yet |

---

## Serializable POJO Support

Serializable POJO support is implemented as raw Java serialized byte preservation.

The shim does not need to load or understand the customer class. It only needs to recognize the Geode Java-serialized-object marker, store the serialized bytes, and return those bytes to the Geode client.

### Wire Shape

Observed shape:

```text
2c ac ed 00 05 ...
```

Meaning:

```text
0x2c               Geode Java-serialized-object marker
ac ed 00 05 ...    Java ObjectOutputStream bytes
```

### Stored Bytes

The shim strips the Geode marker before storing:

```text
Stored in Couchbase:
ac ed 00 05 ...

Returned to Geode client:
2c ac ed 00 05 ...
```

### Why This Design

This preserves maximum compatibility:

```text
The shim does not need the customer POJO classes.
The Geode client already has the customer POJO classes.
The shim stores and returns the raw serialized object bytes.
The client deserializes the object normally when it receives the response.
```

### Validated POJO Shapes

Validated in shape tests and Docker-backed integration tests:

```text
Simple Serializable POJO
Serializable POJO with null field
Serializable POJO with Date and byte[] fields
Serializable POJO with nested LinkedHashMap<String,Object>
Serializable POJO in put
Serializable POJO in get
Serializable POJO in putAll
Serializable POJO in getAll
Serializable POJO inside full mixed typed putAll/getAll
```

---

## Supported `HashMap<String,Object>` Nested Value Types

`HashMap<String,Object>` / `LinkedHashMap<String,Object>` currently supports the following nested value types:

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
String[]
ArrayList<String>
```

Nested Serializable POJO values inside `HashMap<String,Object>` are not yet supported by the structured map envelope. A POJO as the top-level region value is supported.

---

## Verified Wire Shapes

### String

```text
57 <2-byte UTF-8 length> <UTF-8 bytes>
```

### Boolean

| Value | Hex |
|---:|---|
| `Boolean.TRUE` | `3501` |
| `Boolean.FALSE` | `3500` |

### Character

| Value | Hex |
|---:|---|
| `'A'` | `360041` |
| `'Z'` | `36005a` |
| `'0'` | `360030` |
| `' '` | `360020` |

### Byte

| Value | Hex |
|---:|---|
| `Byte.valueOf((byte) 0)` | `3700` |
| `Byte.valueOf((byte) 7)` | `3707` |
| `Byte.valueOf((byte) -7)` | `37f9` |
| `Byte.MAX_VALUE` | `377f` |
| `Byte.MIN_VALUE` | `3780` |

### Byte Array

Two byte-array shapes are supported.

```text
DataSerializer byte-array:
0x2e + compact length + bytes

Real Geode client raw byte-array:
raw bytes as the value part payload
```

Examples:

```text
new byte[] {}                         -> 2e00 or empty payload
new byte[] {0x01}                     -> 2e0101
new byte[] {0x01,0x02,0x03,0x04,0x05} -> 2e050102030405 or 0102030405
new byte[] {0x00,0x01,0x7f,0x80,0xff} -> 2e0500017f80ff
```

### String Array

```text
new String[] {}                   -> 4000
new String[] {"one"}              -> 40015700036f6e65
new String[] {"one",null,"three"} -> 40035700036f6e65455700057468726565
```

### ArrayList<String>

```text
new ArrayList<>()                 -> 4100
["one"]                           -> 41015700036f6e65
["one",null,"three"]              -> 41035700036f6e65295700057468726565
```

### HashMap<String,String>

```text
empty map      -> 4300
non-empty map  -> 2caced0005...
```

### HashMap<String,Object>

```text
empty map      -> 4300
non-empty map  -> 2caced0005...
```

### Serializable POJO

```text
simple POJO                  -> 2caced0005...
POJO with null field          -> 2caced0005...
POJO with Date + byte[]       -> 2caced0005...
POJO with nested map field    -> 2caced0005...
```

### Date

```text
new Date(0L)                 -> 3d0000000000000000
new Date(1_000L)             -> 3d00000000000003e8
new Date(1_778_265_266_000L) -> 3d0000019e08de9750
new Date(-1_000L)            -> 3dfffffffffffffc18
```

---

## Couchbase Typed Storage Envelopes

### String

```json
{
  "type": "string",
  "value": "value-1"
}
```

### Byte Array

```json
{
  "type": "byteArray",
  "valueBase64": "AQIDBAU=",
  "length": 5
}
```

### String Array

```json
{
  "type": "stringArray",
  "value": ["one", null, "three"],
  "length": 3
}
```

### ArrayList<String>

```json
{
  "type": "stringArrayList",
  "value": ["one", null, "three"],
  "length": 3
}
```

### HashMap<String,String>

```json
{
  "type": "stringHashMap",
  "value": {
    "one": "value-1",
    "two": null,
    "three": "value-3"
  },
  "length": 3
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
    "active": {
      "type": "boolean",
      "value": true
    },
    "createdAt": {
      "type": "date",
      "value": "1970-01-01T00:00:01Z",
      "epochMillis": 1000
    }
  },
  "length": 4
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

Notes:

```text
valueBase64 is the Java ObjectOutputStream byte stream without the Geode 0x2c marker.
className is best-effort diagnostic metadata extracted without loading the class.
length is the stored serialized-byte length.
```

### Date

```json
{
  "type": "date",
  "value": "1970-01-01T00:00:01Z",
  "epochMillis": 1000
}
```

---

## Runtime Coverage by Component

| Component | POJO | Map<String,Object> | String[] | ArrayList<String> | byte[] | Date | Scalars |
|---|---:|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET_ALL | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Integration tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

---

## Integration Test Coverage

### Serialization Integration Tests

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchSerializationIntegrationTest.java
```

Current Docker-backed test count:

```text
Tests run: 59
Failures: 0
Errors: 0
Skipped: 0
```

Serializable POJO scenarios:

```text
serializablePojoValueShouldRoundTripThroughShimAndCouchbase
serializablePojoWithNullFieldShouldRoundTripThroughShimAndCouchbase
serializablePojoWithDateAndByteArrayShouldRoundTripThroughShimAndCouchbase
serializablePojoWithNestedMapShouldRoundTripThroughShimAndCouchbase
putAllWithSerializablePojoValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithSerializablePojoValuesShouldReturnSerializablePojos
mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

---

## Known Limitations

Not yet fully implemented or validated:

```text
Nested Serializable POJO values inside structured Map<String,Object> envelopes
Object[]
ArrayList<Object>
Primitive arrays beyond byte[]
Wrapper arrays
BigDecimal
BigInteger
UUID
Enum
java.time types such as Instant, LocalDate, LocalDateTime
DataSerializable
PDX / PdxInstance
Expiration / TTL behavior
Transactions
Queries
Continuous queries
Interest registration
Partitioned region metadata behavior
Server-side function execution
Production-grade security/TLS/auth compatibility beyond the current shim setup
High-concurrency load and soak testing
```

---

## Current Milestone

```text
java-serialized-pojo-support-complete
```

Suggested commit:

```text
Add Java serialized POJO support
```

Suggested tag:

```text
java-serialized-pojo-support-complete
```

---

## Recommended Next Target

The next recommended compatibility target is:

```text
Object[]
```

Reason:

```text
Serializable POJO support covers arbitrary object graphs as opaque serialized objects.
Object[] is the next common non-POJO object container that may appear as a top-level region value.
It also prepares the project for ArrayList<Object> and nested mixed collection support.
```
