# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

---

## Current Status

Current milestone:

```text
java-serialized-pojo-support-complete
```

Latest Docker-backed serialization verification:

```text
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"

ProtoGemCouchSerializationIntegrationTest
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

Serializable POJO support is now implemented and validated without requiring the shim to load the POJO class.

---

## What Works Today

ProtoGemCouch currently supports a validated subset of Geode/GemFire client behavior:

```text
Client connect / handshake
Region access
put
get
putAll
getAll
remove
containsKey / contains-style checks
sizeOnServer
keySetOnServer
unknown opcode logging
```

Validated backend:

```text
Couchbase bucket: test
scope: _default
collection: _default
```

Validated default region:

```text
helloWorld
```

Document IDs are mapped using this pattern:

```text
/<region>::<key>
```

Example:

```text
/helloWorld::customer-123
```

---

## Supported Value Types

The current supported typed value set is:

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

`HashMap<String,Object>` / `LinkedHashMap<String,Object>` supports the following nested value types:

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

Top-level Serializable POJOs are supported as opaque Java serialized objects.

---

## Serializable POJO Support

Serializable POJO support uses raw byte preservation.

### How It Works

```text
Geode client sends:
2c ac ed 00 05 ...

ProtoGemCouch stores:
ac ed 00 05 ...

ProtoGemCouch returns:
2c ac ed 00 05 ...

Geode client deserializes:
CustomerProfile / customer POJO
```

### Why The Shim Does Not Deserialize POJOs

The shim should not need every customer application class on its classpath.

Instead:

```text
The customer app already has the POJO classes.
The shim stores the serialized object bytes.
Couchbase persists those bytes in a typed envelope.
The shim returns the bytes.
The customer app deserializes the object normally.
```

This is the safest first-pass compatibility behavior.

### Couchbase POJO Envelope

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
valueBase64 contains Java ObjectOutputStream bytes without the Geode 0x2c marker.
className is best-effort diagnostic metadata.
length is the number of serialized bytes stored.
```

---

## Project Architecture

At a high level:

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

Runtime responsibilities:

```text
Decode supported Geode client frames
Extract region/key/value payloads
Translate keys into Couchbase document IDs
Persist typed values into Couchbase
Read typed values from Couchbase
Serialize Geode-compatible responses
```

---

## Main Components

### Protocol / Wire Layer

Representative package:

```text
com.protogemcouch.wire
```

Important classes:

```text
GemFrame
GemPart
GemResponseWriter
MessageTypes
```

Responsibilities:

```text
Frame parsing
Message type constants
Response writing
Geode-compatible typed value encoding
VersionedObjectList-compatible GET_ALL responses
Golden-wire and shape validation
```

### Operation Handlers

Representative package:

```text
com.protogemcouch.ops
```

Important handlers:

```text
PutHandler
GetHandler
PutAllHandler
GetAllHandler
RemoveHandler
ContainsHandler
SizeOnServerHandler
KeySetOnServerHandler
SimpleAckHandler
UnknownOpcodeHandler
```

### Serialization Layer

Representative package:

```text
com.protogemcouch.serialization
```

Important classes:

```text
StoredValue
ValueDecoding
ValueEncoding
GeodeSerialization
```

Responsibilities:

```text
Decode typed Geode DataSerializer values
Decode real-client raw byte[] payloads
Decode Geode Java-serialized map payloads
Detect Java-serialized POJO payloads without classloading
Represent typed values internally
Avoid accidental primitive/map/object-to-string fallback
```

### Couchbase Repository Layer

Representative package:

```text
com.protogemcouch.couchbase
```

Important classes:

```text
Repository
CouchbaseRepository
RepositoryFactory
```

Responsibilities:

```text
Connect to Couchbase
Read documents
Write typed JSON envelopes
Hydrate typed JSON envelopes back into StoredValue
Remove documents
Check existence
List keys
Count region documents
```

---

## Couchbase Typed Document Format

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
    "createdAt": {
      "type": "date",
      "value": "1970-01-01T00:00:01Z",
      "epochMillis": 1000
    }
  },
  "length": 3
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

### Date

```json
{
  "type": "date",
  "value": "1970-01-01T00:00:01Z",
  "epochMillis": 1000
}
```

---

## Verified Geode Wire Shapes

### Serializable POJO

```text
2c ac ed 00 05 ...
```

Meaning:

```text
0x2c               Geode Java-serialized-object marker
ac ed 00 05 ...    Java ObjectOutputStream stream header and object bytes
```

### HashMap<String,Object>

```text
empty map      -> 4300
non-empty map  -> 2caced0005...
```

### Byte Array

```text
DataSerializer byte-array:
2e050102030405

Real Geode client raw byte-array:
0102030405
```

### Date

```text
new Date(1_000L) -> 3d00000000000003e8
```

---

## GET_ALL Response Strategy

`GET_ALL` uses a manually written, VersionedObjectList-compatible payload.

The production response writer does not instantiate Geode `VersionedObjectList` at runtime because that previously caused shaded-container issues related to Geode Log4j caller lookup.

Instead, `GemResponseWriter` manually writes the compatible object header and body.

Object markers:

```text
0x01 = present object
0x03 = key not at server / absent
```

For Serializable POJOs, the GET_ALL value payload is:

```text
0x01 + 0x2c + ac ed 00 05 ...
```

---

## Running the Tests

### Unit and Focused Tests

```powershell
mvn test
```

### Focused POJO Path

```powershell
mvn test "-Dtest=PutHandlerTest,PutAllHandlerTest,GetHandlerTest,GetAllHandlerTest,SerializablePojoShapeTest,GemResponseWriterTest"
```

### Full Serialization Integration Verification

Requires Docker Desktop / Docker daemon to be running.

```powershell
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
```

Expected:

```text
Tests run: 59, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## Docker Requirement

Before running full verification, confirm Docker is available:

```powershell
docker ps
```

If Docker is not running, Maven will fail at:

```text
exec:3.2.0:exec (docker-compose-up)
```

---

## Current Demo Narrative

The current demo shows a Java Geode client using normal Geode client APIs while only changing the endpoint to point at ProtoGemCouch.

Example Serializable POJO call:

```java
CustomerProfile profile = new CustomerProfile(
    "customer-1",
    "Rob",
    42,
    true
);

region.put("profile-key", profile);
Object profileValue = region.get("profile-key");
```

Mixed bulk example:

```java
Map<String, Object> entries = new LinkedHashMap<>();
entries.put("string-key", "value-1");
entries.put("integer-key", Integer.valueOf(12345));
entries.put("date-key", new Date(1_000L));
entries.put("profile-key", profile);

region.putAll(entries);

Map<String, Object> results = region.getAll(entries.keySet());
```

Expected result:

```text
Each returned value keeps its original Java wrapper/date/binary/array/list/map/POJO type.
```

---

## Current Validation Scope

Currently validated:

```text
Primitive wrapper value round-tripping
byte[] round-tripping
String[] round-tripping
ArrayList<String> round-tripping
HashMap<String,String> round-tripping
HashMap<String,Object> round-tripping
Serializable POJO round-tripping
java.util.Date round-tripping
Typed Couchbase persistence envelopes
Manual VersionedObjectList-compatible GET_ALL responses
Key-based region operation mapping
Docker-backed integration verification
```

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
java.time values such as Instant, LocalDate, LocalDateTime
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

## Suggested Next Development Target

The next recommended compatibility target is:

```text
Object[]
```

Recommended implementation path:

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
