# ProtoGemCouch Demo Results

## Current Milestone

```text
object-array-list-support-complete
```

The project now supports opaque `ArrayList<Object>` round-tripping through the full stack:

```text
Geode Java client
ProtoGemCouch protocol shim
Couchbase typed storage envelope
Geode-compatible response encoding
```

This includes and builds on the previous completed `Object[]` milestone.

Verification completed successfully:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Latest Docker-backed integration result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchSerializationIntegrationTest
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

## Supported Demo Value Types

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
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
```

## ArrayList<Object> Demo

Client-side example:

```java
ArrayList<Object> value = new ArrayList<>();
value.add("one");
value.add(Integer.valueOf(42));
value.add(Boolean.TRUE);

region.put("object-array-list-demo-key", value);
Object actual = region.get("object-array-list-demo-key");
```

Observed Geode shape:

```text
41035700036f6e65390000002a3501
```

Decoded meaning:

```text
0x41         ArrayList/list marker
03           list length
57 0003 one  String element
39 0000002a  Integer 42
35 01        Boolean true
```

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

## ArrayList<Object> Runtime Strategy

```text
Client sends:
41 <length> <elements...>

Shim stores:
same full 41... payload as Base64

Shim returns:
same full 41... payload

Client receives:
ArrayList<Object>
```

## Why Opaque ArrayList<Object> Storage

`ArrayList<Object>` may contain nested POJOs, maps, arrays, lists, byte arrays, Date values, and scalar values. Parsing it fully would require handling Java serialization stream boundaries and potentially classloading customer objects.

The compatibility-first approach is:

```text
Try ArrayList<String> structured decoding first
If that fails and the payload starts with 0x41, recognize it as ArrayList<Object>
Store original encoded payload
Return original encoded payload
Let the Geode client deserialize normally
```

## Validated ArrayList<Object> Scenarios

```text
Simple ArrayList<Object> with String, Integer, Boolean
ArrayList<Object> with null element
ArrayList<Object> with scalar wrappers
ArrayList<Object> with Date
ArrayList<Object> with byte[]
ArrayList<Object> with nested String[]
ArrayList<Object> with nested Object[]
ArrayList<Object> with nested ArrayList<String>
ArrayList<Object> with nested HashMap<String,Object>
ArrayList<Object> with nested Serializable POJO
ArrayList<Object> in put/get
ArrayList<Object> in putAll/get
ArrayList<Object> in getAll
ArrayList<Object> inside full mixed typed putAll/getAll
```

## Full Mixed Demo Path

The current integration test validates a mixed batch containing:

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
Object[]
ArrayList<Object>
Short
Integer
Boolean
Long
Float
Double
Date
```

## Current Scope

Validated:

```text
Java Geode client
Core region operations
PUT / GET / PUT_ALL / GET_ALL
Couchbase KV persistence
Typed storage envelopes
Opaque POJO preservation
Opaque Object[] preservation
Opaque ArrayList<Object> preservation
Manual VersionedObjectList-compatible GET_ALL responses
Docker-backed integration environment
```

Not yet validated:

```text
Nested Object[] inside structured Map<String,Object>
Nested POJO inside structured Map<String,Object>
Nested ArrayList<Object> inside structured Map<String,Object>
Primitive arrays beyond byte[]
Wrapper arrays
DataSerializable
PDX / PdxInstance
Queries
Transactions
Continuous queries
Interest registration
Server-side functions
High-concurrency load testing
```

## Suggested Next Demo Target

```text
primitive arrays beyond byte[]
```

Recommended first type:

```text
int[]
```
