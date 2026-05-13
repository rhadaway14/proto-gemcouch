# ProtoGemCouch Demo Results

## Current Milestone

```text
object-array-support-complete
```

The project now supports opaque `Object[]` round-tripping through the full stack:

```text
Geode Java client
ProtoGemCouch protocol shim
Couchbase typed storage envelope
Geode-compatible response encoding
```

Verification completed successfully:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
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
```

## Object[] Demo

Client-side example:

```java
Object[] value = new Object[] {
    "one",
    Integer.valueOf(42),
    Boolean.TRUE
};

region.put("object-array-demo-key", value);
Object actual = region.get("object-array-demo-key");
```

Observed Geode shape:

```text
34032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a3501
```

Decoded meaning:

```text
0x34                         Object[] marker
03                           array length
2b 57 0010 java.lang.Object  component type metadata
57 0003 one                  String element
39 0000002a                  Integer 42
35 01                        Boolean true
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

## Runtime Strategy

```text
Client sends:
34 <length> 2b 57 0010 java.lang.Object <elements...>

Shim stores:
same full 34... payload as Base64

Shim returns:
same full 34... payload

Client receives:
Object[]
```

## Why Opaque Object[] Storage

`Object[]` may contain nested POJOs, maps, arrays, lists, and scalar values. Parsing it fully would require handling Java serialization stream boundaries and potentially classloading customer objects.

The compatibility-first approach is:

```text
Recognize Object[] marker
Store original encoded payload
Return original encoded payload
Let the Geode client deserialize normally
```

## Validated Object[] Scenarios

```text
Simple Object[] with String, Integer, Boolean
Object[] with null element
Object[] with scalar wrappers
Object[] with Date
Object[] with byte[]
Object[] with nested String[]
Object[] with nested ArrayList<String>
Object[] with nested HashMap<String,Object>
Object[] with nested Serializable POJO
Object[] in put/get
Object[] in putAll/get
Object[] in getAll
Object[] inside full mixed typed putAll/getAll
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
Manual VersionedObjectList-compatible GET_ALL responses
Docker-backed integration environment
```

Not yet validated:

```text
ArrayList<Object>
Nested Object[] inside structured Map<String,Object>
Nested POJO inside structured Map<String,Object>
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
ArrayList<Object>
```
