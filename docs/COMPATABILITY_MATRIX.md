# ProtoGemCouch Compatibility Matrix

## Current Milestone

```text
primitive-array-family-support-complete
```

Primitive array family support is now implemented and validated end-to-end.

This milestone adds structural support for:

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

Previous completed milestones:

```text
int-array-support-complete
object-array-list-support-complete
object-array-support-complete
```

Latest verification:

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

## Supported Operations

| Operation | Status | Notes |
|---|---:|---|
| connect / handshake | Supported | Java Geode client connects to the shim. |
| region access | Supported | Validated against `helloWorld`. |
| `put` | Supported | Supports all validated value types. |
| `get` | Supported | Returns Geode-compatible typed values. |
| `putAll` | Supported | Supports batch typed values. |
| `getAll` | Supported | Uses VersionedObjectList-compatible response payloads. |
| `remove` | Supported | Removes mapped Couchbase document. |
| `containsKey` | Supported | Repository-backed existence check. |
| `sizeOnServer` | Supported | Region document count. |
| `keySetOnServer` | Supported | Returns region keys. |
| unknown opcode logging | Supported | Logs unknown frame details without crashing. |

## Supported Value Types

| Java / Geode Type | Marker / Shape | Runtime | Unit Tests | Integration Tests |
|---|---|---:|---:|---:|
| `String` | `0x57` | Yes | Yes | Yes |
| `Boolean` | `0x35` | Yes | Yes | Yes |
| `Character` | `0x36` | Yes | Yes | Yes |
| `Byte` | `0x37` | Yes | Yes | Yes |
| `Short` | `0x38` | Yes | Yes | Yes |
| `Integer` | `0x39` | Yes | Yes | Yes |
| `Long` | `0x3a` | Yes | Yes | Yes |
| `Float` | `0x3b` | Yes | Yes | Yes |
| `Double` | `0x3c` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | Yes | Yes | Yes |
| `byte[]` | `0x2e` or raw bytes | Yes | Yes | Yes |
| `boolean[]` | `0x1a` | Yes | Yes | Yes |
| `char[]` | `0x1b` | Yes | Yes | Yes |
| `short[]` | `0x2f` | Yes | Yes | Yes |
| `int[]` | `0x30` | Yes | Yes | Yes |
| `long[]` | `0x31` | Yes | Yes | Yes |
| `float[]` | `0x32` | Yes | Yes | Yes |
| `double[]` | `0x33` | Yes | Yes | Yes |
| `String[]` | `0x40` | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` string-only list | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... java.lang.Object ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | `0x41 ... mixed elements ...` | Yes | Yes | Yes |
| wrapper arrays | TBD | Not yet | Not yet | Not yet |
| BigDecimal / BigInteger | TBD | Not yet | Not yet | Not yet |
| UUID | TBD | Not yet | Not yet | Not yet |
| Enum | TBD | Not yet | Not yet | Not yet |
| `java.time` values | TBD | Not yet | Not yet | Not yet |
| DataSerializable | TBD | Not yet | Not yet | Not yet |
| PDX / PdxInstance | TBD | Not yet | Not yet | Not yet |

## Primitive Array Support

Primitive arrays use dedicated Geode DataSerializer markers:

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

The general wire pattern is:

```text
<marker> <length> <big-endian primitive values...>
```

Primitive arrays are decoded structurally and stored in Couchbase as typed JSON array envelopes.

Example `int[]` envelope:

```json
{
  "type": "intArray",
  "value": [1, 42, -7, 2147483647, -2147483648],
  "length": 5
}
```

Example `char[]` envelope:

```json
{
  "type": "charArray",
  "value": ["A", "Z", "0"],
  "length": 3
}
```

Design decision:

```text
Primitive arrays are simple fixed-width payloads, so they are stored structurally.
Object[] and ArrayList<Object> remain opaque because parsing them fully can involve nested Java serialization, customer classes, and object graph boundaries.
```

## Opaque Object Support

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

## HashMap<String,Object> Nested Value Support

Currently supported nested values in structured map envelopes:

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
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
String[]
ArrayList<String>
```

Not yet supported inside structured map envelopes:

```text
Object[]
Serializable POJO
ArrayList<Object>
```

Top-level `Object[]`, top-level `ArrayList<Object>`, and top-level Serializable POJOs are supported.

## Runtime Coverage

| Component | Primitive Arrays | ArrayList<Object> | Object[] | POJO | Map<String,Object> | Scalars | Date |
|---|---:|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Docker integration | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

## Primitive Array Integration Coverage

```text
booleanArrayValueShouldRoundTripThroughShimAndCouchbase
charArrayValueShouldRoundTripThroughShimAndCouchbase
shortArrayValueShouldRoundTripThroughShimAndCouchbase
intArrayValueShouldRoundTripThroughShimAndCouchbase
emptyIntArrayValueShouldRoundTripThroughShimAndCouchbase
longArrayValueShouldRoundTripThroughShimAndCouchbase
floatArrayValueShouldRoundTripThroughShimAndCouchbase
doubleArrayValueShouldRoundTripThroughShimAndCouchbase
putAllWithPrimitiveArrayFamilyValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithPrimitiveArrayFamilyValuesShouldReturnPrimitiveArrays
stringObjectHashMapWithArrayValuesShouldRoundTripThroughShimAndCouchbase
mixedStringCharacterBytePrimitiveArraysStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoObjectArrayObjectArrayListShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

## Known Limitations

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
Expiration / TTL behavior
Transactions
Queries
Continuous queries
Interest registration
Partitioned region metadata behavior
Server-side function execution
High-concurrency load and soak testing
```

## Recommended Next Target

```text
wrapper arrays and common Java utility value types
```

Recommended next types:

```text
Integer[]
Long[]
Boolean[]
Double[]
UUID
BigDecimal
BigInteger
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```
