# ProtoGemCouch Compatibility Matrix

## Current Milestone

```text
object-array-support-complete
```

`Object[]` support is now implemented and validated. The shim preserves `Object[]` values as opaque Geode DataSerializer payloads, stores the full encoded payload in Couchbase as Base64, and returns the same payload to the Geode client.

Latest verification completed successfully:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
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
| `String[]` | `0x40` | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... java.lang.Object ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | TBD | Not yet | Not yet | Not yet |
| DataSerializable | TBD | Not yet | Not yet | Not yet |
| PDX / PdxInstance | TBD | Not yet | Not yet | Not yet |

## Object[] Support

Observed wire shape:

```text
34 <length> 2b 57 0010 6a6176612e6c616e672e4f626a656374 <elements...>
```

Meaning:

```text
0x34                         Geode Object[] marker
<length>                     compact array length
0x2b                         component class-name metadata marker
0x57 0010 java.lang.Object   component type string
<elements...>                encoded array elements
```

Example:

```text
Object[] {"one", Integer.valueOf(42), Boolean.TRUE}

34032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a3501
```

Storage envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

Design decision:

```text
Object[] values are stored opaquely.
The shim does not parse nested Object[] contents.
The shim does not need nested customer POJO classes.
The shim returns the original Geode-compatible payload.
```

## Serializable POJO Support

Serializable POJOs are also stored opaquely, but without the leading Geode `0x2c` marker:

```text
Client sends:  2c ac ed 00 05 ...
Stored bytes:     ac ed 00 05 ...
Returned:      2c ac ed 00 05 ...
```

Storage envelope:

```json
{
  "type": "javaSerializedObject",
  "className": "com.example.CustomerProfile",
  "valueBase64": "rO0ABXNy...",
  "length": 218
}
```

## HashMap<String,Object> Nested Value Support

Currently supported nested values:

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
String[]
ArrayList<String>
```

Not yet supported inside structured map envelopes:

```text
Object[]
Serializable POJO
ArrayList<Object>
```

Top-level `Object[]` and top-level Serializable POJOs are supported.

## Runtime Coverage

| Component | Object[] | POJO | Map<String,Object> | Scalars | Arrays/lists | Date |
|---|---:|---:|---:|---:|---:|---:|
| Shape tests | Yes | Yes | Yes | Yes | Yes | Yes |
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes |
| Docker integration | Yes | Yes | Yes | Yes | Yes | Yes |

## Object[] Integration Coverage

```text
objectArrayValueShouldRoundTripThroughShimAndCouchbase
objectArrayWithNullElementShouldRoundTripThroughShimAndCouchbase
objectArrayWithNestedValuesShouldRoundTripThroughShimAndCouchbase
putAllWithObjectArrayValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithObjectArrayValuesShouldReturnObjectArrays
mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoObjectArrayShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

## Known Limitations

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
ArrayList<Object>
```

Reason:

```text
ArrayList<String> is already supported.
Object[] is now supported as an opaque mixed object container.
ArrayList<Object> is the next common mixed collection shape.
It prepares the project for broader mixed collection compatibility.
```
