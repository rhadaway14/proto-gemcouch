# ProtoGemCouch Compatibility Matrix

## Current Validation Status

This matrix tracks Geode client operations tested against `ProtoGemCouch`.

Latest validated results:

```text
Unit tests: 83 passing
Integration tests: 9 passing
Build: SUCCESS
```

Integration coverage:

```text
ProtoGemCouchCrudIntegrationTest: 7 passing
ProtoGemCouchSerializationIntegrationTest: 2 passing
```

The latest validated milestone includes the previous string-based operation matrix plus single-key Java `Integer` round-trip support.

---

## Validation Levels

| Status | Meaning |
|---|---|
| Validated | Covered by automated integration tests using a real Geode client, shim, and Couchbase |
| Unit Tested | Covered by unit tests but not yet proven through the real client integration path |
| Partial | Some behavior exists, but compatibility is not complete |
| Not Started | Not implemented or not tested |
| Out of Scope | Not currently planned for the validated MVP path |

---

## Core Region Operations

| Operation | Geode Client API | Status | Notes |
|---|---|---|---|
| Connect | `ClientCacheFactory().addPoolServer(...)` | Validated | Real Geode client connects to shim port `40405` |
| Create / Put string | `region.put(key, "value")` | Validated | String-like values stored in Couchbase |
| Read / Get string | `region.get(key)` | Validated | Returns Java `String` for validated string path |
| Update / Put string overwrite | `region.put(existingKey, "newValue")` | Validated | Follow-up `get` returns updated string value |
| Delete / Remove | `region.remove(key)` | Validated | Follow-up `get` returns `null` |
| Contains key | `region.containsKeyOnServer(key)` | Validated | Existing and missing paths validated |
| Contains value for key | `containsValueForKeyOnServer(...)` helper path | Validated | Existing and missing paths validated |
| Bulk read strings | `region.getAll(keys)` | Validated | Existing and missing-key paths validated for strings |
| Bulk write strings | `region.putAll(map)` | Validated | Multiple string entries and overwrite path validated |
| Server size | `region.sizeOnServer()` | Validated | Uses Geode `Integer` payload shape |
| Server key set | `region.keySetOnServer()` | Validated | Uses Geode `List<String>` payload shape |
| Create / Put integer | `region.put(key, Integer.valueOf(...))` | Validated | Single-key integer write validated |
| Read / Get integer | `region.get(key)` | Validated | Single-key integer read returns Java `Integer` |
| Update / Put integer overwrite | `region.put(existingKey, Integer.valueOf(...))` | Validated | Follow-up `get` returns updated integer value |

---

## Current Automated Integration Tests

### CRUD / Bulk / Server Operations

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchCrudIntegrationTest.java
```

Validated tests:

| Test | Purpose |
|---|---|
| `validatedCrudAndContainsBaselineShouldPassAndShimLogsShouldStayClean` | Validates PUT, GET, update, contains, remove, and missing behavior |
| `getAllForExistingStringKeysShouldReturnExpectedValues` | Validates `getAll(...)` for existing string keys |
| `getAllWithMissingKeyShouldReturnOnlyExistingValuesOrNullForMissingKey` | Validates `getAll(...)` with a missing key |
| `putAllForStringValuesShouldPersistAllEntriesAndBeReadableByGetAndGetAll` | Validates `putAll(...)`, direct reads, and follow-up `getAll(...)` for strings |
| `putAllShouldOverwriteExistingStringValues` | Validates string `putAll(...)` overwrite behavior |
| `sizeOnServerShouldReturnCurrentRegionCount` | Validates `sizeOnServer()` response compatibility |
| `keySetOnServerShouldReturnCurrentKeys` | Validates `keySetOnServer()` response compatibility |

Latest validated result:

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

---

### Serialization Operations

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchSerializationIntegrationTest.java
```

Validated tests:

| Test | Purpose |
|---|---|
| `integerValueShouldRoundTripThroughShimAndCouchbase` | Validates single-key `Integer` PUT/GET round trip |
| `integerValueShouldBeOverwrittenByAnotherIntegerValue` | Validates overwriting an existing integer value |

Latest validated result:

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

---

## Value Support

| Value Type | Status | Notes |
|---|---|---|
| String | Validated | Main supported path across single-key and bulk operations |
| Missing/null read | Validated | `get` missing returns `null`; `getAll` missing handled |
| Integer | Validated | Validated for single-key `PUT`, `GET`, and overwrite |
| Integer in bulk operations | Not Started | `putAll(...)` / `getAll(...)` with integer values not yet validated |
| Boolean as value | Partial | Boolean response encoding exists for contains response, not general value storage |
| Java serialized object | Not Started | Requires object serialization/deserialization work |
| PDX object | Not Started | Requires PDX protocol compatibility work |
| JSON document object | Not Started | Could be mapped into Couchbase JSON, but not yet Geode-compatible |
| Binary/blob value | Not Started | Needs byte-array response shape validation |

---

## Typed Value Storage

String values continue to use the existing raw string storage path.

Integer values currently use a lightweight internal typed envelope before being stored through the existing repository string contract.

Current integer envelope shape:

```text
__PROTOGEMCOUCH_TYPED__|integer|<value>
```

Example:

```text
__PROTOGEMCOUCH_TYPED__|integer|12345
```

This allows `GetHandler` to distinguish between:

- a real string value like `"12345"`
- a typed integer value like `Integer.valueOf(12345)`

This is an interim compatibility design. A future repository refactor should replace the string-only repository contract with a first-class typed value contract.

Recommended future repository shape:

```java
StoredValue get(String docId);
Map<String, StoredValue> getAll(String region, List<String> keys);
void put(String docId, StoredValue value);
```

---

## Repository / Couchbase Behavior

| Capability | Status | Notes |
|---|---|---|
| Put string document | Validated | Used by single-key string `PUT` and string `PUT_ALL` |
| Get string document | Validated | Used by single-key string `GET` and string `GET_ALL` |
| Put integer document | Validated | Validated for single-key `PUT` using typed envelope |
| Get integer document | Validated | Validated for single-key `GET` using typed envelope |
| Remove document | Validated | Used by `REMOVE` |
| Exists check | Validated | Used by `containsKeyOnServer` |
| Contains value for key | Validated | Used by `containsValueForKeyOnServer` |
| Bulk get strings | Validated | Used by `GET_ALL` string path |
| Bulk put strings | Validated | Used by `PUT_ALL` string path |
| Bulk get typed values | Not Started | Requires typed repository contract |
| Bulk put typed values | Not Started | Requires typed repository contract |
| Size | Validated | Used by `sizeOnServer` |
| Key list | Validated | Used by `keySetOnServer` |

---

## Wire Shape Discoveries

### Geode String

Used for string values and keys.

Shape:

```text
57 <2-byte-length> <UTF-8 bytes>
```

Example:

```text
57 00 05 6b65792d31
```

Meaning:

```text
String: key-1
```

---

### Geode Integer

Used for `sizeOnServer()` and integer value responses.

Shape:

```text
39 00 00 00 07
```

Meaning:

```text
Integer.valueOf(7)
```

Usage:

| Context | Status |
|---|---|
| `sizeOnServer()` response | Validated |
| Single-key integer `GET` response | Validated |
| Integer values in `GET_ALL` | Not Started |
| Integer values in `PUT_ALL` | Not Started |

---

### Geode List

Used for `keySetOnServer()` response payload.

Shape:

```text
41 <small-count> <geode-string> <geode-string> ...
```

Example:

```text
41 03
57 00 05 6b65792d31
57 00 05 6b65792d32
57 00 05 6b65792d33
```

Meaning:

```text
List.of("key-1", "key-2", "key-3")
```

Important note:

`keySetOnServer()` required a list-shaped payload. A Geode set payload deserialized successfully as a `LinkedHashSet`, but the Geode client path attempted to cast it to `List`, causing a `ClassCastException`.

---

### Geode Set

Observed but not used for `keySetOnServer()`.

Shape:

```text
49 <small-count> <geode-string> <geode-string> ...
```

Example:

```text
49 03
57 00 05 6b65792d31
57 00 05 6b65792d32
57 00 05 6b65792d33
```

Meaning:

```text
Set.of("key-1", "key-2", "key-3")
```

Status:

| Context | Status |
|---|---|
| Shape discovery | Unit Tested |
| `keySetOnServer()` response | Not Used |
| Other set-valued responses | Not Started |

---

### VersionedObjectList-Compatible Payload

Used for `getAll(...)`.

Validated shape for:

```text
keys: key-1, key-2, missing
values: value-1, value-2, missing/null
```

Payload:

```text
010703035700056b65792d315700056b65792d325700076d697373696e67030157000776616c75652d31015700077616c75652d320329
```

Meaning:

- `01 07` = observed validated header
- following small count = key count
- keys are encoded as Geode strings
- second small count = value count
- present values use present marker plus Geode string payload
- missing values use absent/null marker shape

Status:

| Context | Status |
|---|---|
| `GET_ALL` with string values | Validated |
| `GET_ALL` with missing key | Validated |
| `GET_ALL` with integer values | Not Started |
| `GET_ALL` with mixed typed values | Not Started |

---

## Operation Matrix by Value Type

| Operation | String | Integer | Boolean | JSON/Object | PDX | Binary |
|---|---|---|---|---|---|---|
| `put` | Validated | Validated | Not Started | Not Started | Not Started | Not Started |
| `get` | Validated | Validated | Not Started | Not Started | Not Started | Not Started |
| overwrite with `put` | Validated | Validated | Not Started | Not Started | Not Started | Not Started |
| `remove` | Validated | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral |
| `containsKeyOnServer` | Validated | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral |
| `containsValueForKeyOnServer` | Validated | Not Started | Not Started | Not Started | Not Started | Not Started |
| `getAll` | Validated | Not Started | Not Started | Not Started | Not Started | Not Started |
| `putAll` | Validated | Not Started | Not Started | Not Started | Not Started | Not Started |
| `sizeOnServer` | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral |
| `keySetOnServer` | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral | Type-neutral |

---

## Region and Cache Semantics

| Feature | Status | Notes |
|---|---|---|
| Single region path | Validated | Validated with `/helloWorld` / `helloWorld` test path |
| Multiple regions | Partial | Region-to-document-ID mapping exists, but not broadly validated |
| Region-to-collection mapping | Partial | Current backend path uses one bucket/scope/collection |
| Region destroy | Not Started | No validated compatibility path yet |
| Region clear | Not Started | No validated compatibility path yet |
| Local cache behavior | Not Started | Proxy region path is the validated path |
| Server region metadata compatibility | Partial | Enough for tested operations, not full Geode metadata compatibility |

---

## Query / Function / Event Features

| Feature | Status |
|---|---|
| OQL queries | Not Started |
| Indexes | Not Started |
| Function execution | Not Started |
| Register interest | Not Started |
| Subscriptions | Not Started |
| Continuous queries | Not Started |
| Event callbacks | Not Started |
| Listener behavior | Not Started |

---

## Serialization

| Feature | Status | Notes |
|---|---|---|
| String encoding | Validated | Used by string values and keys |
| Integer response encoding | Validated | Used by `sizeOnServer()` and single-key integer `GET` |
| Integer request decoding | Validated | Used by single-key integer `PUT` |
| Boolean response encoding | Validated for contains response | General boolean value round trip is not validated |
| List response encoding | Validated | Used by `keySetOnServer()` |
| Set response encoding | Unit Tested | Discovered shape but not used in validated client path |
| VersionedObjectList-compatible response | Validated | Used by string `getAll(...)` |
| General object deserialization | Partial | Fallback exists, but production-safe typed behavior is not validated |
| PDX | Not Started | Requires PDX protocol compatibility work |
| DataSerializable custom classes | Not Started | Requires class-aware serialization support |

---

## Production Hardening

| Area | Status | Notes |
|---|---|---|
| TLS from Geode client to shim | Not Started | Required for production-like security |
| TLS from shim to Couchbase | Not Started | Required for Capella/secure deployment scenarios |
| Authentication | Not Started | Geode client auth path not yet validated |
| Authorization | Not Started | No role/permission model yet |
| Secrets management | Not Started | Current local validation uses simple config |
| Backpressure | Not Started | Needed under high load |
| Connection pooling hardening | Partial | Basic client connection validated |
| Reconnect handling | Not Started | Needs explicit tests |
| Timeout handling | Partial | Needs client and Couchbase failure-mode tests |
| Error response mapping | Partial | Happy paths are the focus so far |
| Unsupported opcode behavior | Unit Tested | Needs client-facing compatibility strategy |
| Metrics | Partial | Health/readiness and structured logs exist; Prometheus metrics not complete |
| Structured logs | Partial | Useful handler logs exist |
| Load testing | Not Started | Required before production readiness |
| Soak testing | Not Started | Required before production readiness |
| Chaos testing | Not Started | Required for failure-mode confidence |
| Docker Compose deployment | Validated | Used by integration tests |
| Kubernetes deployment | Not Started | Future deployment target |
| Helm chart | Not Started | Future deployment target |

---

## Current MVP Statement

The current MVP supports a real Java Geode client using a proxy region to perform core string-based region operations against Couchbase through the `ProtoGemCouch` shim.

It also supports single-key integer value round trips for `PUT`, `GET`, and overwrite behavior.

Validated operations:

```text
put string
get string
overwrite string
remove
containsKeyOnServer
containsValueForKeyOnServer
getAll strings
putAll strings
sizeOnServer
keySetOnServer
put integer
get integer
overwrite integer
```

Validated backend:

```text
Couchbase bucket: test
scope: _default
collection: _default
```

Validated deployment path:

```text
Docker Compose
Couchbase container
Couchbase init container
ProtoGemCouch shim container
Maven Failsafe integration tests
```

---

## Known Limitations

- validated value path is still string-first
- integer values are validated only for single-key `PUT`, `GET`, and overwrite
- integer values are not yet validated in `putAll(...)` or `getAll(...)`
- mixed typed bulk responses are not yet validated
- repository contract is still string-based
- typed storage currently uses an interim envelope string
- protocol compatibility is operation-specific
- response shapes are manually implemented for known paths
- not all Geode/GemFire operations are supported
- not yet validated with a real customer application
- not yet validated under production load
- no TLS/auth production hardening yet
- no CI pipeline yet
- no Kubernetes/Helm deployment yet

---

## Recommended Next Compatibility Work

### 1. Refactor Repository Contract to Typed Values

Current repository shape:

```java
String get(String docId);
Map<String, String> getAll(String region, List<String> keys);
void put(String docId, String value);
```

Recommended repository shape:

```java
StoredValue get(String docId);
Map<String, StoredValue> getAll(String region, List<String> keys);
void put(String docId, StoredValue value);
```

Reason:

The current string-only repository contract made sense for the first milestone, but typed values are now part of the compatibility path. Refactoring to `StoredValue` will make typed bulk support and future object support much cleaner.

---

### 2. Add Typed Bulk Operation Tests

Add integration tests for:

```text
putAll with Integer values
getAll with Integer values
putAll with mixed String and Integer values
getAll with mixed String and Integer values
putAll integer overwrite
```

Expected affected components:

- `PutAllHandler`
- `GetAllHandler`
- `GemResponseWriter.buildGetAllChunkedResponse(...)`
- `Repository`
- `CouchbaseRepository`
- `StoredValue`

---

### 3. Add Boolean Value Round Trip

Boolean response encoding already exists for contains responses, but general boolean value storage is not validated.

Add tests for:

```text
region.put(key, Boolean.TRUE)
region.get(key)
overwrite Boolean.TRUE -> Boolean.FALSE
```

Expected affected components:

- `ValueDecoding`
- `StoredValue`
- `GemResponseWriter`
- `GetHandler`
- `PutHandler`

---

### 4. Add JSON/Object Strategy

Decide whether JSON-like values should be:

- stored as Couchbase JSON and returned as a Geode-compatible object
- stored as raw Geode serialized bytes
- stored as typed envelope metadata plus JSON payload
- handled through PDX/custom serializers

This should be designed before implementing broad object compatibility.

---

## Production-Ready Definition

The project should not be considered production ready until the following are complete:

- operation matrix covers the customer application’s real behavior
- captured traffic from the customer application replays successfully
- unsupported operations fail cleanly
- non-string values used by the app are supported
- typed bulk operations are supported if the app uses them
- load tests meet latency and throughput targets
- soak tests pass
- reconnect behavior is validated
- observability is in place
- security model is defined
- deployment model is repeatable
- rollback plan exists
