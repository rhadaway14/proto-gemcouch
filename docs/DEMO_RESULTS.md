# ProtoGemCouch Demo Results

## Current Milestone

```text
pdx-and-large-collection-boundary-support-complete
```

The project now supports a broad validated round-trip path through the full stack:

```text
Geode Java client
ProtoGemCouch protocol shim
Couchbase typed storage envelope
Geode-compatible response encoding
Geode Java client deserialization
```

Current completed support areas include:

```text
core region operations
bulk PUT_ALL / GET_ALL operations
server-side key metadata operations
primitive wrappers
primitive arrays
wrapper / utility arrays
standalone utility values
Serializable POJOs
Object[]
ArrayList<Object>
PDX / PdxInstance
large collection boundary handling for >127 and >252 entries
```

## Latest Verification

Latest Docker-backed integration result:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchPdxRegistryDiscoveryIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 3

ProtoGemCouchSerializationIntegrationTest
Tests run: 135, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3

BUILD SUCCESS
```

Recommended local validation:

```powershell
mvn -Dtest=GemResponseWriterTest test
mvn clean verify
```

## Supported Demo Operations

```text
connect / handshake
region access
put
get
putAll
getAll
remove
containsKey / containsKeyOnServer
containsValueForKey
sizeOnServer
keySetOnServer
unknown opcode logging
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
PdxInstance
```

## PDX Demo

Client-side example:

```java
PdxInstance value = cache.createPdxInstanceFactory("com.example.Customer")
        .writeString("id", "customer-1")
        .writeString("name", "Rob")
        .writeInt("age", 42)
        .writeBoolean("active", true)
        .create();

region.put("pdx-demo-key", value);
Object actual = region.get("pdx-demo-key");
```

Observed strategy:

```text
Client sends:
0x5d <PDX payload>

Shim stores:
opaque PDX payload as a typed Couchbase envelope

Shim returns:
same 0x5d PDX payload shape

Client receives:
PdxInstance
```

Validated PDX scenarios include:

```text
simple PdxInstance
PDX with primitive arrays
PDX with String arrays
PDX with Object[] fields
PDX with ArrayList<Object> fields
PDX with nested Map fields
PDX with UUID, BigInteger, BigDecimal fields
PDX with Instant, LocalDate, LocalDateTime fields
PDX with Enum field
PDX values in putAll/getAll
mixed primitive and PDX values in putAll/getAll
remove with PDX values
containsKeyOnServer for PDX-backed keys
keySetOnServer for PDX-backed keys
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

## Large Collection Boundary Demo

The current compatibility profile now validates the count-encoding boundaries that matter for list-style and VersionedObjectList-style responses.

Validated scenarios:

```text
keySetOnServerShouldHandleMoreThan127Keys
keySetOnServerShouldHandleMoreThan252Keys
getAllShouldHandleMoreThan127Keys
getAllShouldHandleMoreThan252Keys
putAllShouldHandleMoreThan127Entries
putAllShouldHandleMoreThan252Entries
```

Key detail:

```text
keySetOnServer returns a list-style payload and uses Geode array/list length encoding.
GET_ALL returns VersionedObjectList and uses unsigned variable-length integer count encoding.
```

This distinction is now covered by both integration tests and fast response-writer unit tests.

## Full Mixed Demo Path

The current integration suite validates mixed batches containing:

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
PdxInstance
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
contains / size / key-set operations
Couchbase KV persistence
Typed storage envelopes
Structural primitive-array preservation
Opaque wrapper / utility array preservation
Opaque standalone utility value preservation
Opaque POJO preservation
Opaque Object[] preservation
Opaque ArrayList<Object> preservation
Opaque PDX / PdxInstance preservation
Manual VersionedObjectList-compatible GET_ALL responses
Large collection count-boundary behavior
Docker-backed integration environment
```

Not yet validated:

```text
Nested Object[] inside structured Map<String,Object>
Nested POJO inside structured Map<String,Object>
Nested ArrayList<Object> inside structured Map<String,Object>
Nested wrapper / utility arrays inside structured Map<String,Object>
Nested opaque standalone utility values inside structured Map<String,Object>
Nested PDX / PdxInstance inside structured Map<String,Object>
DataSerializable
Full PDX registry discovery / broader PDX server semantics
Queries
Transactions
Continuous queries
Interest registration
Server-side functions
High-concurrency load testing against the current PDX/boundary baseline
```

## Suggested Next Demo Target

```text
observability hardening
```

Suggested demo additions:

```text
/metrics/json endpoint
operation counters
success/error counts
latency tracking
response byte-size tracking
Prometheus-format /metrics endpoint
```
