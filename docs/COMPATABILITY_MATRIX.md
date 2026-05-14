# ProtoGemCouch Compatibility Matrix

## Current Milestone

```text
object-array-list-support-complete
```

`ArrayList<Object>` support is now implemented and validated. The shim preserves mixed `ArrayList<Object>` values as opaque Geode DataSerializer list payloads, stores the full encoded payload in Couchbase as Base64, and returns the same payload to the Geode client.

Previous completed milestone:

```text
object-array-support-complete
```

Latest verification completed successfully:

```powershell
mvn clean test
mvn clean verify "-Dtest=ProtoGemCouchSerializationIntegrationTest"
```

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
| `String[]` | `0x40` | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` string-only list | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... java.lang.Object ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | `0x41 ... mixed elements ...` | Yes | Yes | Yes |
| primitive arrays beyond `byte[]` | TBD | Not yet | Not yet | Not yet |
| wrapper arrays | TBD | Not yet | Not yet | Not yet |
| DataSerializable | TBD | Not yet | Not yet | Not yet |
| PDX / PdxInstance | TBD | Not yet | Not yet | Not yet |

## ArrayList<Object> Support

Observed simple mixed-list wire shape:

```text
41035700036f6e65390000002a3501
```

Meaning:

```text
0x41            Geode ArrayList/list marker
03              list length
57 0003 one     String element
39 0000002a     Integer 42
35 01           Boolean true
```

Storage envelope:

```json
{
  "type": "objectArrayList",
  "valueBase64": "QQ...",
  "length": 14
}
```

Design decision:

```text
ArrayList<Object> values are stored opaquely.
ArrayList<String> still uses the existing structured string-list path.
The decoder first attempts ArrayList<String>.
If string-list decoding fails and the payload starts with 0x41, the shim preserves it as ArrayList<Object>.
The shim returns the original Geode-compatible 0x41 payload.
```

## Object[] Support

Observed wire shape:

```text
34 <length> 2b 57 0010 6a6176612e6c616e672e4f626a656374 <elements...>
```

Storage envelope:

```json
{
  "type": "objectArray",
  "valueBase64": "NA...",
  "length": 37
}
```

## Serializable POJO Support

Serializable POJOs are stored opaquely, but without the leading Geode `0x2c` marker:

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

| Component | ArrayList<Object> | Object[] | POJO | Map<String,Object> | Scalars | Arrays/lists | Date |
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

## ArrayList<Object> Integration Coverage

```text
objectArrayListValueShouldRoundTripThroughShimAndCouchbase
objectArrayListWithNullElementShouldRoundTripThroughShimAndCouchbase
objectArrayListWithNestedValuesShouldRoundTripThroughShimAndCouchbase
putAllWithObjectArrayListValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithObjectArrayListValuesShouldReturnArrayLists
mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoObjectArrayObjectArrayListShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes
```

## Known Limitations

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
primitive arrays beyond byte[]
```

Start with:

```text
int[]
```

Reason:

```text
byte[] is already supported.
Primitive arrays are common in serialized payloads.
int[] gives us a clean next DataSerializer shape to validate.
Primitive arrays are narrower than PDX/DataSerializable and help continue expanding core type compatibility safely.
```
