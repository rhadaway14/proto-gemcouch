# COMPATIBILITY_MATRIX

## Summary

This document tracks the current compatibility status of `ProtoGemCouch` relative to the Geode client behaviors validated so far.

Status values:

- **Working** = validated in current sample/demo flow
- **Partial** = backend or protocol path exists, but behavior is incomplete or only validated in a narrow path
- **Planned** = not yet validated / not yet implemented as a supported claim
- **Unknown** = not yet evaluated enough to classify safely

---

## Client Connection and Basic Session

| Area | Status | Notes |
|---|---|---|
| Client handshake | Working | Geode client can connect and establish a session with the shim. |
| Basic request/response loop | Working | Validated through CRUD and contains sample flow. |
| Health endpoints | Working | `/live` and `/ready` are working. |
| Control frames | Working | Control frames are observed and handled in the current flow. |

---

## CRUD Operations

| Operation | Status | Notes |
|---|---|---|
| PUT / create | Working | Validated end-to-end against Couchbase for supported string values. |
| GET / read | Working | Validated end-to-end; supported string values return to the sample app as `java.lang.String`. |
| PUT / update | Working | Validated end-to-end for the same document path. |
| REMOVE / destroy | Working | Validated for the tested sample flow; remove returns normally and backend state is correct. |
| Delete verification | Working | Verified by native contains checks after remove. |

---

## Lookup and Existence Semantics

| Operation | Status | Notes |
|---|---|---|
| `containsKeyOnServer(key)` | Working | Native protocol path validated. Shim receives `CONTAINS` mode `0` and returns correct Boolean result. |
| Server-side `containsValueForKey(key)` protocol path | Working | Native protocol path validated. Shim receives `CONTAINS` mode `1` and returns correct Boolean result. |
| Missing key contains semantics | Working | Deleted-document path returns `false` for both key existence and value-for-key checks. |
| Missing document logging for contains value-for-key | Working | Expected Couchbase document-not-found case is logged as a normal miss, not a warning/error. |
| Public `region.containsValueForKey(key)` on `PROXY` region | Partial | Public API may resolve locally and return `false` without a server call. Protocol path validated through server proxy reflection in the sample. |
| Null / missing GET response | Working | Missing document path has been observed and handled in current sample verification. |

---

## Collection / Bulk Operations

| Operation | Status | Notes |
|---|---|---|
| GET_ALL | Partial | Basic support exists, but not broadly validated against real Geode behavior. |
| PUT_ALL | Partial | Basic support exists, but not broadly validated against real Geode behavior. |
| KEY_SET | Partial | Response path exists, but broader real-client validation is still needed. |
| SIZE | Partial | Response path exists, but broader real-client validation is still needed. |

---

## Data Type Fidelity

| Area | Status | Notes |
|---|---|---|
| String values | Working | Validated in the current sample path. |
| Native Java String return type | Working | Supported string reads now return as `java.lang.String` in the sample app. |
| Arbitrary Java objects | Planned | Not yet supported as a validated claim. |
| PDX objects | Planned | Not yet validated. |
| Custom serialized objects | Planned | Not yet validated. |
| Complex nested payloads | Planned | Not yet validated. |

---

## Serialization / Deserialization

| Area | Status | Notes |
|---|---|---|
| Deterministic request decode for supported Geode string values | Working | PUT path now decodes supported Geode string payloads without noisy DataSerializer fallback warnings. |
| Response-side Geode string encoding | Working | GET response path is validated for supported string values. |
| Boolean response encoding for contains operations | Working | Contains responses now deserialize correctly as `java.lang.Boolean` on the client. |
| Native Geode DataSerializer use in shim runtime | Partial | DataSerializer initialization remains unreliable in the shim runtime, but the supported string path no longer depends on it. |
| Full object serialization fidelity | Planned | Not yet supported as a validated claim. |

---

## Error Handling and Recovery

| Area | Status | Notes |
|---|---|---|
| Missing document on GET | Working | Validated in delete verification flow. |
| Missing document on contains key | Working | Returns `false`. |
| Missing document on contains value-for-key | Working | Returns `false` and logs a clean miss. |
| Client reconnect in sample flow | Working | Basic reconnect behavior has been exercised successfully. |
| Retry behavior under broader failure conditions | Unknown | Needs more validation. |
| Robust handling of unsupported object types | Partial | Expected to be limited today. |

---

## Observability and Operations

| Area | Status | Notes |
|---|---|---|
| Structured logging | Working | Present and useful in current validation. |
| Metrics summaries | Working | Present and reporting request counts / latencies. |
| Startup validation | Working | Present and validated in current startup flow. |
| Docker packaging | Working | Current demo runs in Docker Compose. |
| Clean expected-miss logging | Working | Expected Couchbase misses in contains-value-for-key are no longer logged as warnings. |
| Runbooks / launch docs | Working | Present, but should continue evolving as compatibility expands. |

---

## Current Supported Claim

The current safe supported claim is:

**ProtoGemCouch supports a validated Geode client CRUD and native contains sample flow for supported string values against Couchbase, including create, read, update, destroy/remove, native key existence checks, native value-for-key existence checks, and clean missing-document semantics for the tested path.**

---

## Current Unsupported or Not-Yet-Validated Claim

The following claims should **not** yet be made:

- full Geode server replacement
- broad native compatibility across arbitrary Java object types
- full PDX compatibility
- complete parity for all Geode operations
- production-grade compatibility guarantees across all client behaviors
- public `region.containsValueForKey(...)` behavior as a fully supported application-level claim for all client region shortcuts

---

## Next Expansion Targets

Recommended next validation targets:

1. GET_ALL and PUT_ALL against a real Geode client comparison
2. KEY_SET and SIZE validation
3. broader object types
4. regression tests based on captured real Geode wire responses
5. automated integration tests for the current validated CRUD + contains profile