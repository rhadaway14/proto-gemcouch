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
| Basic request/response loop | Working | Validated through CRUD sample flow. |
| Health endpoints | Working | `/live` and `/ready` are working. |
| Control frames | Working | Control frames are observed and handled in the current flow. |

---

## CRUD Operations

| Operation | Status | Notes |
|---|---|---|
| PUT / create | Working | Validated end-to-end against Couchbase for string-like values. |
| GET / read | Working | Validated end-to-end; sample app currently receives `byte[]` and decodes locally. |
| PUT / update | Working | Validated end-to-end for the same document path. |
| REMOVE / destroy | Working | Validated for the tested sample flow; remove returns normally and backend state is correct. |
| Delete verification | Working | Verified by follow-up absence check after remove. |

---

## Lookup and Existence Semantics

| Operation | Status | Notes |
|---|---|---|
| Existence check in sample flow | Working | Validated using GET-based existence verification. |
| Native contains semantics | Partial | Backend contains path exists, but broader native client type fidelity still needs expansion and validation. |
| Null / missing GET response | Working | Missing document path is observed and handled in current sample verification. |

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
| String-like values | Working | Validated in the current sample path. |
| Native Java String return type | Partial | Values currently come back to the sample app as `byte[]`, with local decoding. |
| Arbitrary Java objects | Planned | Not yet supported as a validated claim. |
| PDX objects | Planned | Not yet validated. |
| Custom serialized objects | Planned | Not yet validated. |
| Complex nested payloads | Planned | Not yet validated. |

---

## Serialization / Deserialization

| Area | Status | Notes |
|---|---|---|
| Request fallback decode for string-like values | Working | Current demo path depends on this. |
| Native Geode DataSerializer use in shim runtime | Partial | Initialization issues still exist in the shim runtime. |
| Response-side wire framing for validated CRUD flow | Working | Validated for current create/read/update/delete sample path. |
| Full object serialization fidelity | Planned | Not yet supported as a validated claim. |

---

## Error Handling and Recovery

| Area | Status | Notes |
|---|---|---|
| Missing document on GET | Working | Validated in delete verification flow. |
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
| Runbooks / launch docs | Working | Present, but should continue evolving as compatibility expands. |

---

## Current Supported Claim

The current safe supported claim is:

**ProtoGemCouch supports a validated Geode client CRUD sample flow for string-like values against Couchbase, including working create, read, update, and destroy/remove behavior for the tested path.**

---

## Current Unsupported or Not-Yet-Validated Claim

The following claims should **not** yet be made:

- full Geode server replacement
- broad native compatibility across arbitrary Java object types
- full PDX compatibility
- complete parity for all Geode operations
- production-grade compatibility guarantees across all client behaviors

---

## Next Expansion Targets

Recommended next validation targets:

1. native contains semantics without sample-side normalization workarounds
2. GET_ALL and PUT_ALL against a real Geode client comparison
3. KEY_SET and SIZE validation
4. broader object types
5. regression tests based on captured real Geode wire responses