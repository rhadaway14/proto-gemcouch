# ProtoGemCouch Demo Results

## Current Build Verification

The project successfully completed Docker-backed verification after adding `HashMap<String,Object>` / `LinkedHashMap<String,Object>` support.

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

Latest focused typed-path unit verification:

```text
mvn test "-Dtest=RepositoryFactoryTest,GetAllHandlerTest,GetHandlerTest,PutAllHandlerTest,PutHandlerTest,HashMapStringObjectShapeTest,GemResponseWriterTest"

Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The verification lifecycle includes:

```text
clean
compile
testCompile
surefire unit tests
jar
shade
docker-compose-up
failsafe integration tests
docker-compose-down
failsafe verify
```

The latest run built the shaded jar, started Couchbase and the ProtoGemCouch shim with Docker Compose, ran all 53 serialization integration tests, and cleaned up the Docker network, containers, and volume successfully.

---

## Summary

ProtoGemCouch now supports typed value round-tripping across the shim for:

```text
String
Boolean
Character
Byte
byte[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Short
Integer
Long
Float
Double
java.util.Date
```

This includes:

```text
PUT
GET
PUT_ALL
GET_ALL
Couchbase persistence
Couchbase hydration
Geode-compatible response serialization
Focused unit coverage
Docker-backed serialization integration coverage
```

---

## Newly Added HashMap<String,Object> Support

`HashMap<String,Object>` / `LinkedHashMap<String,Object>` support was added and verified across the full runtime path.

### Supported Map Shapes

Observed Geode shapes:

```text
empty map      -> 43 00
non-empty map  -> 2c + Java ObjectOutputStream bytes
```

For non-empty maps, the runtime decodes using `ObjectInputStream` over the bytes after the `0x2c` Geode Java-serialized-object marker.

### Supported Nested Map Values

Currently validated nested values:

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

### HashMap<String,Object> Runtime Support

| Component | Status |
|---|---:|
| `HashMapStringObjectShapeTest.java` | Complete |
| `ValueDecoding.decodeStringObjectHashMapValue(...)` | Complete |
| `StoredValue.Type.STRING_OBJECT_HASH_MAP` | Complete |
| `StoredValue.stringObjectHashMapValue(...)` | Complete |
| `StoredValue.asStringObjectHashMap()` | Complete |
| `GemResponseWriter.buildStringObjectHashMapGetResponse(...)` | Complete |
| `GemResponseWriter` GET_ALL string-object-map encoding | Complete |
| `PutHandler` string-object-map decode/store | Complete |
| `PutAllHandler` string-object-map decode/store | Complete |
| `GetHandler` string-object-map response path | Complete |
| `GetAllHandler` string-object-map response path | Complete |
| `CouchbaseRepository` string-object-map persistence/hydration | Complete |
| `ProtoGemCouchSerializationIntegrationTest` string-object-map end-to-end coverage | Complete |
| Focused handler tests | Complete |
| Docker-backed integration verification | Passing |

---

## String Collection and Map Support

The previous milestones are also fully validated.

### String[]

Shape:

```text
0x40 + compact length + element payloads
```

Representative examples:

```text
new String[] {}                    -> 4000
new String[] {"one"}               -> 40015700036f6e65
new String[] {"one",null,"three"}  -> 40035700036f6e65455700057468726565
```

### ArrayList<String>

Shape:

```text
0x41 + compact length + element payloads
```

Representative examples:

```text
new ArrayList<>()                  -> 4100
["one"]                            -> 41015700036f6e65
["one",null,"three"]               -> 41035700036f6e65295700057468726565
```

### HashMap<String,String>

Shape:

```text
empty map      -> 4300
non-empty map  -> 2caced0005...
```

---

## byte[] Support

`byte[]` support remains fully validated across both observed input shapes.

### Supported byte[] Input Shapes

#### DataSerializer byte-array shape

```text
0x2e + compact length + bytes
```

Verified examples:

```text
new byte[] {}                         -> 2e00
new byte[] {0x01}                     -> 2e0101
new byte[] {0x01,0x02,0x03,0x04,0x05} -> 2e050102030405
new byte[] {0x00,0x01,0x7f,0x80,0xff} -> 2e0500017f80ff
```

#### Real Geode client raw byte-array shape

Real `Region.put(key, byte[])` was observed to send `byte[]` as the raw value payload rather than the `0x2e` wrapper.

Verified examples:

```text
new byte[] {}                         -> empty payload
new byte[] {0x01,0x02,0x03,0x04,0x05} -> 0102030405
new byte[] {0x00,0x01,0x02,0x03}      -> 00010203
```

Runtime labels:

```text
encoding=geode-byte-array
encoding=raw-byte-array
```

---

## Date Support

Date support remains fully validated.

### Geode Date Marker

```text
Date marker: 0x3d
```

### Verified Date Shape

Date is encoded as:

```text
0x3d + 8-byte signed epoch millis, big-endian
```

Verified examples:

```text
new Date(0L)                 -> 3d0000000000000000
new Date(1_000L)             -> 3d00000000000003e8
new Date(1_778_265_266_000L) -> 3d0000019e08de9750
new Date(-1_000L)            -> 3dfffffffffffffc18
```

---

## Focused Handler Test Results

Representative command:

```powershell
mvn test "-Dtest=RepositoryFactoryTest,GetAllHandlerTest,GetHandlerTest,PutAllHandlerTest,PutHandlerTest,HashMapStringObjectShapeTest,GemResponseWriterTest"
```

Successful result:

```text
Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Validated string-object-map paths include:

```text
PUT:
encoding=geode-string-object-hash-map
valueType=STRING_OBJECT_HASH_MAP

PUT_ALL:
encoding=geode-string-object-hash-map
valueType=STRING_OBJECT_HASH_MAP

GET:
StoredValue.Type.STRING_OBJECT_HASH_MAP -> buildStringObjectHashMapGetResponse(...)

GET_ALL:
StoredValue.Type.STRING_OBJECT_HASH_MAP -> VersionedObjectList-compatible map payload
```

---

## Shape Test Results

### HashMapStringObjectShapeTest

Command:

```powershell
mvn test "-Dtest=HashMapStringObjectShapeTest"
```

Representative result:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Captured shape families:

```text
HASH_MAP_STRING_OBJECT_EMPTY_HEX_START
4300
HASH_MAP_STRING_OBJECT_EMPTY_HEX_END

HASH_MAP_STRING_OBJECT_ONE_STRING_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_ONE_STRING_HEX_END

HASH_MAP_STRING_OBJECT_STRING_INTEGER_BOOLEAN_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_STRING_INTEGER_BOOLEAN_HEX_END

HASH_MAP_STRING_OBJECT_STRING_NULL_DATE_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_STRING_NULL_DATE_HEX_END

HASH_MAP_STRING_OBJECT_BYTE_ARRAY_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_BYTE_ARRAY_HEX_END

HASH_MAP_STRING_OBJECT_STRING_ARRAY_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_STRING_ARRAY_HEX_END

HASH_MAP_STRING_OBJECT_STRING_ARRAY_LIST_HEX_START
2caced0005...
HASH_MAP_STRING_OBJECT_STRING_ARRAY_LIST_HEX_END
```

### Validated Shape Suite

The validated shape suite now includes:

```text
BooleanShapeTest
CharacterShapeTest
ByteShapeTest
ByteArrayShapeTest
StringArrayShapeTest
StringArrayListShapeTest
HashMapStringStringShapeTest
HashMapStringObjectShapeTest
ShortShapeTest
IntegerShapeTest
LongShapeTest
FloatShapeTest
DoubleShapeTest
DateShapeTest
```

---

## Serialization Integration Results

The serialization integration suite now includes `HashMap<String,Object>` end-to-end coverage.

Added / updated map scenarios:

```text
stringObjectHashMapValueShouldRoundTripThroughShimAndCouchbase
stringObjectHashMapWithArrayValuesShouldRoundTripThroughShimAndCouchbase
putAllWithStringObjectHashMapValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithStringObjectHashMapValuesShouldReturnMaps
mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

Verified integration behavior:

```text
Geode client PUT Map<String,Object>
  -> shim decodes STRING_OBJECT_HASH_MAP
  -> Couchbase stores typed stringObjectHashMap document
  -> shim hydrates typed nested values from Couchbase
  -> Geode client GET receives Map-compatible value

Geode client PUT_ALL Map<String,Object> values
  -> shim decodes and stores all map values

Geode client GET_ALL Map<String,Object> values
  -> shim returns VersionedObjectList-compatible map values

Mixed typed PUT_ALL / GET_ALL preserves Map<String,Object> alongside:
  String
  Character
  Byte
  byte[]
  String[]
  ArrayList<String>
  HashMap<String,String>
  Short
  Integer
  Boolean
  Long
  Float
  Double
  Date
```

---

## Couchbase Document Examples

### String Object Hash Map

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

The nested value envelopes preserve Java type fidelity that would otherwise be lost in generic JSON.

---

## Full Verification

Command:

```powershell
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
```

Result:

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The build also produced Maven Shade Plugin warnings for overlapping resources/classes and `module-info.class` entries. These are dependency packaging warnings from building the shaded jar and did not block the build.

---

## Current Demo Narrative

The current demo shows a Java Geode client using normal Geode client APIs while only changing the endpoint to point at ProtoGemCouch.

The shim accepts the request, decodes Geode-compatible typed values, persists them into Couchbase using typed JSON envelopes, and returns values back to the Geode client with Geode-compatible response serialization.

Validated demo value types:

```text
String
Boolean
Character
Byte
byte[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Short
Integer
Long
Float
Double
java.util.Date
```

Validated demo request paths:

```text
put
get
putAll
getAll
remove
contains
size
keySet
```

---

## Suggested Demo Flow

1. Start the environment with Docker Compose.
2. Show the Java Geode client using standard `Region.put`, `Region.get`, `Region.putAll`, and `Region.getAll`.
3. Demonstrate a `HashMap<String,Object>` round trip:
   ```java
   LinkedHashMap<String, Object> profile = new LinkedHashMap<>();
   profile.put("name", "rob");
   profile.put("age", Integer.valueOf(42));
   profile.put("active", Boolean.TRUE);
   profile.put("createdAt", new Date(1_000L));
   profile.put("payload", new byte[] {0x01, 0x02, 0x03});
   profile.put("items", new String[] {"one", null, "three"});

   region.put("profile-demo-key", profile);
   Object actual = region.get("profile-demo-key");
   ```
4. Show the Couchbase document with nested typed envelopes.
5. Demonstrate mixed typed `putAll` / `getAll` preserving all validated types.
6. Run or reference:
   ```powershell
   mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
   ```

---

## What This Demo Proves

This demo proves that the current shim implementation can support a meaningful subset of Geode client behavior against Couchbase.

The validated path proves:

```text
A real Geode Java client can connect to the shim.
The shim can parse supported Geode protocol operations.
The shim can translate those operations into Couchbase KV operations.
The shim can encode Geode-compatible responses.
Couchbase can act as the persistence backend for the tested region operations.
Typed Java wrapper values, arrays, lists, maps, byte arrays, and Date values preserve type fidelity across PUT/GET and PUT_ALL/GET_ALL.
Automated Docker-based integration tests can validate the whole stack.
```

---

## Current Scope

Validated:

```text
Java Geode client
Proxy region behavior
Core region operations
Typed wrapper, array/list/map, byte[], and Date values
Couchbase KV persistence
Single bucket/scope/collection backend
Manual VersionedObjectList-compatible GET_ALL responses
Docker Compose based integration environment
```

Not yet fully validated:

```text
Arbitrary Java object graph serialization
Complex POJO values
Nested Map<String,Object> beyond explicitly tested supported value types
PDX values
JSON object values
Transactions
Queries
Region events
Subscriptions
Continuous queries
Server-side functions
Partition/replication semantics
Multi-region mapping beyond the current tested path
Production-grade security/TLS/authentication
High-concurrency load behavior
Long-running soak behavior
```

---

## Current Milestone

This demo phase is successful.

The current milestone is:

```text
string-object-map-support-complete
```

Suggested commit:

```text
Add string object map serialization support
```

Suggested next target:

```text
Simple Serializable POJO
```
