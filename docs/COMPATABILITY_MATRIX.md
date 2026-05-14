# ProtoGemCouch Compatibility Matrix

## Current Milestone

```text
standalone-utility-value-support-complete
```

Standalone Java utility value support is now implemented and validated end-to-end.

This milestone adds support for standalone:

```text
UUID
BigInteger
BigDecimal
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```

It also preserves the completed wrapper/utility array milestone through generalized `0x34` object-array preservation:

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

Previous completed milestones:

```text
wrapper-and-utility-array-support-complete
primitive-array-family-support-complete
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
Tests run: 103, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 110, Failures: 0, Errors: 0, Skipped: 0

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
| `Integer[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Long[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Boolean[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Double[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `UUID[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigInteger[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigDecimal[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Enum[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Instant[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDate[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDateTime[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` string-only list | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... <component-type> ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | `0x41 ... mixed elements ...` | Yes | Yes | Yes |
| `UUID` | `0x62` opaque standalone utility | Yes | Yes | Yes |
| `BigInteger` | `0x5f` opaque standalone utility | Yes | Yes | Yes |
| `BigDecimal` | `0x60` opaque standalone utility | Yes | Yes | Yes |
| `Enum` | `0x65` opaque standalone utility | Yes | Yes | Yes |
| `java.time.Instant` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDate` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDateTime` | `0x2c` Java serialized | Yes | Yes | Yes |
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

## Wrapper and Utility Array Support

Wrapper and utility arrays use Geode's `0x34` object-array style envelope.

Examples:

```text
Integer[]        -> 0x34 ... java.lang.Integer ...
UUID[]           -> 0x34 ... java.util.UUID ...
BigDecimal[]     -> 0x34 ... java.math.BigDecimal ...
Instant[]        -> 0x34 ... java.time.Instant ...
LocalDate[]      -> 0x34 ... java.time.LocalDate ...
LocalDateTime[]  -> 0x34 ... java.time.LocalDateTime ...
```

Storage envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 123
}
```

Design decision:

```text
The shim preserves the entire 0x34 payload opaquely, regardless of component type.
Returning the original Geode payload lets the Geode client deserialize the exact original array type.
```

## Standalone Utility Value Support

Standalone utility values use a mix of dedicated Geode markers and Java serialization.

```text
BigInteger           -> 0x5f
BigDecimal           -> 0x60
UUID                 -> 0x62
Enum                 -> 0x65
java.time.Instant    -> 0x2c Java serialized
java.time.LocalDate  -> 0x2c Java serialized
java.time.LocalDateTime -> 0x2c Java serialized
```

Dedicated standalone utility markers are stored as opaque Geode values:

```json
{
  "type": "opaqueGeodeValue",
  "opaqueGeodeTypeName": "uuid",
  "valueBase64": "YhI+RWfomxLTpFZCZhQXQAA=",
  "length": 17
}
```

Java-time values are handled through the existing Java-serialized-object preservation path.

## Opaque Object Support

### ArrayList<Object>

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

### Object[] and wrapper/utility arrays

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

### Serializable POJO and Java-serialized utility values

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
Opaque standalone utility values
Wrapper / utility arrays
```

Top-level `Object[]`, top-level wrapper/utility arrays, top-level `ArrayList<Object>`, top-level Serializable POJOs, and top-level standalone utility values are supported.

## Runtime Coverage

| Component | Primitive Arrays | Wrapper / Utility Arrays | Standalone Utility Values | ArrayList<Object> | Object[] | POJO | Map<String,Object> | Scalars | Date |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Docker integration | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

## Latest Integration Coverage

```text
booleanArrayValueShouldRoundTripThroughShimAndCouchbase
charArrayValueShouldRoundTripThroughShimAndCouchbase
shortArrayValueShouldRoundTripThroughShimAndCouchbase
intArrayValueShouldRoundTripThroughShimAndCouchbase
emptyIntArrayValueShouldRoundTripThroughShimAndCouchbase
longArrayValueShouldRoundTripThroughShimAndCouchbase
floatArrayValueShouldRoundTripThroughShimAndCouchbase
doubleArrayValueShouldRoundTripThroughShimAndCouchbase

integerWrapperArrayValueShouldRoundTripThroughShimAndCouchbase
longWrapperArrayValueShouldRoundTripThroughShimAndCouchbase
booleanWrapperArrayValueShouldRoundTripThroughShimAndCouchbase
doubleWrapperArrayValueShouldRoundTripThroughShimAndCouchbase
uuidArrayValueShouldRoundTripThroughShimAndCouchbase
bigIntegerArrayValueShouldRoundTripThroughShimAndCouchbase
bigDecimalArrayValueShouldRoundTripThroughShimAndCouchbase
enumArrayValueShouldRoundTripThroughShimAndCouchbase
instantArrayValueShouldRoundTripThroughShimAndCouchbase
localDateArrayValueShouldRoundTripThroughShimAndCouchbase
localDateTimeArrayValueShouldRoundTripThroughShimAndCouchbase

uuidValueShouldRoundTripThroughShimAndCouchbase
bigIntegerValueShouldRoundTripThroughShimAndCouchbase
bigDecimalValueShouldRoundTripThroughShimAndCouchbase
enumValueShouldRoundTripThroughShimAndCouchbase
instantValueShouldRoundTripThroughShimAndCouchbase
localDateValueShouldRoundTripThroughShimAndCouchbase
localDateTimeValueShouldRoundTripThroughShimAndCouchbase

putAllWithWrapperAndUtilityArrayValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithWrapperAndUtilityArrayValuesShouldReturnArrays
putAllWithStandaloneUtilityValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithStandaloneUtilityValuesShouldReturnValues
```

## Known Limitations

```text
Nested Object[] inside structured Map<String,Object>
Nested Serializable POJO inside structured Map<String,Object>
Nested ArrayList<Object> inside structured Map<String,Object>
Nested wrapper / utility arrays inside structured Map<String,Object>
Nested opaque standalone utility values inside structured Map<String,Object>
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
nested opaque values inside HashMap<String,Object>
```

Recommended next nested targets:

```text
Object[]
ArrayList<Object>
Serializable POJO
UUID
BigInteger
BigDecimal
Enum
wrapper / utility arrays
```
