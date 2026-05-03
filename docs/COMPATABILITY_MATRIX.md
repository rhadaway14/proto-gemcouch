
---

# `docs/COMPATIBILITY_MATRIX.md`

```markdown
# ProtoGemCouch Compatibility Matrix

## Current Validation Status

This matrix tracks Geode client operations tested against `ProtoGemCouch`.

Validation levels:

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
| Create / Put | `region.put(key, value)` | Validated | String-like values stored in Couchbase |
| Read / Get | `region.get(key)` | Validated | Returns Java `String` for validated string path |
| Update / Put overwrite | `region.put(existingKey, newValue)` | Validated | Follow-up `get` returns updated value |
| Delete / Remove | `region.remove(key)` | Validated | Follow-up `get` returns `null` |
| Contains key | `region.containsKeyOnServer(key)` | Validated | Existing and missing paths validated |
| Contains value for key | `containsValueForKeyOnServer(...)` helper path | Validated | Existing and missing paths validated |
| Bulk read | `region.getAll(keys)` | Validated | Existing and missing-key paths validated |
| Bulk write | `region.putAll(map)` | Validated | Multiple entries and overwrite path validated |
| Server size | `region.sizeOnServer()` | Validated | Uses Geode `Integer` payload shape |
| Server key set | `region.keySetOnServer()` | Validated | Uses Geode `List<String>` payload shape |

---

## Current Automated Integration Tests

Integration test class:

```text
src/test/java/com/protogemcouch/integration/ProtoGemCouchCrudIntegrationTest.java