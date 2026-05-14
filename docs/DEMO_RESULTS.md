# ProtoGemCouch Demo Results

## Current Milestone

```text
primitive-array-family-support-complete
```

The project now supports structural primitive-array round-tripping through the full stack:

```text
Geode Java client
ProtoGemCouch protocol shim
Couchbase typed storage envelope
Geode-compatible response encoding
```

This includes:

```text
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
```

`byte[]` remains supported through the existing byte-array path.

This milestone builds on the previous completed `int[]`, `ArrayList<Object>`, and `Object[]` milestones.

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
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 88, Failures: 0, Errors: 0, Skipped: 0

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

## Primitive Array Demo

Client-side example:

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

Observed Geode shape:

```text
3005000000010000002afffffff97fffffff80000000
```

Decoded meaning:

```text
0x30        int[] marker
05          array length
00000001    1
0000002a    42
fffffff9    -7
7fffffff    Integer.MAX_VALUE
80000000    Integer.MIN_VALUE
```

Couchbase envelope:

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

## Primitive Array Runtime Strategy

```text
Client sends:
<array-marker> <length> <big-endian primitive values...>

Shim decodes:
typed primitive array

Shim stores:
typed JSON array envelope

Shim returns:
<array-marker> <length> <big-endian primitive values...>

Client receives:
same primitive array type
```

## Primitive Array Shapes

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

## Validated Primitive Array Scenarios

```text
boolean[] in put/get
char[] in put/get
short[] in put/get
int[] in put/get
empty int[] in put/get
long[] in put/get
float[] in put/get
double[] in put/get
primitive arrays in putAll/get
primitive arrays in getAll
primitive arrays inside HashMap<String,Object>
primitive arrays inside full mixed typed putAll/getAll
```

## ArrayList<Object> Demo

```java
ArrayList<Object> value = new ArrayList<>();
value.add("one");
value.add(Integer.valueOf(42));
value.add(Boolean.TRUE);

region.put("object-array-list-demo-key", value);
Object actual = region.get("object-array-list-demo-key");
```

Couchbase envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

## Object[] Demo

```java
Object[] value = new Object[] {
    "one",
    Integer.valueOf(42),
    Boolean.TRUE
};

region.put("object-array-demo-key", value);
Object actual = region.get("object-array-demo-key");
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

## Full Mixed Demo Path

The current integration test validates a mixed batch containing:

```text
String
Character
Byte
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
Structural primitive-array preservation
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
Wrapper arrays
BigDecimal / BigInteger
UUID
Enum
java.time values
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
wrapper arrays and common Java utility values
```
