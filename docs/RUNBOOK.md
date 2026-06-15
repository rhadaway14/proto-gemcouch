
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
- `ERROR_RESPONSE_MODE` default `exception` (`exception` = reply with a Geode EXCEPTION frame and
  keep the connection open, so the client raises a ServerOperationException; `close` = drop the
  connection on operation failure). The `exception` behavior is validated against a live Geode
  client in `ProtoGemCouchExceptionResponseIntegrationTest`.
- `HANDLER_THREADS` default `64` (size of the executor pool that runs request handlers off the
  Netty event loop; raise it if many connections may block on a slow backend at once)
- `CB_KV_TIMEOUT_MS` default `5000` (per-operation Couchbase KV timeout)
- `CB_CONNECT_TIMEOUT_MS` default `10000` (Couchbase connect timeout)
- `CB_TTL_SECONDS` default `0` (default entry time-to-live in seconds: when `> 0`, value writes get a
  Couchbase document expiry, emulating a Geode entry-time-to-live; `0` disables expiry)
- `CB_TTL_REGIONS` default empty (per-region TTL overrides, e.g. `sessions:1800,cacheA:60`; an exact
  region-name match wins over `CB_TTL_SECONDS`)
- `CB_TTL_MODE` default `ttl` (`ttl` = expiry counts from the last write, i.e. entry-time-to-live;
  `idle` = reads also refresh the expiry via get-and-touch, i.e. entry-idle-time)
- `CB_DURABILITY` default `none` (Couchbase write durability applied to all value writes:
  `none` = ack on the active node's memory; `majority`, `majorityAndPersistToActive`,
  `persistToMajority` = synchronous durability — these require a replicated cluster and will fail
  writes on a single-node cluster)
  - `putAll` is **partial-failure aware**: entries that succeed are persisted and counted in
    `size`/`keySet` even if others fail, and a partial failure is reported to the client as a
    PUT_ALL server error naming the failed keys (rather than silently dropping or rolling back).
  - When a region has a TTL, `size`/`keySet` verify which keys still exist and evict expired ones
    from the keyset metadata, so they stay correct (at the cost of an existence check per key).
- `CB_MAX_VALUE_BYTES` default `20971520` (max encoded value-document size; Couchbase's hard
  per-document ceiling is 20 MiB). A value whose encoded document exceeds this is rejected up front,
  before any backend write, with a clean `ServerOperationException` — so an oversized value never
  reaches Couchbase and never updates the region's keyset. In a `putAll` it is a per-key failure (the
  under-limit entries still persist). Set `0` to disable the check and rely on the backend to reject.
- `CONNECTION_IDLE_TIMEOUT_SECONDS` default `300` (close a connection with no read/write activity
  for this long; `0` disables idle reaping)
- `MAX_CONNECTIONS` default `0` (max concurrent client connections; new connections beyond this
  are rejected and closed; `0` means unlimited)
- `FIRST_REQUEST_TIMEOUT_SECONDS` default `10` (a connection must complete its handshake and first
  request within this long or it is closed; unlike the idle timeout this is not reset by trickled
  bytes, so it bounds slowloris-style connections; `0` disables it)
- `HANDLER_MAX_PENDING_TASKS` default `10000` (per-handler-thread queue bound; once full, further
  requests are shed and the connection closed, instead of letting the backlog grow unbounded;
  `0` means unbounded)
- `TLS_ENABLED` default `false` (terminate TLS on the Geode listener)
- `TLS_KEYSTORE_PATH` / `TLS_KEYSTORE_PASSWORD` / `TLS_KEYSTORE_TYPE` (server keystore; type default
  `PKCS12`; path+password required when `TLS_ENABLED=true`)
- `TLS_CLIENT_AUTH` default `none` (`require` enables mutual TLS / client-certificate auth)
- `TLS_TRUSTSTORE_PATH` / `TLS_TRUSTSTORE_PASSWORD` / `TLS_TRUSTSTORE_TYPE` (truststore for verifying
  client certs; required when `TLS_CLIENT_AUTH=require`)
- `TLS_PROTOCOLS` default `TLSv1.3,TLSv1.2` (comma-separated enabled-protocol allowlist for the Geode
  listener + health HTTPS; legacy SSLv3 / TLS 1.0 / 1.1 excluded; narrow to `TLSv1.3` to require 1.3)
- `TLS_CIPHERS` default unset (optional comma-separated cipher-suite allowlist; unset = provider strong
  defaults)
- `CB_TLS_ENABLED` default `false` (TLS to Couchbase; also implied by a `couchbases://` connstr)
- `CB_TLS_CERT_PATH` (PEM cert to trust for the Couchbase connection)
- `CB_TLS_VERIFY_HOSTNAME` default `true` (verify the Couchbase host against its cert; disable only
  for self-signed certs whose SAN does not match the host)
- `HEALTH_TLS_ENABLED` default `false` (serve the health/admin endpoints over HTTPS, using the same
  server keystore as `TLS_KEYSTORE_*`)
- `HEALTH_BIND_ADDRESS` (interface the health/admin server binds to; unset/blank = all interfaces.
  Set to e.g. `127.0.0.1` or an internal address to restrict exposure)

Geode clients connect to a TLS-enabled shim with `ssl-enabled-components=server` and a truststore
trusting the shim certificate. See `docs/SECURITY.md` and the `protogemcouch-tls` service in
`docker-compose.yml`.

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