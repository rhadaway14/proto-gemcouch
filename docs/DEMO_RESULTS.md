# ProtoGemCouch Demo Results

## Current Milestone

```text
standalone-utility-value-support-complete
```

The project now supports standalone Java utility value round-tripping through the full stack:

```text
Geode Java client
ProtoGemCouch protocol shim
Couchbase typed storage envelope
Geode-compatible response encoding
```

This includes standalone:

```text
UUID
BigInteger
BigDecimal
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```

This milestone also preserves the completed wrapper/utility array support through generalized `0x34` object-array preservation:

```text
Integer[]
Long[]
Boolean[]
Double[]
UUID[]
BigInteger[]
BigDecimal[]
Enum[]
Instant[]
LocalDate[]
LocalDateTime[]
```

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
Tests run: 103, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 110, Failures: 0, Errors: 0, Skipped: 0

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
Integer[]
Long[]
Boolean[]
Double[]
UUID[]
BigInteger[]
BigDecimal[]
Enum[]
Instant[]
LocalDate[]
LocalDateTime[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
UUID
BigInteger
BigDecimal
Enum
Instant
LocalDate
LocalDateTime
```

## Standalone Utility Demo

Client-side example:

```java
UUID value = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

region.put("uuid-demo-key", value);
Object actual = region.get("uuid-demo-key");
```

Observed Geode marker:

```text
0x62 UUID marker
```

Couchbase envelope:

```json
{
  "type": "opaqueGeodeValue",
  "opaqueGeodeTypeName": "uuid",
  "valueBase64": "YhI+RWfomxLTpFZCZhQXQAA=",
  "length": 17
}
```

Runtime strategy:

```text
Client sends:
62 <16 UUID bytes>

Shim stores:
same full 62... payload as Base64

Shim returns:
same full 62... payload

Client receives:
UUID
```

## Standalone Utility Markers

```text
BigInteger              -> 0x5f
BigDecimal              -> 0x60
UUID                    -> 0x62
Enum                    -> 0x65
java.time.Instant       -> 0x2c Java serialized
java.time.LocalDate     -> 0x2c Java serialized
java.time.LocalDateTime -> 0x2c Java serialized
```

## Wrapper / Utility Array Demo

Client-side example:

```java
Integer[] value = new Integer[] {
    Integer.valueOf(1),
    Integer.valueOf(42),
    Integer.valueOf(-7),
    null,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE
};

region.put("integer-wrapper-array-demo-key", value);
Object actual = region.get("integer-wrapper-array-demo-key");
```

Observed Geode shape:

```text
0x34 object-array envelope with component type java.lang.Integer
```

Couchbase envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 123
}
```

Runtime strategy:

```text
Client sends:
34 <length> 2b <component-type-string> <elements...>

Shim stores:
same full 34... payload as Base64

Shim returns:
same full 34... payload

Client receives:
the exact original wrapper or utility array type
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

## Validated Utility Scenarios

```text
UUID in put/get
BigInteger in put/get
BigDecimal in put/get
Enum in put/get
Instant in put/get
LocalDate in put/get
LocalDateTime in put/get
Standalone utility values in putAll/get
Standalone utility values in getAll
```

## Validated Wrapper / Utility Array Scenarios

```text
Integer[] in put/get
Long[] in put/get
Boolean[] in put/get
Double[] in put/get
UUID[] in put/get
BigInteger[] in put/get
BigDecimal[] in put/get
Enum[] in put/get
Instant[] in put/get
LocalDate[] in put/get
LocalDateTime[] in put/get
Wrapper / utility arrays in putAll/get
Wrapper / utility arrays in getAll
```

## Full Mixed Demo Path

The current integration test validates mixed batches containing:

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
Integer[]
Long[]
Boolean[]
Double[]
UUID[]
BigInteger[]
BigDecimal[]
Enum[]
Instant[]
LocalDate[]
LocalDateTime[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
UUID
BigInteger
BigDecimal
Enum
Instant
LocalDate
LocalDateTime
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
Opaque wrapper / utility array preservation
Opaque standalone utility value preservation
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
Nested wrapper / utility arrays inside structured Map<String,Object>
Nested opaque standalone utility values inside structured Map<String,Object>
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
nested opaque values inside HashMap<String,Object>
```
