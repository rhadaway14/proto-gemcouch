# ProtoGemCouch Compatibility Matrix

## Current Validation Status

Last updated after successful full verification:

```text
mvn clean verify
Tests run: 128, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Supported Operations

| Operation | Status | Notes |
|---|---:|---|
| Client connect / handshake | Supported | Shim accepts Geode client connections and routes supported operations. |
| Region access | Supported | Tested with `/helloWorld` / `helloWorld` region-style access. |
| `put` | Supported | Supports typed primitive value decoding and Couchbase persistence. |
| `get` | Supported | Supports typed primitive value response serialization. |
| `putAll` | Supported | Supports batch typed primitive decoding and Couchbase persistence. |
| `getAll` | Supported | Supports VersionedObjectList-compatible response payloads. |
| `remove` | Supported | Removes mapped Couchbase document. |
| `containsKey` / contains-style checks | Supported | Repository-backed key/value existence checks. |
| `sizeOnServer` | Supported | Region-size query path covered by unit tests. |
| `keySetOnServer` | Supported | Returns region keys using Geode-compatible list payload. |
| Unknown opcode handling | Supported | Logs unknown frame details and does not crash. |

## Supported Value Types

| Java / Geode Type | Geode DataSerializer Marker | Example Shape | Runtime Support | Unit Coverage | Integration Coverage |
|---|---:|---|---|---|---|
| `String` | `0x57` | `57 00 <len> <utf8>` | Yes | Yes | Yes |
| `Boolean` | `0x35` | `true -> 3501`, `false -> 3500` | Yes | Yes | Yes |
| `Short` | `0x38` | `7 -> 380007` | Yes | Yes | Yes |
| `Integer` | `0x39` | `7 -> 3900000007` | Yes | Yes | Yes |
| `Long` | `0x3a` | `7 -> 3a0000000000000007` | Yes | Yes | Yes |
| `Float` | `0x3b` | `7.25f -> 3b40e80000` | Yes | Yes | Yes |
| `Double` | `0x3c` | `7.25d -> 3c401d000000000000` | Yes | Yes | Yes |

## Verified Primitive Shapes

### Boolean

| Value | Hex |
|---:|---|
| `Boolean.TRUE` | `3501` |
| `Boolean.FALSE` | `3500` |

### Short

| Value | Hex |
|---:|---|
| `(short) 7` | `380007` |
| `(short) -7` | `38fff9` |
| `(short) 0` | `380000` |
| `Short.MAX_VALUE` | `387fff` |
| `Short.MIN_VALUE` | `388000` |

### Integer

| Value | Hex |
|---:|---|
| `7` | `3900000007` |

### Long

| Value | Hex |
|---:|---|
| `7L` | `3a0000000000000007` |
| `-7L` | `3afffffffffffffff9` |
| `9_876_543_210L` | `3a000000024cb016ea` |

### Float

| Value | Hex |
|---:|---|
| `7.25f` | `3b40e80000` |
| `-7.25f` | `3bc0e80000` |
| `987_654.25f` | `3b49712064` |
| `0.0f` | `3b00000000` |

### Double

| Value | Hex |
|---:|---|
| `7.25d` | `3c401d000000000000` |
| `-7.25d` | `3cc01d000000000000` |
| `9_876_543.210d` | `3c4162d687e6b851ec` |
| `0.0d` | `3c0000000000000000` |

## Value Pipeline Coverage

| Component | String | Boolean | Short | Integer | Long | Float | Double |
|---|---:|---:|---:|---:|---:|---:|---:|
| `ValueDecoding` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `StoredValue` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GemResponseWriter` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `PutAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `GetAllHandler` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| `CouchbaseRepository` | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| Serialization integration test | Yes | Yes | Yes | Yes | Yes | Yes | Yes |

## Couchbase Persistence Shape

Current typed primitive documents are persisted using a simple typed envelope:

```json
{
  "type": "<type>",
  "value": <value>
}
```

Examples:

```json
{
  "type": "short",
  "value": 7
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
  "type": "boolean",
  "value": true
}
```

## GET_ALL / VersionedObjectList Compatibility

`getAll` responses are written as a manual VersionedObjectList-compatible payload.

Validated shape:

```text
01 07 03
<key-count>
<geode-string-key-1>
...
<object-count>
<object-marker> <geode-object>
...
```

Object markers:

| Marker | Meaning |
|---:|---|
| `0x01` | Present object |
| `0x03` | Key not at server / absent |

The mixed typed GET_ALL path now includes:

```text
String, Short, Integer, Boolean, Long, Float, Double, Missing
```

## Test Coverage Summary

| Test Class | Purpose | Status |
|---|---|---:|
| `BooleanShapeTest` | Confirms Boolean Geode bytes | Passing |
| `ShortShapeTest` | Confirms Short Geode bytes | Passing |
| `IntegerShapeTest` | Confirms Integer Geode bytes | Passing |
| `LongShapeTest` | Confirms Long Geode bytes | Passing |
| `FloatShapeTest` | Confirms Float Geode bytes | Passing |
| `DoubleShapeTest` | Confirms Double Geode bytes | Passing |
| `GemResponseWriterTest` | Confirms response writer behavior | Passing |
| `GetHandlerTest` | Focused GET typed value coverage | Passing |
| `GetAllHandlerTest` | Focused GET_ALL typed value coverage | Passing |
| `PutHandlerTest` | Focused PUT typed value coverage | Passing |
| `PutAllHandlerTest` | Focused PUT_ALL typed value coverage | Passing |
| `ProtoGemCouchSerializationIntegrationTest` | End-to-end Geode client to Couchbase serialization coverage | Passing |
