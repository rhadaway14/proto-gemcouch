# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

---

## Current Status

Current milestone:

```text
string-object-map-support-complete
```

Latest Docker-backed serialization verification:

```text
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
BUILD SUCCESS
```

Latest serialization integration result:

```text
ProtoGemCouchSerializationIntegrationTest
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
```

Latest focused typed-path verification:

```text
mvn test "-Dtest=RepositoryFactoryTest,GetAllHandlerTest,GetHandlerTest,PutAllHandlerTest,PutHandlerTest,HashMapStringObjectShapeTest,GemResponseWriterTest"

Tests run: 106, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

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

These are supported across:

```text
PUT
GET
PUT_ALL
GET_ALL
Couchbase persistence
Couchbase hydration
Geode-compatible response serialization
Focused unit tests
Docker-backed integration tests
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

Responsibilities:

```text
Frame parsing
Message type constants
Response writing
Geode-compatible typed value encoding
VersionedObjectList-compatible GET_ALL responses
Golden-wire and shape validation
```

Important classes include:

```text
GemFrame
GemPart
GemResponseWriter
MessageTypes
```

---

### Operation Handlers

Representative package:

```text
com.protogemcouch.ops
```

Responsibilities:

```text
Route supported operation frames
Decode request payloads
Call repository methods
Write Geode-compatible responses
```

Important handlers include:

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

---

### Serialization Layer

Representative package:

```text
com.protogemcouch.serialization
```

Responsibilities:

```text
Decode typed Geode DataSerializer values
Decode real-client raw byte[] payloads
Decode Geode Java-serialized map payloads
Represent typed values internally
Fallback to Geode DataSerializer deserialization where useful
Avoid accidental primitive/map-to-string fallback
```

Important classes include:

```text
StoredValue
ValueDecoding
ValueEncoding
GeodeSerialization
```

---

### Couchbase Repository Layer

Representative package:

```text
com.protogemcouch.couchbase
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

Important classes include:

```text
Repository
CouchbaseRepository
RepositoryFactory
```

---

## Couchbase Typed Document Format

Typed values are persisted as JSON envelopes.

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

`valueBase64` stores the exact binary payload. `length` is included as a validation/debug aid when hydrating the value back from Couchbase.

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

The `stringObjectHashMap` envelope intentionally stores each nested value as a typed envelope to preserve Java type fidelity across JSON storage.

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

The Date envelope intentionally stores both a readable ISO-8601 timestamp and the exact epoch-millis value needed for lossless Geode round-tripping.

---

## Verified Geode Wire Shapes

### String

```text
57 <2-byte UTF-8 length> <UTF-8 bytes>
```

### Boolean

```text
Boolean.TRUE  -> 3501
Boolean.FALSE -> 3500
```

### Character

```text
'A' -> 360041
'Z' -> 36005a
'0' -> 360030
' ' -> 360020
```

### Byte

```text
0             -> 3700
7             -> 3707
-7            -> 37f9
Byte.MAX      -> 377f
Byte.MIN      -> 3780
```

### Byte Array

Two byte-array shapes are supported.

DataSerializer byte-array shape:

```text
new byte[] {}                         -> 2e00
new byte[] {0x01}                     -> 2e0101
new byte[] {0x01,0x02,0x03,0x04,0x05} -> 2e050102030405
new byte[] {0x00,0x01,0x7f,0x80,0xff} -> 2e0500017f80ff
```

Real Geode client raw byte-array payload shape:

```text
new byte[] {}                         -> empty payload
new byte[] {0x01,0x02,0x03,0x04,0x05} -> 0102030405
new byte[] {0x00,0x01,0x02,0x03}      -> 00010203
```

Runtime decode labels:

```text
encoding=geode-byte-array
encoding=raw-byte-array
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

Non-empty map payloads are encoded as `0x2c + Java ObjectOutputStream bytes`.

### Short

```text
0             -> 380000
7             -> 380007
-7            -> 38fff9
Short.MAX     -> 387fff
Short.MIN     -> 388000
```

### Integer

```text
7 -> 3900000007
```

### Long

```text
7L           -> 3a0000000000000007
-7L          -> 3afffffffffffffff9
9876543210L  -> 3a000000024cb016ea
```

### Float

```text
0.0f       -> 3b00000000
7.25f      -> 3b40e80000
-7.25f     -> 3bc0e80000
987654.25f -> 3b49712064
```

### Double

```text
0.0d        -> 3c0000000000000000
7.25d       -> 3c401d000000000000
-7.25d      -> 3cc01d000000000000
9876543.210 -> 3c4162d687e6b851ec
```

### Date

```text
new Date(0L)                 -> 3d0000000000000000
new Date(1_000L)             -> 3d00000000000003e8
new Date(1_778_265_266_000L) -> 3d0000019e08de9750
new Date(-1_000L)            -> 3dfffffffffffffc18
```

Date shape:

```text
0x3d + 8-byte signed epoch millis, big-endian
```

---

## GET_ALL Response Strategy

`GET_ALL` uses a manually written, VersionedObjectList-compatible payload.

The production response writer does not instantiate Geode `VersionedObjectList` at runtime because that previously caused shaded-container issues related to Geode Log4j caller lookup.

Instead, `GemResponseWriter` manually writes the compatible object header and body.

Conceptual shape:

```text
01 07 03
<key-count>
<geode-string-key-1>
<geode-string-key-2>
...
<object-count>
<object-marker> <geode-object>
<object-marker> <geode-object>
...
```

Object markers:

```text
0x01 = present object
0x03 = key not at server / absent
```

---

## Running the Tests

### Unit and Focused Tests

```powershell
mvn test
```

### Focused Typed Handler Tests

```powershell
mvn test "-Dtest=GetHandlerTest,GetAllHandlerTest,PutHandlerTest,PutAllHandlerTest"
```

### String Object Map Focused Path

```powershell
mvn test "-Dtest=RepositoryFactoryTest,GetAllHandlerTest,GetHandlerTest,PutAllHandlerTest,PutHandlerTest,HashMapStringObjectShapeTest,GemResponseWriterTest"
```

### Full Serialization Integration Verification

Requires Docker Desktop / Docker daemon to be running.

```powershell
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
```

This runs:

```text
Unit tests
Jar build
Maven Shade build
Docker Compose environment startup
Couchbase container
Couchbase init container
ProtoGemCouch shim container
Failsafe integration tests
Docker Compose cleanup
Failsafe verify
```

---

## Docker Requirement

The full verification lifecycle starts Docker Compose as part of Maven.

Before running:

```powershell
mvn clean verify
```

confirm Docker is available:

```powershell
docker ps
```

If Docker is not running, Maven will fail at:

```text
exec:3.2.0:exec (docker-compose-up)
```

with an error similar to:

```text
this error may indicate that the docker daemon is not running
```

---

## Current Demo Narrative

The current demo shows a Java Geode client using normal Geode client APIs while only changing the endpoint to point at ProtoGemCouch.

Example client calls:

```java
region.put("string-key", "value-1");
Object stringValue = region.get("string-key");

region.put("integer-key", Integer.valueOf(12345));
Object integerValue = region.get("integer-key");

region.put("byte-array-key", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
Object byteArrayValue = region.get("byte-array-key");

region.put("date-key", new Date(1_000L));
Object dateValue = region.get("date-key");

LinkedHashMap<String, Object> profile = new LinkedHashMap<>();
profile.put("name", "rob");
profile.put("age", Integer.valueOf(42));
profile.put("active", Boolean.TRUE);
profile.put("createdAt", new Date(1_000L));
profile.put("payload", new byte[] {0x01, 0x02, 0x03});
profile.put("items", new String[] {"one", null, "three"});

region.put("profile-key", profile);
Object profileValue = region.get("profile-key");
```

Mixed bulk example:

```java
ArrayList<String> tags = new ArrayList<>();
tags.add("one");
tags.add(null);
tags.add("three");

LinkedHashMap<String, String> stringMap = new LinkedHashMap<>();
stringMap.put("one", "value-1");
stringMap.put("two", null);
stringMap.put("three", "value-3");

LinkedHashMap<String, Object> objectMap = new LinkedHashMap<>();
objectMap.put("name", "rob");
objectMap.put("age", Integer.valueOf(42));
objectMap.put("active", Boolean.TRUE);
objectMap.put("createdAt", new Date(1_000L));
objectMap.put("payload", new byte[] {0x01, 0x02, 0x03});
objectMap.put("items", new String[] {"one", null, "three"});
objectMap.put("list", tags);

Map<String, Object> entries = new LinkedHashMap<>();
entries.put("string-key", "value-1");
entries.put("character-key", Character.valueOf('A'));
entries.put("byte-key", Byte.valueOf((byte) 7));
entries.put("byte-array-key", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
entries.put("string-array-key", new String[] {"one", null, "three"});
entries.put("string-array-list-key", tags);
entries.put("string-hash-map-key", stringMap);
entries.put("string-object-hash-map-key", objectMap);
entries.put("short-key", Short.valueOf((short) 7));
entries.put("integer-key", Integer.valueOf(12345));
entries.put("boolean-key", Boolean.TRUE);
entries.put("long-key", Long.valueOf(9_876_543_210L));
entries.put("float-key", Float.valueOf(7.25f));
entries.put("double-key", Double.valueOf(7.25d));
entries.put("date-key", new Date(1_000L));

region.putAll(entries);

Map<String, Object> results = region.getAll(entries.keySet());
```

Expected result:

```text
Each returned value keeps its original Java wrapper/date/binary/array/list/map type.
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
java.util.Date round-tripping
Typed Couchbase persistence envelopes
Manual VersionedObjectList-compatible GET_ALL responses
Key-based region operation mapping
Docker-based integration verification
```

Not yet fully implemented or validated:

```text
Arbitrary Java object graph serialization
Complex POJO round-tripping
Nested Map<String,Object> beyond explicitly tested supported value types
PDX object support
JSON object values
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

## Build Notes

The build produces Maven Shade Plugin warnings for overlapping resources/classes and `module-info.class` entries.

These are dependency packaging warnings from the shaded jar build and do not currently block the build.

Current result:

```text
BUILD SUCCESS
```

---

## Suggested Next Development Target

The next recommended compatibility target is:

```text
Simple Serializable POJO
```

Alternative next targets:

```text
Nested Map<String,Object>
PDX object support
JSON object value support
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

---

## Repository Milestone

Current stable checkpoint:

```text
string-object-map-support-complete
```

Suggested commit:

```text
Add string object map serialization support
```

Suggested tag:

```text
string-object-map-support-complete
```
