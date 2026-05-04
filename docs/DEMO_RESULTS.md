# ProtoGemCouch Demo Results

## Latest Full Validation Result

The latest full validation run completed successfully.

### Command

```powershell
mvn clean verify
```

### Result

```text
Unit tests: 85 passing
Serialization integration tests: 5 passing
Build result: BUILD SUCCESS
```

This validates the current shim behavior through unit tests, wire-shape tests, Docker Compose startup, Couchbase initialization, the ProtoGemCouch shim container, and real Apache Geode Java client integration tests.

---

## Typed Bulk Value Support: `putAll` and `getAll`

### Summary

ProtoGemCouch now supports typed bulk operations for string and integer values through the real Apache Geode Java client, the ProtoGemCouch shim, and Couchbase.

The following scenarios have been validated end-to-end:

- `putAll` with `Integer` values
- `getAll` returning `Integer` values
- mixed `String` and `Integer` values through `putAll` and `getAll`
- typed values persisted in Couchbase using the `StoredValue` abstraction
- typed values returned to the Geode client without being flattened into strings

### Focused Validation Command

```powershell
mvn clean verify "-Dit.test=ProtoGemCouchSerializationIntegrationTest"
```

### Focused Validation Result

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Validated Integration Tests

```text
integerValueShouldRoundTripThroughShimAndCouchbase
integerValueShouldBeOverwrittenByAnotherIntegerValue
putAllWithIntegerValuesShouldPersistAllEntriesAndBeReadableByGet
getAllWithIntegerValuesShouldReturnIntegers
mixedStringAndIntegerPutAllAndGetAllShouldPreserveTypes
```

---

## Confirmed Behavior

| Operation | Value Type | Result |
|---|---:|---|
| `put` | `Integer` | Stored as typed integer |
| `get` | `Integer` | Returned as `Integer` |
| `putAll` | `Integer` values | Stored as typed integers |
| `getAll` | `Integer` values | Returned as `Integer` values |
| `putAll` / `getAll` | mixed `String` and `Integer` | Each value preserves its original type |

---

## Runtime Flow Validated

The integration tests validated the full runtime path:

```text
Apache Geode Java Client
        |
        v
ProtoGemCouch Geode wire protocol shim
        |
        v
Operation handlers
        |
        v
Typed StoredValue repository layer
        |
        v
Couchbase container
        |
        v
Typed response serialization back to Geode client
```

---

## Implementation Notes

### Repository Refactor

The repository layer now stores values as `StoredValue` rather than plain strings.

This allows handlers to preserve logical value type across the shim boundary.

Current validated types:

```text
StoredValue.Type.STRING
StoredValue.Type.INTEGER
```

### `put` / `get`

Single-key `put` and `get` now support integer values.

Integer payload shape:

```text
39 <4-byte integer>
```

Example observed integer shape from testing:

```text
39 00 00 00 07
```

### `putAll`

`PutAllHandler` now decodes string and integer values into typed `StoredValue` objects.

Validated decode paths:

```text
encoding=geode-string valueType=STRING
encoding=geode-integer valueType=INTEGER
```

### `getAll`

`GetAllHandler` keeps repository results typed all the way into `GemResponseWriter`.

This prevents integer values from being flattened into strings.

### `VersionedObjectList` Response Shape

For `GET_ALL`, the response writer now builds a Geode `VersionedObjectList`-compatible payload manually.

This avoids loading Geode's `VersionedObjectList` class inside the shaded runtime container, which previously caused a logger initialization failure.

The validated `GET_ALL` payload shape starts with:

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

Object markers:

```text
0x01 = present object
0x03 = key not at server / absent
```

Typed values:

```text
String:
57 <2-byte UTF-8 length> <UTF-8 bytes>

Integer:
39 <4-byte integer>

Absent / missing:
03 29
```

---

## Issues Found and Resolved

### Issue: Bulk integer values returned as strings

Initial typed bulk integration tests showed that `putAll` and `getAll` paths were still string-first.

Resolution:

- updated repository contract to use `StoredValue`
- updated `PutAllHandler` to decode integer values before string-like fallback
- updated `GetAllHandler` to pass typed results into response generation
- updated `GemResponseWriter` to emit typed values in `GET_ALL`

### Issue: Mixed `String` / `Integer` `GET_ALL` failed with object type metadata error

The manually built `VersionedObjectList` body was initially incomplete for mixed typed values.

Resolution:

- added `MixedVersionedObjectListShapeTest`
- corrected the compatible full object payload shape
- validated mixed string/integer payloads

### Issue: Runtime `VersionedObjectList` initialization failed in shaded container

The full object payload worked in unit tests, but constructing `VersionedObjectList` in the shaded runtime container caused a Geode Log4j caller lookup failure.

Resolution:

- stopped instantiating Geode `VersionedObjectList` in production response-writing code
- manually wrote the compatible serialized header/body
- kept the full Geode object-compatible payload shape

### Issue: Noisy expected negative-path stack trace in `GetAllHandlerTest`

Malformed key payload tests intentionally trigger key deserialization failure.

Resolution:

- recommended logging only the structured warning without the full stack trace for expected malformed client payloads

---

## Final Validation Snapshot

### Unit Test Summary

```text
Tests run: 85
Failures: 0
Errors: 0
Skipped: 0
```

### Serialization Integration Test Summary

```text
Tests run: 5
Failures: 0
Errors: 0
Skipped: 0
```

### Build Summary

```text
BUILD SUCCESS
```

---

## Current Limitations

The validated typed value set currently includes:

- `String`
- `Integer`

Additional value types are not yet implemented:

- `Long`
- `Boolean`
- `Double`
- custom Java objects

---

## Recommended Next Tasks

### 1. Commit current typed bulk support

```powershell
git status
git add .
git commit -m "Add typed bulk value support for putAll and getAll"
```

### 2. Update docs

Update or commit:

```text
DEMO_RESULTS.md
COMPATIBILITY_MATRIX.md
```

### 3. Add more primitive types

Suggested next feature branch:

```powershell
git checkout -b feature/add-more-typed-primitives
```

Recommended next integration tests:

```text
longValueShouldRoundTripThroughShimAndCouchbase
booleanValueShouldRoundTripThroughShimAndCouchbase
doubleValueShouldRoundTripThroughShimAndCouchbase
putAllWithLongBooleanAndDoubleValuesShouldPersistTypedValues
getAllWithLongBooleanAndDoubleValuesShouldReturnTypedValues
mixedPrimitivePutAllAndGetAllShouldPreserveTypes
```

Likely implementation areas:

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
