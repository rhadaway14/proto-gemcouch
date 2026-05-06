# ProtoGemCouch Compatibility Matrix

_Last updated: 2026-05-06_

This matrix tracks the current compatibility status of the ProtoGemCouch GemFire/Geode protocol shim. The shim accepts GemFire client operations, translates them into Couchbase-backed storage operations, and returns Geode-compatible responses.

## Summary

| Area | Status | Notes |
|---|---:|---|
| Native Geode client connection | Supported | Java Geode client can connect to the shim by changing the server endpoint. |
| Couchbase-backed persistence | Supported | Values are persisted as typed JSON documents in Couchbase. |
| String round trip | Supported | `String` values are stored and returned as `String`. |
| Integer round trip | Supported | `Integer` values use Geode marker `0x39`. |
| Boolean round trip | Supported | `Boolean.TRUE` / `Boolean.FALSE` use Geode marker `0x35`. |
| Long round trip | Supported | `Long` values use Geode marker `0x3a`. |
| Float round trip | Supported | `Float` values use Geode marker `0x3b`. |
| Double round trip | Supported | `Double` values use Geode marker `0x3c`. |
| Mixed primitive typed values | Supported | Mixed `String`, `Integer`, `Boolean`, `Long`, `Float`, and `Double` values are supported in `putAll` and `getAll`. |
| Missing values / null-like responses | Supported | Missing keys return null-compatible Geode responses. |
| Custom Java object serialization | Not yet supported | Planned future work. |
| Complex nested objects | Not yet supported | Planned after custom object serialization strategy is defined. |

## Operation Compatibility

| GemFire / Geode Operation | Current Status | Supported Value Types | Notes |
|---|---:|---|---|
| `put` | Supported | `String`, `Integer`, `Boolean`, `Long`, `Float`, `Double` | Decodes Geode/DataSerializer primitive payloads and stores typed values in Couchbase. |
| `get` | Supported | `String`, `Integer`, `Boolean`, `Long`, `Float`, `Double`, missing/null | Reads typed Couchbase document and returns Geode-compatible serialized value. |
| `putAll` | Supported | `String`, `Integer`, `Boolean`, `Long`, `Float`, `Double`, mixed values | Handles typed primitive batches and skips invalid/null-like payloads safely. |
| `getAll` | Supported | `String`, `Integer`, `Boolean`, `Long`, `Float`, `Double`, mixed values, missing/null | Builds a manual `VersionedObjectList`-compatible payload. |
| `remove` | Supported | N/A | Removes Couchbase document by translated region/key document ID. |
| `containsKey` | Supported | N/A | Uses Couchbase existence check. |
| `containsValueForKey` | Supported | N/A | Checks whether a stored typed value exists for the key. |
| `size` | Supported | N/A | Counts documents for a region prefix. |
| `keySetOnServer` | Supported | N/A | Returns keys by Couchbase document ID prefix. |
| Unknown opcode handling | Supported | N/A | Logs unknown frame details and avoids crashing. |
| Simple ack / ping-style response | Supported | N/A | Basic acknowledgement path is tested. |

## Type Compatibility

| Java Type | Geode Marker | Payload Shape | Decode Support | Encode Response Support | Couchbase Persistence | Unit Coverage | Integration Coverage |
|---|---:|---|---:|---:|---:|---:|---:|
| `String` | `0x57` | `57 <2-byte length> <UTF-8 bytes>` | Yes | Yes | `type=string` | Yes | Yes |
| `Integer` | `0x39` | `39 <4-byte signed int>` | Yes | Yes | `type=integer` | Yes | Yes |
| `Boolean` | `0x35` | `35 01` / `35 00` | Yes | Yes | `type=boolean` | Yes | Yes |
| `Long` | `0x3a` | `3a <8-byte signed long>` | Yes | Yes | `type=long` | Yes | Yes |
| `Float` | `0x3b` | `3b <4-byte IEEE-754 float>` | Yes | Yes | `type=float` | Yes | Yes |
| `Double` | `0x3c` | `3c <8-byte IEEE-754 double>` | Yes | Yes | `type=double` | Yes | Yes |
| Custom object | Varies | Geode/DataSerializable object payload | No | No | Not yet defined | No | No |

## Confirmed Primitive Wire Shapes

### Boolean

| Value | Hex |
|---|---|
| `Boolean.TRUE` | `3501` |
| `Boolean.FALSE` | `3500` |

### Integer

| Value | Hex |
|---|---|
| `Integer.valueOf(7)` | `3900000007` |

### Long

| Value | Hex |
|---|---|
| `Long.valueOf(7L)` | `3a0000000000000007` |
| `Long.valueOf(-7L)` | `3afffffffffffffff9` |
| `Long.valueOf(9876543210L)` | `3a000000024cb016ea` |

### Float

| Value | Hex |
|---|---|
| `Float.valueOf(7.25f)` | `3b40e80000` |
| `Float.valueOf(-7.25f)` | `3bc0e80000` |
| `Float.valueOf(987654.25f)` | `3b49712064` |
| `Float.valueOf(0.0f)` | `3b00000000` |

### Double

| Value | Hex |
|---|---|
| `Double.valueOf(7.25d)` | `3c401d000000000000` |
| `Double.valueOf(-7.25d)` | `3cc01d000000000000` |
| `Double.valueOf(9876543.210d)` | `3c4162d687e6b851ec` |
| `Double.valueOf(0.0d)` | `3c0000000000000000` |

## Couchbase Persistence Format

Values are persisted as typed JSON documents.

### String

```json
{
  "type": "string",
  "value": "example"
}
```

### Integer

```json
{
  "type": "integer",
  "value": 12345
}
```

### Boolean

```json
{
  "type": "boolean",
  "value": true
}
```

### Long

```json
{
  "type": "long",
  "value": 9876543210
}
```

### Float

```json
{
  "type": "float",
  "value": 7.25
}
```

### Double

```json
{
  "type": "double",
  "value": 7.25
}
```

## Current Test Coverage

| Test Area | Coverage |
|---|---|
| Primitive shape tests | Boolean, Integer, Long, Float, Double |
| Response writer tests | String, Integer, Boolean, Long, Float, Double, mixed `VersionedObjectList` payloads |
| Handler unit tests | `GetHandler`, `GetAllHandler`, `PutHandler`, `PutAllHandler` include Float and Double coverage |
| Repository tests | Repository factory and typed persistence paths compile and validate successfully |
| Integration tests | CRUD and serialization integration tests validate typed round trips through the shim and Couchbase |

## Latest Verified Test Result

Latest full verification result:

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

## Known Gaps / Next Compatibility Targets

| Target | Priority | Notes |
|---|---:|---|
| `Short` | High | Natural next primitive wrapper after Float/Double. |
| `Byte` | High | Small primitive wrapper; likely easy to support once marker is confirmed. |
| `Character` | Medium | Useful for completeness, but less common in cache values. |
| `BigInteger` / `BigDecimal` | Medium | Useful for financial/business data; requires shape discovery. |
| Custom Java serialized objects | High | Required for broader real-world GemFire migration compatibility. |
| Geode PDX objects | High | Important for enterprise GemFire/Geode apps. |
| Object arrays / collections | Medium | Needed for complex cache values. |
| Null explicit values | Medium | Missing-key behavior is supported; explicit null value semantics need further validation. |
