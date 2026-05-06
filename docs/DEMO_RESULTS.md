# ProtoGemCouch Demo Results

_Last updated: 2026-05-06_

This document summarizes the current verified demo and test results for ProtoGemCouch.

ProtoGemCouch is a Java-based GemFire/Geode protocol shim that allows a Java Geode client application to connect to the shim instead of a GemFire server. The shim translates supported operations into Couchbase-backed storage operations while returning Geode-compatible responses to the client.

## Current Status

The current implementation successfully supports typed primitive value round trips through the shim and Couchbase for:

- `String`
- `Integer`
- `Boolean`
- `Long`
- `Float`
- `Double`

The implementation also supports mixed typed values in `putAll` and `getAll`.

## Latest Verified Command

The latest full verification was run with:

```powershell
mvn clean verify
```

## Latest Verified Result

```text
Unit tests:
Tests run: 119, Failures: 0, Errors: 0, Skipped: 0

Integration tests:
ProtoGemCouchCrudIntegrationTest: 7 passed
ProtoGemCouchSerializationIntegrationTest: 20 passed

Integration total:
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESS
```

## Validated End-to-End Scenarios

| Scenario | Status | Notes |
|---|---:|---|
| Java Geode client connects to shim | Passed | Client connects through Geode client APIs. |
| Shim health/readiness path | Passed | Integration tests wait for `/ready` before creating the Geode client. |
| Couchbase container initialization | Passed | Docker Compose starts Couchbase and initializes test bucket/scope/collection. |
| Shim container startup | Passed | Shim starts and accepts client traffic. |
| `String` `put/get` | Passed | Value round trips as `String`. |
| `Integer` `put/get` | Passed | Value round trips as `Integer`. |
| `Boolean` `put/get` | Passed | Value round trips as `Boolean`. |
| `Long` `put/get` | Passed | Value round trips as `Long`. |
| `Float` `put/get` | Passed | Value round trips as `Float`. |
| `Double` `put/get` | Passed | Value round trips as `Double`. |
| `Integer` overwrite | Passed | Existing typed integer value can be overwritten by another integer. |
| `putAll` with `Integer` values | Passed | All entries persist and are readable by `get`. |
| `putAll` with `Boolean` values | Passed | `true` and `false` entries persist and are readable by `get`. |
| `putAll` with `Long` values | Passed | Positive and negative long values persist and are readable by `get`. |
| `putAll` with `Float` values | Passed | Positive, negative, and large float values persist and are readable by `get`. |
| `putAll` with `Double` values | Passed | Positive, negative, and large double values persist and are readable by `get`. |
| `getAll` with `Integer` values | Passed | Values return as `Integer`. |
| `getAll` with `Boolean` values | Passed | Values return as `Boolean`. |
| `getAll` with `Long` values | Passed | Values return as `Long`. |
| `getAll` with `Float` values | Passed | Values return as `Float`. |
| `getAll` with `Double` values | Passed | Values return as `Double`. |
| Mixed `String` + `Integer` `putAll/getAll` | Passed | Types are preserved. |
| Mixed `String` + `Integer` + `Boolean` `putAll/getAll` | Passed | Types are preserved. |
| Mixed `String` + `Integer` + `Boolean` + `Long` `putAll/getAll` | Passed | Types are preserved. |
| Mixed `String` + `Integer` + `Boolean` + `Long` + `Float` + `Double` `putAll/getAll` | Passed | Types are preserved. |
| Missing key behavior | Passed | Missing keys return null-compatible responses. |
| Remove behavior | Passed | Existing document can be removed. |
| Contains behavior | Passed | `containsKey` and value-for-key paths are covered by unit tests. |
| Size behavior | Passed | Region prefix count path is covered by unit tests. |
| Key set behavior | Passed | Region key-set response path is covered by unit tests. |

## Confirmed Wire Shapes

The following Geode/DataSerializer primitive shapes have been captured and validated by shape tests.

### Boolean

```text
Boolean.TRUE  -> 3501
Boolean.FALSE -> 3500
```

### Integer

```text
Integer.valueOf(7) -> 3900000007
```

### Long

```text
Long.valueOf(7L)          -> 3a0000000000000007
Long.valueOf(-7L)         -> 3afffffffffffffff9
Long.valueOf(9876543210L) -> 3a000000024cb016ea
```

### Float

```text
Float.valueOf(7.25f)      -> 3b40e80000
Float.valueOf(-7.25f)     -> 3bc0e80000
Float.valueOf(987654.25f) -> 3b49712064
Float.valueOf(0.0f)       -> 3b00000000
```

### Double

```text
Double.valueOf(7.25d)        -> 3c401d000000000000
Double.valueOf(-7.25d)       -> 3cc01d000000000000
Double.valueOf(9876543.210d) -> 3c4162d687e6b851ec
Double.valueOf(0.0d)         -> 3c0000000000000000
```

## Unit Test Coverage Summary

| Test Class | Status | Coverage |
|---|---:|---|
| `BooleanShapeTest` | Passed | Geode Boolean wire shape. |
| `IntegerShapeTest` | Passed | Geode Integer wire shape. |
| `LongShapeTest` | Passed | Geode Long wire shape. |
| `FloatShapeTest` | Passed | Geode Float wire shape. |
| `DoubleShapeTest` | Passed | Geode Double wire shape. |
| `GemResponseWriterTest` | Passed | Response construction and primitive value encoding paths. |
| `MixedVersionedObjectListShapeTest` | Passed | Mixed typed `VersionedObjectList`-compatible payload shape. |
| `VersionedObjectListShapeTest` | Passed | Manual `VersionedObjectList`-compatible response shape. |
| `GetHandlerTest` | Passed | String, Integer, Boolean, Long, Float, Double, missing values. |
| `GetAllHandlerTest` | Passed | String, Integer, Boolean, Long, Float, Double, mixed values, missing/null, malformed keys. |
| `PutHandlerTest` | Passed | String, Integer, Boolean, Long, Float, Double, invalid payloads, missing region/key. |
| `PutAllHandlerTest` | Passed | String, Integer, Boolean, Long, Float, Double, mixed values, truncated entries, invalid payloads. |
| `ContainsHandlerTest` | Passed | Contains-key and contains-value modes. |
| `RemoveHandlerTest` | Passed | Remove response path. |
| `SizeOnServerHandlerTest` | Passed | Region size response path. |
| `KeySetOnServerHandlerTest` | Passed | Region key-set response path. |
| `UnknownOpcodeHandlerTest` | Passed | Unknown frame handling. |
| `ServerConfigTest` | Passed | Configuration defaults and env parsing. |
| `StartupValidatorTest` | Passed | Startup validation behavior. |

## Integration Test Coverage Summary

| Integration Test | Status | Coverage |
|---|---:|---|
| `ProtoGemCouchCrudIntegrationTest` | Passed | Basic CRUD behavior through shim and Couchbase. |
| `ProtoGemCouchSerializationIntegrationTest` | Passed | Typed primitive round trips and mixed typed batch operations. |

## Couchbase Persistence Model

Values are persisted into Couchbase with a type discriminator.

Examples:

```json
{
  "type": "float",
  "value": 7.25
}
```

```json
{
  "type": "double",
  "value": 7.25
}
```

```json
{
  "type": "long",
  "value": 9876543210
}
```

This allows the shim to preserve Java type identity when reading values back from Couchbase and returning them to the Geode client.

## Notes from Latest Verification

- The full unit suite passed with 119 tests.
- The full integration suite passed with 27 tests.
- Float is now covered across shape decoding, value decoding, response writing, `put`, `get`, `putAll`, `getAll`, Couchbase persistence, focused unit tests, and end-to-end integration tests.
- Double remains covered across the same runtime paths.
- Maven Shade emitted dependency overlap warnings, but they did not block the build and are not currently test failures.
- Docker Compose successfully started the integration environment and cleaned it down after test completion.

## Recommended Next Steps

1. Commit the Float and documentation work.
2. Add `Short` support next:
    - `ShortShapeTest`
    - `ValueDecoding.decodeShortValue(...)`
    - `StoredValue.Type.SHORT`
    - `GemResponseWriter.buildShortGetResponse(...)`
    - `PutHandler` / `PutAllHandler` decode paths
    - `GetHandler` / `GetAllHandler` response paths
    - `CouchbaseRepository` typed persistence
    - Focused unit tests
    - Integration tests
3. Add `Byte` support after `Short`.
4. Start designing custom Java object / PDX object compatibility.
