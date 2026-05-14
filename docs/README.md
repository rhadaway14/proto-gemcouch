# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

## Current Status

```text
object-array-list-support-complete
```

Latest verification:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Both completed successfully after adding `ArrayList<Object>` support.

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
ArrayList<Object>
```

## ArrayList<Object> Support

`ArrayList<Object>` is supported as an opaque Geode DataSerializer list payload.

Observed wire shape:

```text
41 <length> <elements...>
```

Example:

```java
ArrayList<Object> value = new ArrayList<>();
value.add("one");
value.add(Integer.valueOf(42));
value.add(Boolean.TRUE);

region.put("object-array-list-demo-key", value);
Object actual = region.get("object-array-list-demo-key");
```

Example encoded payload:

```text
41035700036f6e65390000002a3501
```

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

The shim stores and returns the full `0x41...` payload for mixed object lists.

Important decode rule:

```text
ArrayList<String> is decoded first into the structured stringArrayList format.
If that fails and the payload starts with 0x41, the value is treated as opaque ArrayList<Object>.
```

## Object[] Support

`Object[]` is supported as an opaque Geode DataSerializer payload.

Observed wire shape:

```text
34 <length> 2b 57 0010 java.lang.Object <elements...>
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

### ArrayList<Object>

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

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
ArrayList<Object> round trips
java.util.Date round trips
Couchbase typed envelopes
GET_ALL VersionedObjectList-compatible responses
Docker-backed integration verification
```

Not yet implemented or validated:

```text
Nested Object[] inside structured Map<String,Object>
Nested Serializable POJO inside structured Map<String,Object>
Nested ArrayList<Object> inside structured Map<String,Object>
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
primitive arrays beyond byte[]
```

Suggested first target:

```text
int[]
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
