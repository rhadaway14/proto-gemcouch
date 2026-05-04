# ProtoGemCouch Compatibility Matrix

## Current Validation Status

ProtoGemCouch currently supports a validated subset of Apache Geode client operations translated into Couchbase-backed behavior.

The latest full validation run completed successfully:

```text
Unit tests: 85 passing
Serialization integration tests: 5 passing
Build result: BUILD SUCCESS
```

The typed bulk value work has been validated end-to-end through:

```text
Apache Geode Java client
ProtoGemCouch wire shim
Couchbase-backed repository
Docker Compose integration environment
```

---

## Operation Compatibility

| Geode Operation | Current Status | Supported Value Types | Notes |
|---|---|---|---|
| `get` | Supported | `String`, `Integer` | Returns typed values through Geode-compatible serialization. |
| `put` | Supported | `String`, `Integer` | Persists typed values using `StoredValue`. |
| `remove` | Supported | N/A | Removes the mapped Couchbase document. |
| `containsKey` | Supported | N/A | Uses document existence in Couchbase. |
| `containsValueForKey` | Supported | N/A | Checks whether a stored value exists for the key. |
| `putAll` | Supported | `String`, `Integer`, mixed `String`/`Integer` | Bulk values preserve type. |
| `getAll` | Supported | `String`, `Integer`, mixed `String`/`Integer` | Uses a manually built `VersionedObjectList`-compatible response. |
| `sizeOnServer` | Supported | N/A | Returns a Geode-serialized `Integer`. |
| `keySetOnServer` | Supported | `String` keys | Returns a Geode-compatible list of keys. |
| `GET_CLIENT_PARTITION_ATTRIBUTES` | Observed / no explicit response | N/A | The client sends this, but current behavior does not require a full implementation for tested operations. |
| `CONTROL` | Observed / acknowledged | N/A | Control frames are received during client lifecycle. |

---

## Value Type Compatibility

| Value Type | `put` | `get` | `putAll` | `getAll` | Mixed Bulk |
|---|---:|---:|---:|---:|---:|
| `String` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `Integer` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `Long` | Not yet | Not yet | Not yet | Not yet | Not yet |
| `Boolean` | Not yet | Not yet | Not yet | Not yet | Not yet |
| `Double` | Not yet | Not yet | Not yet | Not yet | Not yet |
| Custom objects | Not yet | Not yet | Not yet | Not yet | Not yet |

---

## Validated Typed Value Scenarios

| Scenario | Status |
|---|---|
| `put` with `Integer` value | ✅ Supported |
| `get` returning `Integer` value | ✅ Supported |
| overwriting an `Integer` with another `Integer` | ✅ Supported |
| `putAll` with all `Integer` values | ✅ Supported |
| `getAll` returning all `Integer` values | ✅ Supported |
| `putAll` with mixed `String` and `Integer` values | ✅ Supported |
| `getAll` returning mixed `String` and `Integer` values | ✅ Supported |
| mixed bulk operation preserving original Java value types | ✅ Supported |

---

## Repository Value Model

Typed values are represented internally by `StoredValue`.

This replaces the earlier string-only repository behavior and prevents values from being flattened into strings during storage or response generation.

Current validated logical types:

```text
StoredValue.Type.STRING
StoredValue.Type.INTEGER
```

The repository contract now supports:

```java
StoredValue get(String docId);

Map<String, StoredValue> getAll(String region, List<String> keys);

void put(String docId, StoredValue value);
```

---

## Wire Serialization Notes

### `get`

Single-key `get` responses return a Geode-compatible object part.

Supported shapes:

```text
String:
57 <2-byte UTF-8 length> <UTF-8 bytes>

Integer:
39 <4-byte integer>
```

### `sizeOnServer`

`sizeOnServer` returns a Geode-serialized `Integer`, not a raw integer payload.

Observed integer shape:

```text
39 00 00 00 07
```

### `keySetOnServer`

`keySetOnServer` returns a Geode-compatible list payload.

Observed list shape:

```text
41 <count> <geode-string-key-1> <geode-string-key-2> ...
```

A Geode `Set` payload was avoided because the client path expected a list and attempted a list cast.

### `getAll`

`GET_ALL` requires special handling because the Geode Java client expects a `VersionedObjectList`-compatible object part.

ProtoGemCouch now writes the compatible wire shape manually to avoid runtime class initialization issues in the shaded container.

The validated `GET_ALL` payload shape starts with the full Geode serialized object header:

```text
01 07 03
```

followed by:

```text
<key-count>
<keys>
<object-count>
<object markers and typed values>
```

Object markers used:

```text
0x01 = present object
0x03 = key not at server / absent
```

Typed value payloads used inside `GET_ALL`:

```text
String:
57 <2-byte UTF-8 length> <UTF-8 bytes>

Integer:
39 <4-byte integer>

Absent / missing:
03 29
```

---

## Integration Tests Covering Compatibility

Current typed serialization integration tests:

```text
integerValueShouldRoundTripThroughShimAndCouchbase
integerValueShouldBeOverwrittenByAnotherIntegerValue
putAllWithIntegerValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithIntegerValuesShouldReturnIntegers
mixedStringAndIntegerPutAllAndGetAllShouldPreserveTypes
```

Focused wire-shape tests include:

```text
IntegerShapeTest
ListShapeTest
KeySetShapeTest
VersionedObjectListShapeTest
MixedVersionedObjectListShapeTest
```

---

## Known Gaps / Future Work

The next recommended compatibility work is to add more primitive value types:

```text
Long
Boolean
Double
```

Recommended future integration tests:

```text
longValueShouldRoundTripThroughShimAndCouchbase
booleanValueShouldRoundTripThroughShimAndCouchbase
doubleValueShouldRoundTripThroughShimAndCouchbase
putAllWithLongBooleanAndDoubleValuesShouldPersistTypedValues
getAllWithLongBooleanAndDoubleValuesShouldReturnTypedValues
mixedPrimitivePutAllAndGetAllShouldPreserveTypes
```

Likely files to extend:

```text
StoredValue
ValueDecoding
ValueEncoding
GemResponseWriter
PutHandler
PutAllHandler
GetHandler
GetAllHandler
unit tests
integration tests
```

---

## Current Recommendation

The current compatibility checkpoint is stable enough to commit.

Suggested commit:

```powershell
git add .
git commit -m "Add typed bulk value support for putAll and getAll"
```
