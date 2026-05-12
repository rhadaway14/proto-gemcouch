# ProtoGemCouch Compatibility Matrix

## Current Validation Status

Last updated after successful full verification for `HashMap<String,Object>` / `LinkedHashMap<String,Object>` support.

```text
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
BUILD SUCCESS
```

Latest Docker-backed serialization integration result:

```text
ProtoGemCouchSerializationIntegrationTest
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

The latest full Docker-backed verification completed successfully after `HashMap<String,Object>` support was added across the runtime path, focused unit tests, Couchbase persistence/hydration, and Docker-backed integration tests.

Previously validated full integration baseline:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchSerializationIntegrationTest
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

Latest focused typed-path unit result:

```text
mvn test "-Dtest=RepositoryFactoryTest,GetAllHandlerTest,GetHandlerTest,PutAllHandlerTest,PutHandlerTest,HashMapStringObjectShapeTest,GemResponseWriterTest"

Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
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
| `Short` | `0x38` | `7 -> 380007` | Yes | Yes | Yes |
| `Integer` | `0x39` | `7 -> 3900000007` | Yes | Yes | Yes |
| `Long` | `0x3a` | `7 -> 3a0000000000000007` | Yes | Yes | Yes |
| `Float` | `0x3b` | `7.25f -> 3b40e80000` | Yes | Yes | Yes |
| `Double` | `0x3c` | `7.25d -> 3c401d000000000000` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | `new Date(1000L) -> 3d00000000000003e8` | Yes | Yes | Yes |
| Simple Serializable POJO | TBD | TBD | Not yet | Not yet | Not yet |
| PDX object | TBD | TBD | Not yet | Not yet | Not yet |

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

Nested `byte[]` and `String[]` values require array-aware equality checks in tests because Java array equality is identity-based by default.

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

### Byte Array

Two byte-array shapes are supported.

#### DataSerializer byte-array shape

`DataSerializer.writeObject(byte[])` produces:

```text
0x2e + compact length + bytes
```

| Value | Hex |
|---:|---|
| `new byte[] {}` | `2e00` |
| `new byte[] {0x01}` | `2e0101` |
| `new byte[] {0x01,0x02,0x03,0x04,0x05}` | `2e050102030405` |
| `new byte[] {0x00,0x01,0x7f,0x80,0xff}` | `2e0500017f80ff` |

#### Real Geode client raw byte-array shape

Real `Region.put(key, byte[])` and related real-client paths were observed to send `byte[]` as the raw value part payload rather than the DataSerializer wrapper.

| Value | Hex |
|---:|---|
| `new byte[] {}` | empty payload |
| `new byte[] {0x01,0x02,0x03,0x04,0x05}` | `0102030405` |
| `new byte[] {0x00,0x01,0x02,0x03}` | `00010203` |

Runtime decode labels:

```text
encoding=geode-byte-array
encoding=raw-byte-array
```

---

### String Array

`DataSerializer.writeObject(String[])` produces:

```text
0x40 + compact length + element payloads
```

Observed shapes:

| Value | Hex |
|---:|---|
| `new String[] {}` | `4000` |
| `new String[] {"one"}` | `40015700036f6e65` |
| `new String[] {"one","two","three"}` | `40035700036f6e6557000374776f5700057468726565` |
| `new String[] {"one",null,"three"}` | `40035700036f6e65455700057468726565` |

Null element marker observed in `String[]`:

```text
0x45
```

---

### ArrayList<String>

`DataSerializer.writeObject(ArrayList<String>)` produces:

```text
0x41 + compact length + element payloads
```

Observed shapes:

| Value | Hex |
|---:|---|
| `new ArrayList<>()` | `4100` |
| `["one"]` | `41015700036f6e65` |
| `["one","two","three"]` | `41035700036f6e6557000374776f5700057468726565` |
| `["one",null,"three"]` | `41035700036f6e65295700057468726565` |

Null element marker observed in `ArrayList<String>`:

```text
0x29
```

---

### HashMap<String,String>

Observed shapes:

```text
empty map      -> 43 00
non-empty map  -> 2c + Java ObjectOutputStream bytes
```

Examples:

| Value | Shape |
|---|---|
| empty map | `4300` |
| non-empty `LinkedHashMap<String,String>` | `2caced0005...` |

The runtime decodes non-empty maps with `ObjectInputStream` over the bytes after the `0x2c` marker.

---

### HashMap<String,Object>

Observed shapes:

```text
empty map      -> 43 00
non-empty map  -> 2c + Java ObjectOutputStream bytes
```

Examples:

| Value | Shape |
|---|---|
| empty map | `4300` |
| non-empty `LinkedHashMap<String,Object>` | `2caced0005...` |

Supported nested value types are listed above.

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
    },
    "payload": {
      "type": "byteArray",
      "valueBase64": "AQID",
      "length": 3
    },
    "items": {
      "type": "stringArray",
      "value": ["one", null, "three"],
      "length": 3
    },
    "list": {
      "type": "stringArrayList",
      "value": ["one", null, "three"],
      "length": 3
    }
  },
  "length": 7
}
```

The `stringObjectHashMap` envelope intentionally stores each value as a nested typed envelope to preserve Java type fidelity across JSON storage.

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

| Component | String | Boolean | Character | Byte | byte[] | String[] | ArrayList<String> | Map<String,String> | Map<String,Object> | Short | Integer | Long | Float | Double | Date |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` GET_ALL | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Integration tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

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
byte[] round trips through Couchbase
String[] round trips through Couchbase
ArrayList<String> round trips through Couchbase
HashMap<String,String> round trips through Couchbase
HashMap<String,Object> round trips through Couchbase
```

Map-specific scenarios:

```text
stringHashMapValueShouldRoundTripThroughShimAndCouchbase
emptyStringHashMapValueShouldRoundTripThroughShimAndCouchbase
putAllWithStringHashMapValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithStringHashMapValuesShouldReturnLinkedHashMaps

stringObjectHashMapValueShouldRoundTripThroughShimAndCouchbase
stringObjectHashMapWithArrayValuesShouldRoundTripThroughShimAndCouchbase
putAllWithStringObjectHashMapValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithStringObjectHashMapValuesShouldReturnMaps
mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

---

## Known Limitations

Not yet fully implemented or validated:

```text
Arbitrary Java object graph serialization
Complex POJO round-tripping
Nested Map<String,Object> beyond explicitly tested supported value types
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

`HashMap<String,Object>` support is the current completed milestone.

Suggested commit:

```text
Add string object map serialization support
```

Suggested tag:

```text
string-object-map-support-complete
```

---

## Recommended Next Target

The next compatibility target should be one of:

```text
Simple Serializable POJO
Nested Map<String,Object>
PDX object support
```

Suggested implementation path for the next type:

```text
Shape test
ValueDecoding support
StoredValue representation
GemResponseWriter GET support
GemResponseWriter GET_ALL support
PutHandler decode/store
PutAllHandler decode/store
GetHandler response
GetAllHandler response
CouchbaseRepository persistence/hydration
ProtoGemCouchSerializationIntegrationTest round trip
Docs update
```
