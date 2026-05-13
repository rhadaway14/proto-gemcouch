# ProtoGemCouch Demo Results

## Current Build Verification

Current completed milestone:

```text
java-serialized-pojo-support-complete
```

The project successfully completed Docker-backed verification after adding Serializable POJO support.

```text
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"

ProtoGemCouchSerializationIntegrationTest
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

The POJO implementation was updated to avoid classloading inside the shim. The shim stores and returns raw Java ObjectOutputStream bytes, so customer POJO classes are required only on the Geode client side.

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
Serializable POJO
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

## Newly Added Serializable POJO Support

Serializable POJO support is now complete.

### Demonstrated POJO Cases

```text
Simple Serializable POJO
Serializable POJO with null field
Serializable POJO with Date and byte[] fields
Serializable POJO with nested LinkedHashMap<String,Object>
Serializable POJO in put/get
Serializable POJO in putAll/get
Serializable POJO in getAll
Serializable POJO in mixed typed putAll/getAll
```

### Wire Shape

Observed Geode shape:

```text
2c ac ed 00 05 ...
```

Meaning:

```text
0x2c               Geode Java-serialized-object marker
ac ed 00 05 ...    standard Java ObjectOutputStream bytes
```

### Runtime Strategy

```text
Client sends:
2c ac ed 00 05 ...

Shim stores:
ac ed 00 05 ...

Shim returns:
2c ac ed 00 05 ...

Client deserializes:
Customer POJO object
```

### Important Demo Message

The shim does not need customer POJO classes.

That is the important migration story:

```text
Existing Java app has the customer classes.
ProtoGemCouch stores opaque serialized bytes.
Couchbase persists the serialized bytes in a typed JSON envelope.
The Java app receives the same object back.
```

---

## Couchbase POJO Document Example

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
valueBase64 contains ObjectOutputStream bytes without the Geode 0x2c marker.
className is diagnostic metadata extracted best-effort without loading the class.
length is stored for validation/debugging.
```

---

## Previous Completed Milestones

### HashMap<String,Object>

Validated support includes:

```text
HashMap<String,Object>
LinkedHashMap<String,Object>
Map values containing String, Boolean, Character, Byte, Short, Integer, Long, Float, Double, Date, byte[], String[], ArrayList<String>, and null
```

Stored as:

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
    }
  },
  "length": 2
}
```

### byte[]

Both observed shapes are supported:

```text
DataSerializer byte-array:
2e050102030405

Real Geode client raw byte-array:
0102030405
```

### Date

Date support remains fully validated:

```text
0x3d + 8-byte signed epoch millis, big-endian
```

Example:

```text
new Date(1_000L) -> 3d00000000000003e8
```

---

## Focused Handler Test Results

Representative command:

```powershell
mvn test "-Dtest=PutHandlerTest,PutAllHandlerTest,GetHandlerTest,GetAllHandlerTest,SerializablePojoShapeTest,GemResponseWriterTest"
```

Result:

```text
BUILD SUCCESS
```

Validated POJO paths:

```text
PUT:
encoding=geode-java-serialized-object
valueType=JAVA_SERIALIZED_OBJECT

PUT_ALL:
encoding=geode-java-serialized-object
valueType=JAVA_SERIALIZED_OBJECT

GET:
StoredValue.Type.JAVA_SERIALIZED_OBJECT -> buildJavaSerializedObjectGetResponse(...)

GET_ALL:
StoredValue.Type.JAVA_SERIALIZED_OBJECT -> VersionedObjectList-compatible object payload
```

---

## Serialization Integration Results

Latest Docker-backed result:

```text
ProtoGemCouchSerializationIntegrationTest
Tests run: 59
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

POJO-specific scenarios:

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

## Suggested Demo Flow

1. Start the environment with Docker Compose.
2. Show the Java Geode client using standard `Region.put`, `Region.get`, `Region.putAll`, and `Region.getAll`.
3. Demonstrate a normal Serializable POJO:
   ```java
   CustomerProfile profile = new CustomerProfile(
       "customer-1",
       "Rob",
       42,
       true
   );

   region.put("profile-demo-key", profile);
   Object actual = region.get("profile-demo-key");
   ```
4. Show that Couchbase stores the object as:
   ```json
   {
     "type": "javaSerializedObject",
     "className": "com.example.CustomerProfile",
     "valueBase64": "rO0ABXNy...",
     "length": 218
   }
   ```
5. Explain that the shim does not deserialize the POJO and does not need the customer class.
6. Demonstrate mixed typed `putAll` / `getAll` preserving:
   ```text
   String
   Character
   Byte
   byte[]
   String[]
   ArrayList<String>
   HashMap<String,String>
   HashMap<String,Object>
   Serializable POJO
   Short
   Integer
   Boolean
   Long
   Float
   Double
   Date
   ```
7. Run or reference:
   ```powershell
   mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
   ```

---

## What This Demo Proves

This demo proves that the current shim implementation can support a meaningful subset of Geode client behavior against Couchbase, including opaque custom Java objects.

Validated:

```text
A real Geode Java client can connect to the shim.
The shim can parse supported Geode protocol operations.
The shim can translate those operations into Couchbase KV operations.
The shim can persist typed values into Couchbase.
The shim can preserve Java-serialized POJO bytes without classloading.
The shim can encode Geode-compatible responses.
The Java client receives and deserializes the POJO normally.
Automated Docker-backed integration tests validate the whole stack.
```

---

## Current Scope

Validated:

```text
Java Geode client
Proxy region behavior
Core region operations
Typed wrapper values
byte[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
java.util.Date
Couchbase KV persistence
Single bucket/scope/collection backend
Manual VersionedObjectList-compatible GET_ALL responses
Docker Compose based integration environment
```

Not yet fully validated:

```text
Nested Serializable POJO values inside structured Map<String,Object> envelopes
Object[]
ArrayList<Object>
Primitive arrays beyond byte[]
Wrapper arrays
BigDecimal
UUID
Enum
java.time values
DataSerializable
PDX / PdxInstance
Transactions
Queries
Region events
Subscriptions
Continuous queries
Server-side functions
Production-grade security/TLS/authentication
High-concurrency load behavior
Long-running soak behavior
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

Suggested next target:

```text
Object[]
```
