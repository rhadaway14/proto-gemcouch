# DEMO_RESULTS

## Summary

The sample CRUD and compatibility demonstration for `ProtoGemCouch` completed successfully against the shim and Couchbase backend.

The validated demo now proves that a Geode client application can:

- connect to the shim
- create string data through the shim into Couchbase
- read string data back from Couchbase through the shim
- update string data through the shim
- verify existence using server-side contains operations
- delete data from Couchbase through the shim
- verify deleted documents are absent
- retrieve multiple string documents with `getAll(...)`
- write multiple string documents with `putAll(...)`
- overwrite existing string documents with `putAll(...)`
- retrieve region size with `sizeOnServer()`
- retrieve server-side keys with `keySetOnServer()`
- write Java `Integer` values through the shim into Couchbase
- read Java `Integer` values back through the shim
- overwrite existing Java `Integer` values

This is a successful end-to-end milestone for the current project phase.

---

## Demo Environment

### Client

- Java 17-compatible build target
- Apache Geode client libraries
- Real Geode `ClientCache`
- `ClientRegionShortcut.PROXY`
- Integration test classes:
    - `ProtoGemCouchCrudIntegrationTest`
    - `ProtoGemCouchSerializationIntegrationTest`

### Shim

- `RawShimServer`
- running in Docker Compose
- health endpoint on `8081`
- shim protocol port on `40405`

### Backend

- Couchbase Server running in Docker Compose
- Couchbase bucket: `test`
- scope: `_default`
- collection: `_default`

---

## Validated Build Result

The latest full verification run completed successfully.

### Unit Test Result

```text
Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
```

### Integration Test Results

```text
Running com.protogemcouch.integration.ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

Running com.protogemcouch.integration.ProtoGemCouchSerializationIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

### Final Build Result

```text
BUILD SUCCESS
```

---

## Operations Demonstrated

### 1. Create / PUT String

The sample client successfully writes string values through the Geode client API.

Validated behavior:

- `region.put(key, "value")`
- shim receives Geode protocol request
- shim decodes the Geode string value
- shim stores the document in Couchbase
- response is accepted by the Geode client

Example document ID shape:

```text
/helloWorld::<key>
```

---

### 2. Read / GET String

The client successfully reads string values back through the shim.

Validated behavior:

- `region.get(key)`
- shim maps region/key to Couchbase document ID
- Couchbase value is returned
- shim encodes response as a Geode-compatible string object
- Geode client receives a Java `String`

---

### 3. Update / PUT String Overwrite

The client successfully overwrites existing string values.

Validated behavior:

- initial `put`
- follow-up `put` with a new value
- follow-up `get`
- returned value matches the updated value

---

### 4. Delete / REMOVE

The client successfully removes documents through the shim.

Validated behavior:

- `region.remove(key)`
- shim removes the Couchbase document
- remove response is accepted by the Geode client
- follow-up `get` returns `null`
- follow-up contains checks return `false`

---

### 5. `containsKeyOnServer`

The client successfully validates key existence against the shim.

Validated behavior:

- existing document returns `true`
- deleted/missing document returns `false`

---

### 6. `containsValueForKeyOnServer`

The client successfully validates value existence for a key.

Validated behavior:

- existing key with value returns `true`
- missing/deleted key returns `false`

---

### 7. `getAll(...)` for String Values

The client successfully retrieves multiple string values in one call.

Validated behavior:

- multiple existing keys are returned
- returned values are Java `String` values
- missing keys are handled safely
- response uses a manually validated Geode `VersionedObjectList`-compatible payload

Validated response-shape work:

- manual `VersionedObjectList` payload writer
- Geode string encoding
- missing value marker handling

Current scope:

- string values only
- existing keys
- missing-key behavior

Not yet validated:

- integer values in `getAll(...)`
- mixed typed values in `getAll(...)`
- object/JSON/PDX values in `getAll(...)`

---

### 8. `putAll(...)` for String Values

The client successfully writes multiple string values in one call.

Validated behavior:

- multiple key/value entries are written
- values are decoded from Geode string payloads
- values are persisted into Couchbase
- follow-up `get(...)` confirms stored values
- follow-up `getAll(...)` confirms bulk reads after bulk writes

Validated overwrite behavior:

- existing string values can be overwritten with `putAll(...)`
- follow-up direct `get(...)` confirms updated values

Current scope:

- string values only

Not yet validated:

- integer values in `putAll(...)`
- mixed string/integer values in `putAll(...)`
- object/JSON/PDX values in `putAll(...)`

---

### 9. `sizeOnServer()`

The client successfully retrieves server-side region size.

Validated behavior:

- documents are inserted
- `sizeOnServer()` returns a value at least equal to the inserted test documents
- response uses a Geode-serialized `Integer`

Validated integer shape:

```text
39 00 00 00 07
```

Meaning:

```text
0x39 = Geode Integer marker
next 4 bytes = integer value
```

---

### 10. `keySetOnServer()`

The client successfully retrieves server-side keys.

Validated behavior:

- documents are inserted
- `keySetOnServer()` returns a set containing the inserted keys
- response payload is encoded as a Geode `List<String>` because the Geode client path expects a list internally

Validated list shape:

```text
41 03
57 00 05 6b65792d31
57 00 05 6b65792d32
57 00 05 6b65792d33
```

Meaning:

```text
0x41 = Geode List marker
0x03 = small list size
0x57 = Geode String marker
```

Important note:

A Geode `Set<String>` payload was also tested and successfully deserialized to a `LinkedHashSet`, but the `keySetOnServer()` client path attempted to cast the response to `List`, causing a `ClassCastException`. The validated response path therefore uses a Geode `List<String>` payload.

Observed but not used for `keySetOnServer()`:

```text
49 03
57 00 05 6b65792d31
57 00 05 6b65792d32
57 00 05 6b65792d33
```

Meaning:

```text
0x49 = Geode Set marker
```

---

### 11. Integer Value Round Trip

The client successfully writes and reads Java `Integer` values through the shim.

Validated behavior:

- `region.put(key, Integer.valueOf(...))`
- shim decodes the Geode integer payload
- shim stores the value using a lightweight typed envelope
- `region.get(key)` returns a Java `Integer`
- overwrite from one integer value to another integer value succeeds
- follow-up `get(...)` returns the updated integer value

Validated tests:

```text
integerValueShouldRoundTripThroughShimAndCouchbase
integerValueShouldBeOverwrittenByAnotherIntegerValue
```

Validated integer response shape:

```text
39 00 00 00 07
```

Meaning:

```text
0x39 = Geode Integer marker
next 4 bytes = integer value
```

Current scope:

- single-key `PUT`
- single-key `GET`
- integer overwrite with `PUT`

Not yet validated:

- integer values in `putAll(...)`
- integer values in `getAll(...)`
- mixed string/integer bulk responses
- typed Couchbase JSON document storage

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

---

## Current Automated Test Coverage

### Unit and Golden-Wire Tests

The unit test suite validates:

- configuration validation
- repository factory behavior
- handler behavior
- byte utilities
- document key generation
- response writer shapes
- golden-wire response stability
- Geode integer shape
- Geode list shape
- Geode set shape
- VersionedObjectList-compatible shape

Latest unit result:

```text
Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
```

---

### CRUD / Bulk / Server Operations Integration Tests

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

### Serialization Integration Tests

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

## Final Verified State

The project now has verified end-to-end behavior for the following operation categories:

- single-key string writes
- single-key string reads
- single-key string overwrites
- single-key string deletes
- contains checks
- bulk string reads
- bulk string writes
- server-side size
- server-side key listing
- single-key integer writes
- single-key integer reads
- single-key integer overwrites

---

## What This Demo Proves

This demo proves that the current shim implementation can support a meaningful subset of Geode client behavior against Couchbase.

The validated path proves:

- a real Geode Java client can connect to the shim
- the shim can parse Geode protocol operations
- the shim can translate operations into Couchbase KV operations
- the shim can encode Geode-compatible responses
- Couchbase can act as the persistence backend for the tested region operations
- automated Docker-based integration tests can validate the whole stack
- string values work across the broader validated operation matrix
- integer values work for single-key `PUT`, `GET`, and overwrite behavior

---

## Current Scope

Validated:

- Java Geode client
- proxy region behavior
- string-like values across the core operation matrix
- integer values for single-key `PUT`, `GET`, and overwrite
- Couchbase KV persistence
- single bucket/scope/collection backend
- manually encoded response shapes for the tested operations
- Docker Compose based integration environment

Not yet fully validated:

- integer values in `getAll(...)`
- integer values in `putAll(...)`
- mixed string/integer bulk operations
- complex object values
- JSON object values
- PDX values
- transactions
- queries
- region events
- subscriptions
- continuous queries
- server-side functions
- partition/replication semantics
- multi-region mapping beyond the current tested path
- production-grade security/TLS/authentication
- high-concurrency load behavior
- long-running soak behavior

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

## Conclusion

This demo phase is successful.

The project now has verified:

- end-to-end CRUD behavior through the shim
- persistence in Couchbase
- real Geode client compatibility for the validated operation set
- working `GET_ALL` for string values
- working `PUT_ALL` for string values
- working `sizeOnServer`
- working `keySetOnServer`
- working single-key `Integer` value round trips
- automated unit and integration test coverage
- Docker-based repeatable validation

The next phase is broader serialization support, especially typed bulk operations and JSON/object-style values.
