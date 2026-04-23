# ProtoGemCouch Compatibility Matrix

## Status legend

- **Supported** — implemented and validated end-to-end
- **Partially Supported** — implemented enough for current flows, but with semantic gaps
- **Discovered / Not Implemented** — opcode/request shape known, but no handler yet
- **Unsupported** — not implemented and not yet analyzed

---

## Current summary

ProtoGemCouch currently supports a core set of GemFire/Geode client operations well enough for basic application flows:

- `GET`
- `PUT`
- `REMOVE`
- `CONTAINS_KEY`
- `GET_ALL`
- `PUT_ALL`
- `SIZE`
- `KEY_SET`
- `PING`
- `CONTROL`

The current integration suite is green for:

- `containsKeyOnServer`
- `getAll`
- `keySetOnServer`
- `putAll`
- `put/get/remove`
- `sizeOnServer` :contentReference[oaicite:0]{index=0} :contentReference[oaicite:1]{index=1}

---

## Compatibility matrix

| Operation | Opcode | Request Shape Known | Response Shape Implemented | Probe Completed | Unit Tested | Integration Tested | Status | Semantic Gaps / Risks | Notes |
|---|---:|---|---|---|---|---|---|---|---|
| GET | 0 | Yes | Yes | Yes | In progress | Yes | Supported | Response fidelity should continue to be regression tested | Basic get path works |
| PUT | 7 | Yes | Yes | Yes | In progress | Yes | Supported | Parsing is positional and based on observed layout; should keep regression coverage | Current put path works with integration tests |
| REMOVE | 9 | Yes | Yes | Yes | In progress | Yes | Partially Supported | Current behavior is delete-only; `region.remove(key)` returns `null` instead of the removed value | This is a known semantic gap |
| CONTAINS_KEY | 38 | Yes | Yes | Yes | In progress | Yes | Supported | Only implemented modes should be treated as reliable | `containsKeyOnServer` works |
| CONTAINS_VALUE_FOR_KEY | 38 + mode 1 | Yes | Yes | Yes | In progress | Not yet | Partially Supported | Handler path exists, but no dedicated integration test yet | Same opcode as contains, mode-based behavior |
| CONTAINS_VALUE | 38 + mode 2 | Partially | No meaningful support | Partially | No | No | Unsupported | Currently returns false / not implemented | Must not be treated as real support |
| GET_ALL | 100 | Yes | Yes | Yes | In progress | Yes | Supported | Chunked response should stay under regression coverage | Working in integration tests |
| PUT_ALL | 56 | Yes | Yes | Yes | In progress | Yes | Supported | Bulk semantics may still differ from full Geode versioning/callback behavior | Current client flow works |
| SIZE / sizeOnServer | 81 | Yes | Yes | Yes | In progress | Yes | Supported | Count is backed by query over document ID prefix; behavior depends on current key model | Works for current region-to-doc mapping |
| KEY_SET / keySetOnServer | 40 | Yes | Yes | Yes | In progress | Yes | Supported | Returned from current Couchbase prefix query model; large result sets may need later tuning | Working in tests |
| CONTROL | 18 | Yes | Yes | Observed | Minimal | Not directly | Supported | Simple ack only | Seen in live traffic and acknowledged successfully |
| PING | 5 | Yes | Yes | Observed | Minimal | Not directly | Supported | Simple ack only | Infrastructure-level support |
| GET_CLIENT_PARTITION_ATTRIBUTES | 73 | Yes | No real response behavior | Yes | No | No | Discovered / Not Implemented | Observed and logged only; not real support yet | Must not be relied on |
| Unknown opcodes | Various | N/A | Fallback logging only | Ongoing | Yes | N/A | Partially Supported | Safe fallback is log-and-ignore, not feature support | Good for discovery, not compatibility |

---

## Supported operations detail

### GET
- Region and key are parsed from the first two parts.
- Value is read from Couchbase using the constructed document ID.
- Null and found-value response paths are implemented.

### PUT
- Request shape is known from probe traffic and integration testing.
- Region, key, and value are extracted from observed part positions.
- Value is stored in Couchbase as:
  ```json
  { "value": "<payload>" }