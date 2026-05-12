# ProtoGemCouch Compatibility Matrix

## Current Validation Status

Last updated after successful full verification for `java.util.Date` support.

```text
mvn clean verify
BUILD SUCCESS
```

The latest verification completed successfully after Date support was added across the runtime path, focused unit tests, Couchbase persistence/hydration, and Docker-backed integration tests.

Known visible unit-test result from the final verification stream:

```text
Tests run: 153, Failures: 0, Errors: 0, Skipped: 0
```

Full Docker/Failsafe integration was also confirmed successful locally after Docker was running.

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
| `sizeOnServer` | Supported | Region-size query path covered by unit tests. |
| `keySetOnServer` | Supported | Returns region keys using Geode-compatible list payload. |
| Unknown opcode handling | Supported | Logs unknown frame details and does not crash. |

---

## Supported Value Types

| Java / Geode Type | Geode DataSerializer Marker | Example Shape | Runtime Support | Unit Coverage | Integration Coverage |
|---|---:|---|---|---|---|
| `String` | `0x57` | `57 00 <len> <utf8>` | Yes | Yes | Yes |
| `Boolean` | `0x35` | `true -> 3501`, `false -> 3500` | Yes | Yes | Yes |
| `Character` | `0x36` | `'A' -> 360041` | Yes | Yes | Yes |
| `Byte` | `0x37` | `7 -> 3707` | Yes | Yes | Yes |
| `Short` | `0x38` | `7 -> 380007` | Yes | Yes | Yes |
| `Integer` | `0x39` | `7 -> 3900000007` | Yes | Yes | Yes |
| `Long` | `0x3a` | `7 -> 3a0000000000000007` | Yes | Yes | Yes |
| `Float` | `0x3b` | `7.25f -> 3b40e80000` | Yes | Yes | Yes |
| `Double` | `0x3c` | `7.25d -> 3c401d000000000000` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | `new Date(1000L) -> 3d00000000000003e8` | Yes | Yes | Yes |
| `byte[]` | TBD | TBD | Not yet | Not yet | Not yet |
| `String[]` | TBD | TBD | Not yet | Not yet | Not yet |
| `ArrayList<String>` | TBD | TBD | Not yet | Not yet | Not yet |
| `HashMap<String, Object>` | TBD | TBD | Not yet | Not yet | Not yet |
| Simple Serializable POJO | TBD | TBD | Not yet | Not yet | Not yet |

---

## Verified Wire Shapes

### String

```text
57 <2-byte UTF-8 length> <UTF-8 bytes>
```

Example:

```text
57 00 05 6b65792d31
```

Meaning:

```text
String: key-1
```

---

### Boolean

| Value | Hex |
|---:|---|
| `Boolean.TRUE` | `3501` |
| `Boolean.FALSE` | `3500` |

---

### Character

| Value | Hex |
|---:|---|
| `'A'` | `360041` |
| `'Z'` | `36005a` |
| `'0'` | `360030` |
| `' '` | `360020` |

---

### Byte

| Value | Hex |
|---:|---|
| `Byte.valueOf((byte) 0)` | `3700` |
| `Byte.valueOf((byte) 7)` | `3707` |
| `Byte.valueOf((byte) -7)` | `37f9` |
| `Byte.MAX_VALUE` | `377f` |
| `Byte.MIN_VALUE` | `3780` |

---

### Short

| Value | Hex |
|---:|---|
| `Short.valueOf((short) 0)` | `380000` |
| `Short.valueOf((short) 7)` | `380007` |
| `Short.valueOf((short) -7)` | `38fff9` |
| `Short.MAX_VALUE` | `387fff` |
| `Short.MIN_VALUE` | `388000` |

---

### Integer

| Value | Hex |
|---:|---|
| `Integer.valueOf(7)` | `3900000007` |

---

### Long

| Value | Hex |
|---:|---|
| `Long.valueOf(7L)` | `3a0000000000000007` |
| `Long.valueOf(-7L)` | `3afffffffffffffff9` |
| `Long.valueOf(9876543210L)` | `3a000000024cb016ea` |

---

### Float

| Value | Hex |
|---:|---|
| `Float.valueOf(0.0f)` | `3b00000000` |
| `Float.valueOf(7.25f)` | `3b40e80000` |
| `Float.valueOf(-7.25f)` | `3bc0e80000` |
| `Float.valueOf(987654.25f)` | `3b49712064` |

---

### Double

| Value | Hex |
|---:|---|
| `Double.valueOf(0.0d)` | `3c0000000000000000` |
| `Double.valueOf(7.25d)` | `3c401d000000000000` |
| `Double.valueOf(-7.25d)` | `3cc01d000000000000` |
| `Double.valueOf(9876543.210d)` | `3c4162d687e6b851ec` |

---

### Date

Date is encoded as:

```text
0x3d + 8-byte signed epoch millis, big-endian
```

| Value | Hex |
|---:|---|
| `new Date(0L)` | `3d0000000000000000` |
| `new Date(1_000L)` | `3d00000000000003e8` |
| `new Date(1_778_265_266_000L)` | `3d0000019e08de9750` |
| `new Date(-1_000L)` | `3dfffffffffffffc18` |

---

## Couchbase Typed Storage Envelopes

Validated typed values are persisted as JSON objects with a `type` field and value-specific fields.

### String

```json
{
  "type": "string",
  "value": "value-1"
}
```

### Boolean

```json
{
  "type": "boolean",
  "value": true
}
```

### Character

```json
{
  "type": "character",
  "value": "A"
}
```

### Byte

```json
{
  "type": "byte",
  "value": 7
}
```

### Short

```json
{
  "type": "short",
  "value": 7
}
```

### Integer

```json
{
  "type": "integer",
  "value": 12345
}
```

### Long

```json
{
  "type": "long",
  "value": 9876543210
}
```

### Float

```json
{
  "type": "float",
  "value": 7.25
}
```

### Double

```json
{
  "type": "double",
  "value": 7.25
}
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

| Component | String | Boolean | Character | Byte | Short | Integer | Long | Float | Double | Date |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET_ALL | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Integration tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

---

## Integration Test Coverage

### CRUD / Region Operation Integration Tests

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchCrudIntegrationTest.java
```

Validated operation categories:

```text
PUT
GET
Overwrite
REMOVE
containsKey / containsValueForKey style checks
GET_ALL for strings
PUT_ALL for strings
sizeOnServer
keySetOnServer
```

### Serialization Integration Tests

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchSerializationIntegrationTest.java
```

Validated typed categories:

```text
Single-key PUT/GET typed round trips
Typed overwrite for supported values where implemented
PUT_ALL typed values
GET_ALL typed values
Mixed typed PUT_ALL / GET_ALL preservation
Date round trips through Couchbase
```

Date-specific scenarios added:

```text
dateValueShouldRoundTripThroughShimAndCouchbase
putAllWithDateValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithDateValuesShouldReturnDates
mixedStringCharacterByteShortIntegerBooleanLongFloatDoubleAndDatePutAllAndGetAllShouldPreserveTypes
```

---

## Known Limitations

Not yet fully implemented or validated:

```text
byte[]
String[]
ArrayList<String>
HashMap<String, Object>
Arbitrary Java object graph serialization
Complex POJO round-tripping
PDX object support
JSON object value support
Expiration / TTL behavior
Transactions
Queries
Continuous queries
Interest registration
Partitioned region metadata behavior
Server-side function execution
Production-grade security/auth compatibility beyond the current shim setup
TLS/mTLS production configuration
High-concurrency load and soak testing
```

---

## Current Milestone

Date support is the current completed milestone.

Suggested commit:

```text
Add Date serialization support
```

Suggested tag:

```text
date-support-complete
```

---

## Recommended Next Target

The next compatibility target should be:

```text
byte[]
```

Suggested implementation path:

```text
ByteArrayShapeTest
ValueDecoding.decodeByteArrayValue(...)
StoredValue.Type.BYTE_ARRAY
StoredValue.byteArrayValue(...)
StoredValue.asByteArray()
GemResponseWriter.buildByteArrayGetResponse(...)
PutHandler byte[] decode/store
PutAllHandler byte[] decode/store
GetHandler byte[] response
GetAllHandler byte[] response
CouchbaseRepository byte[] persistence using Base64
ProtoGemCouchSerializationIntegrationTest byte[] round trip
Docs update
```

Suggested Couchbase envelope:

```json
{
  "type": "byteArray",
  "valueBase64": "AQIDBAU=",
  "length": 5
}
```
