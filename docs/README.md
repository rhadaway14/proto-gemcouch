# ProtoGemCouch

ProtoGemCouch is a Java protocol shim that accepts a practical subset of Apache Geode / GemFire client requests and translates them into Couchbase operations.

The goal is to let an existing Java Geode client application change only its connection endpoint, while the shim handles protocol translation and persists data in Couchbase.

## Current Status

```text
primitive-array-family-support-complete
```

Latest verification:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

Both completed successfully after adding support for the remaining primitive array family.

Latest Docker-backed integration result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchSerializationIntegrationTest
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 88, Failures: 0, Errors: 0, Skipped: 0

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
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
```

## Primitive Array Support

Primitive arrays are supported structurally using Geode DataSerializer primitive-array payloads.

Supported markers:

```text
boolean[]  -> 0x1a
char[]     -> 0x1b
byte[]     -> 0x2e
short[]    -> 0x2f
int[]      -> 0x30
long[]     -> 0x31
float[]    -> 0x32
double[]   -> 0x33
```

Example:

```java
int[] value = new int[] {
    1,
    42,
    -7,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE
};

region.put("int-array-demo-key", value);
Object actual = region.get("int-array-demo-key");
```

Example encoded payload:

```text
3005000000010000002afffffff97fffffff80000000
```

Couchbase envelope:

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

The shim decodes primitive arrays structurally, stores them as typed JSON array envelopes, and re-encodes them as Geode-compatible primitive-array payloads when returning data to the client.

## ArrayList<Object> Support

`ArrayList<Object>` is supported as an opaque Geode DataSerializer list payload.

Observed wire shape:

```text
41 <length> <elements...>
```

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

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

## Serializable POJO Support

Serializable POJOs are preserved opaquely.

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

### Primitive array

```json
{
  "type": "booleanArray",
  "value": [true, false, true],
  "length": 3
}
```

```json
{
  "type": "charArray",
  "value": ["A", "Z", "0"],
  "length": 3
}
```

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

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
    },
    "intItems": {
      "type": "intArray",
      "value": [1, 42, -7],
      "length": 3
    }
  },
  "length": 3
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
boolean[] round trips
char[] round trips
short[] round trips
int[] round trips
long[] round trips
float[] round trips
double[] round trips
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
wrapper arrays and common Java utility values
```
