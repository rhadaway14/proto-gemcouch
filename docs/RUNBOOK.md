
# ProtoGemCouch Runbook

## Purpose

This runbook explains how to start, stop, validate, troubleshoot, and operate ProtoGemCouch.

---

## Preconditions

Before starting ProtoGemCouch, confirm:

- required environment variables are set
- Couchbase is reachable
- bucket, scope, and collection exist
- `SHIM_PORT` is available
- `HEALTH_PORT` is available

---

## Required configuration

Required:
- `CB_CONNSTR`
- `CB_USERNAME`
- `CB_PASSWORD`
- `CB_BUCKET`
- `CB_SCOPE`
- `CB_COLLECTION`

Optional:
- `SHIM_PORT` default `40405`
- `HEALTH_PORT` default `8081`
- `MAX_FRAME_BYTES` default `52428800` (inbound frame size cap; see `docs/SECURITY.md`)
- `MAX_FRAME_PARTS` default `100000` (inbound frame part-count cap)
- `ERROR_RESPONSE_MODE` default `close` (`close` = drop the connection on operation failure;
  `exception` = reply with a Geode EXCEPTION frame and keep the connection open). `exception`
  mode is pending live-client validation and should stay `close` until then.

Example:

```bash
export CB_CONNSTR=couchbase://127.0.0.1
export CB_USERNAME=Administrator
export CB_PASSWORD=password
export CB_BUCKET=test
export CB_SCOPE=_default
export CB_COLLECTION=_default
export SHIM_PORT=40405
export HEALTH_PORT=8081
```

---

## Backend (Couchbase) failure behavior

The shim distinguishes a legitimate **miss** from an infrastructure **failure**:

- A missing document (`get`, `containsKey`, `containsValueForKey`) or a region with no keys
  (`keySet`, `size`) returns a normal empty/absent result — this is correct and expected.
- An infrastructure failure (Couchbase unreachable, KV timeout, auth rejected, decode failure)
  is **not** masked as an empty result. The operation fails: it is recorded as an operation
  error and the request is terminated.

This matters because masking a failure as "empty" would let a Couchbase outage look to clients
like a cache that genuinely has no data, which can cause incorrect application decisions
(treating absent data as authoritative). Failing loudly is the safer behavior.

### What you will observe during a Couchbase outage

- Per-operation error counters rise: `protogemcouch_operation_errors_total` and
  `protogemcouch_request_errors_total` (visible on the Grafana dashboard and `/metrics`).
- Structured `repository_*_error` logs are emitted with the cause.
- Affected client connections are closed by default (`ERROR_RESPONSE_MODE=close`). Clients
  should reconnect/retry. An opt-in `ERROR_RESPONSE_MODE=exception` mode instead replies with a
  Geode EXCEPTION frame and keeps the connection open, but is pending live-client validation.

### Operator actions

1. Confirm Couchbase health (cluster up, bucket reachable, credentials valid).
2. Check shim logs for `repository_*_error` events to identify the failing operation and cause.
3. Once Couchbase recovers, error rates should return to zero with no shim restart required.