# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

## Current Status

```text
object-array-support-complete
```

Latest verification:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Both completed successfully after adding `Object[]` support.

## Supported Operations

```text
connect / handshake
region access
put
get
putAll
getAll
remove
containsKey
sizeOnServer
keySetOnServer
unknown opcode logging
```

## Supported Value Types

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

## Object[] Support

`Object[]` is supported as an opaque Geode DataSerializer payload.

Observed wire shape:

```text
34 <length> 2b 57 0010 java.lang.Object <elements...>
```

Example:

```java
Object[] value = new Object[] {
    "one",
    Integer.valueOf(42),
    Boolean.TRUE
};

region.put("object-array-demo-key", value);
Object actual = region.get("object-array-demo-key");
```

Example encoded payload:

```text
34032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a3501
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

The shim stores and returns the full `0x34...` payload.

## Serializable POJO Support

Serializable POJOs are also preserved opaquely.

```text
Client sends:  2c ac ed 00 05 ...
Stored bytes:     ac ed 00 05 ...
Returned:      2c ac ed 00 05 ...
```

Couchbase envelope:

```json
{
  "type": "javaSerializedObject",
  "className": "com.example.CustomerProfile",
  "valueBase64": "rO0ABXNy...",
  "length": 218
}
```

## Architecture

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

## Main Packages

```text
com.protogemcouch.wire          protocol frame and response encoding
com.protogemcouch.ops           operation handlers
com.protogemcouch.serialization typed value decoding and representation
com.protogemcouch.couchbase     repository and Couchbase persistence
```

## Couchbase Document Examples

### Object[]

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
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
    }
  },
  "length": 2
}
```

## Running Tests

Local tests:

```powershell
mvn test
mvn clean test
```

Docker-backed serialization integration:

```powershell
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Docker must be running:

```powershell
docker ps
```

## Current Validation Scope

Validated:

```text
Primitive wrapper round trips
byte[] round trips
String[] round trips
ArrayList<String> round trips
HashMap<String,String> round trips
HashMap<String,Object> round trips
Serializable POJO round trips
Object[] round trips
java.util.Date round trips
Couchbase typed envelopes
GET_ALL VersionedObjectList-compatible responses
Docker-backed integration verification
```

Not yet implemented or validated:

```text
ArrayList<Object>
Nested Object[] inside structured Map<String,Object>
Nested Serializable POJO inside structured Map<String,Object>
Primitive arrays beyond byte[]
Wrapper arrays
BigDecimal / BigInteger
UUID
Enum
java.time values
DataSerializable
PDX / PdxInstance
Transactions
Queries
Continuous queries
Interest registration
Partitioned region metadata behavior
Server-side functions
High-concurrency load and soak testing
```

## Next Target

```text
ArrayList<Object>
```

Suggested implementation sequence:

```text
Shape test
ValueDecoding support
StoredValue representation
GemResponseWriter GET / GET_ALL support
PutHandler / PutAllHandler support
GetHandler / GetAllHandler support
CouchbaseRepository persistence / hydration
Docker-backed integration tests
Docs update
```
