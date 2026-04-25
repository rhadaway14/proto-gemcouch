# CURRENT_LIMITATIONS

## Summary

`ProtoGemCouch` has reached a successful demo milestone, but it is not yet a full Apache Geode server replacement.

The current implementation supports an effective end-to-end demo path for string-like values and backend CRUD behavior against Couchbase. The previously blocking destroy/remove reply issue has been resolved for the validated sample flow, but broader protocol and type-fidelity gaps still remain.

---

## 1. Destroy / Remove Reply Compatibility

### Current state
The shim now successfully processes remove requests and returns a Geode-compatible destroy reply for the validated sample flow.

### Validated behavior
- `RemoveHandler` executes successfully
- Couchbase delete succeeds
- the Geode client `region.remove(...)` returns normally in the tested sample app flow
- a follow-up existence check confirms the document is absent

### Remaining limitation
This is currently validated for the tested string-value sample path. It is not yet proven across broader object types, concurrency situations, or all client-side destroy variants.

---

## 2. Response Type Fidelity Is Still Incomplete

### Current state
Some responses that a Geode client would normally expose as native Java types are still returned in a simplified or demo-safe form.

Examples:
- string values are currently returned to the sample app as `byte[]`
- the sample app performs local decoding to verify those values as strings

### Impact
- backend data is correct
- the sample app can function successfully
- client type fidelity is not yet production-grade

---

## 3. Geode DataSerializer Initialization Is Not Reliable in the Shim Runtime

### Current state
`org.apache.geode.DataSerializer` initialization still fails in the shim runtime.

### Observed behavior
- deserialization attempts in request handling can throw initialization errors
- the project currently relies on fallback decoding for string-like payloads

### Impact
- current demo path works for string-like values
- general object serialization/deserialization compatibility is incomplete
- arbitrary complex Geode object types are not yet supported

---

## 4. Supported Data Shape Is Still Narrow

### Current state
The currently validated path is primarily for:

- region + string key
- string-like value payloads

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

### Still needed
Broader operation-by-operation validation is still required, including areas such as:

- native contains semantics
- bulk operations
- key set / size semantics
- error cases
- reconnect and retry behavior
- richer object types and serialization modes

---

## 6. Production Readiness Is Still Partial

### Already present
The project already includes major production-oriented building blocks such as:
- structured logging
- metrics
- startup validation
- benchmark and soak testing work
- deployment packaging
- runbooks and launch criteria

### Still missing for true production readiness
- broader native Geode wire compatibility validation
- broader compatibility testing with real Geode clients
- richer integration and regression coverage around real protocol captures
- clearer guarantees around supported and unsupported data types
- final hardening for reconnect behavior, failures, and compatibility guarantees

---

## 7. Current Positioning

The project should currently be described as:

**A working Geode-to-Couchbase protocol shim prototype with successful end-to-end CRUD demonstration for string-like values, including working destroy/remove replies for the validated sample flow, but not yet a fully native Geode-compatible server replacement.**

---

## Immediate Next Priority

The next highest-priority engineering task is:

## Broaden native wire compatibility beyond the validated CRUD sample path

That work should include:
- expanding the compatibility matrix
- validating more operations against a real Geode server
- validating more payload and object types
- reducing or removing sample-app-side decoding workarounds
- hardening regression coverage around captured real protocol responses

---

## Definition of Done for the Current Limitation Area

The next compatibility phase will be considered successful when:

- the validated CRUD sample continues to pass
- additional Geode client operations are verified without special-case workarounds
- broader response types are surfaced correctly as native Java objects where expected
- compatibility claims are backed by regression tests and captured wire comparisons