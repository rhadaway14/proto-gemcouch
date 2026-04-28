# CURRENT_LIMITATIONS

## Summary

`ProtoGemCouch` has reached a successful demo milestone, but it is not yet a full Apache Geode server replacement.

The current implementation supports an effective end-to-end demo path for supported string values and backend CRUD behavior against Couchbase. Destroy/remove reply compatibility, native String reads, native contains key semantics, and native contains value-for-key semantics have been validated for the current sample path.

Broader protocol coverage and type-fidelity gaps still remain.

---

## 1. Destroy / Remove Reply Compatibility

### Current state
The shim successfully processes remove requests and returns a Geode-compatible destroy reply for the validated sample flow.

### Validated behavior
- `RemoveHandler` executes successfully
- Couchbase delete succeeds
- the Geode client `region.remove(...)` returns normally in the tested sample app flow
- follow-up native contains checks confirm the document is absent

### Remaining limitation
This is currently validated for the tested string-value sample path. It is not yet proven across broader object types, concurrency situations, or all client-side destroy variants.

---

## 2. Response Type Fidelity Has Improved but Is Still Narrow

### Current state
Supported string reads now return to the sample app as native `java.lang.String`.

### Validated behavior
- created string values are read back as `java.lang.String`
- updated string values are read back as `java.lang.String`
- the sample app no longer needs byte-array normalization for the validated String read path

### Remaining limitation
This is validated for supported string values only.

Not yet validated:
- arbitrary Java objects
- PDX objects
- custom serialized objects
- collection/map payloads
- nested object graphs
- broader Geode serialization compatibility

---

## 3. Geode DataSerializer Initialization Is Not Reliable in the Shim Runtime

### Current state
`org.apache.geode.DataSerializer` initialization can still fail in the shim runtime.

### Updated behavior
The supported string PUT path no longer depends on DataSerializer. It now uses deterministic Geode string decoding and logs cleanly.

### Impact
- current demo path works for supported string values
- noisy fallback warnings have been removed from the validated string PUT path
- general object serialization/deserialization compatibility is still incomplete
- arbitrary complex Geode object types are not yet supported

---

## 4. Supported Data Shape Is Still Narrow

### Current state
The currently validated path is primarily for:

- region name
- string key
- supported string value payloads

### Not yet validated
- arbitrary object graphs
- custom serialized types
- PDX objects
- collections / maps with full Geode serialization fidelity
- callbacks and listener semantics
- event metadata fidelity beyond the currently captured paths
- versioning / concurrency semantics beyond the current subset

---

## 5. Compatibility Coverage Is Partial

### Current state
A small but important set of operations is now working for the demo path, including:

- create
- read
- update
- delete
- native key existence checks
- native value-for-key existence checks
- missing-document handling for contains checks

### Still needed
Broader operation-by-operation validation is still required, including areas such as:

- bulk operations
- key set / size semantics
- error cases
- reconnect and retry behavior
- richer object types and serialization modes
- unsupported operation behavior

---

## 6. Public `containsValueForKey(...)` Behavior Requires Careful Positioning

### Current state
The native server-side contains-value-for-key protocol path is validated.

However, the public Geode API call:

- `region.containsValueForKey(key)`

may resolve locally depending on the client region shortcut and may not always send a server request.

### Current validation approach
The sample app validates the native server protocol path by invoking the server proxy through reflection.

### Impact
This is acceptable for protocol validation, but support claims should be precise:

- native mode `1` contains protocol path is validated
- public application-level behavior across all region shortcuts is not yet broadly validated

---

## 7. Production Readiness Is Still Partial

### Already present
The project already includes major production-oriented building blocks such as:

- structured logging
- metrics
- startup validation
- benchmark and soak testing work
- deployment packaging
- runbooks and launch criteria
- clean expected-miss logging for supported contains paths

### Still missing for true production readiness
- broader native Geode wire compatibility validation
- broader compatibility testing with real Geode clients
- richer integration and regression coverage around real protocol captures
- clearer guarantees around supported and unsupported data types
- final hardening for reconnect behavior, failures, and compatibility guarantees

---

## 8. Current Positioning

The project should currently be described as:

**A working Geode-to-Couchbase protocol shim prototype with successful end-to-end CRUD and native contains demonstration for supported string values, including native Java String reads, working destroy/remove replies, native contains key checks, native value-for-key checks, and clean expected-miss handling for the validated sample flow, but not yet a fully native Geode-compatible server replacement.**

---

## Immediate Next Priority

The next highest-priority engineering task is:

## Broaden operation coverage beyond the validated CRUD + contains sample path

That work should include:

- validating GET_ALL and PUT_ALL against real Geode client behavior
- validating KEY_SET and SIZE
- expanding the compatibility matrix
- validating more payload and object types
- hardening regression coverage around captured real protocol responses

---

## Definition of Done for the Current Limitation Area

The next compatibility phase will be considered successful when:

- the validated CRUD + contains sample continues to pass
- additional Geode client operations are verified without special-case workarounds
- broader response types are surfaced correctly as native Java objects where expected
- compatibility claims are backed by regression tests and captured wire comparisons