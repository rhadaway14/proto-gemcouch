# DEMO_RESULTS

## Summary

The sample CRUD and compatibility demonstration for `ProtoGemCouch` completed successfully against the shim and Couchbase backend.

The validated demo now proves that a Geode client application can:

- connect to the shim
- create data through the shim into Couchbase
- read data back from Couchbase through the shim
- update data through the shim
- verify existence using server-side contains operations
- delete data from Couchbase through the shim
- verify deleted documents are absent
- retrieve multiple documents with `getAll(...)`
- write multiple documents with `putAll(...)`
- overwrite existing documents with `putAll(...)`
- retrieve region size with `sizeOnServer()`
- retrieve server-side keys with `keySetOnServer()`

This is a successful end-to-end milestone for the current project phase.

---

## Demo Environment

### Client

- Java 17-compatible build target
- Apache Geode client libraries
- Real Geode `ClientCache`
- `ClientRegionShortcut.PROXY`
- Integration test class: `ProtoGemCouchCrudIntegrationTest`

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