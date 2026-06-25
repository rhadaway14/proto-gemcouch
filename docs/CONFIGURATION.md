# Configuration reference

A consolidated reference for every operator-facing environment variable the ProtoGemCouch shim reads.
Configuration is **environment-variable only** (twelve-factor); there is no config file. Values are read
once at startup unless noted as hot-reloadable. Defaults are the in-code defaults — unset, blank, or
invalid values fall back to the default (the shim does not fail startup on a bad optional value; only the
**required** connection settings below are validated and will refuse to start if missing).

For the deeper "why" and operational procedures, see `docs/SECURITY.md` (TLS/mTLS, secrets, audit, PDX &
durable persistence), `docs/RUNBOOK.md` (incident response, levers), `docs/DEPLOYMENT.md` (topologies),
`docs/OBSERVABILITY.md` (tracing/metrics/logs), and `docs/OQL.md` (pushdown). The Helm chart
(`charts/protogemcouch`) surfaces most of these as `values.yaml` keys.

> **Secrets via files.** Every required connection secret also accepts a `<NAME>_FILE` form pointing at a
> file (Kubernetes Secret volume / Docker secret); when set, the value is read from that file instead of
> the environment, keeping secrets out of the process environment. Applies to `CB_CONNSTR`, `CB_USERNAME`,
> `CB_PASSWORD`, `CB_BUCKET`, `CB_SCOPE`, `CB_COLLECTION` (e.g. `CB_PASSWORD_FILE=/run/secrets/cb-pw`).

## Core — connection & identity (required)

| Variable | Default | Description |
|----------|---------|-------------|
| `CB_CONNSTR` | — (required) | Couchbase connection string (`couchbase://host` or `couchbases://host` for TLS). |
| `CB_USERNAME` | — (required) | Couchbase username (least-privilege user recommended). |
| `CB_PASSWORD` | — (required) | Couchbase password. |
| `CB_BUCKET` | — (required) | Bucket holding region data + internal metadata docs. |
| `CB_SCOPE` | — (required; typically `_default`) | Couchbase scope. |
| `CB_COLLECTION` | — (required; typically `_default`) | Couchbase collection. |
| `SHIM_PORT` | `40405` | Geode client listener port (the port real Geode clients connect to). |
| `HEALTH_PORT` | `8081` | Health / metrics HTTP(S) port (`/ready`, `/live`, `/metrics`). Must differ from `SHIM_PORT`. |
| `HEALTH_BIND_ADDRESS` | unset (all interfaces) | Bind address for the health server; set to restrict exposure (e.g. `127.0.0.1`). |

## Couchbase backend behavior

| Variable | Default | Description |
|----------|---------|-------------|
| `CB_KV_TIMEOUT_MS` | `5000` | Key-value operation timeout. |
| `CB_CONNECT_TIMEOUT_MS` | `10000` | Initial cluster connect timeout. |
| `CB_DURABILITY` | `none` | Write durability: `none` / `majority` / `majorityAndPersistToActive` / `persistToMajority` (applied to all value writes). |
| `CB_MAX_VALUE_BYTES` | `20971520` (20 MiB) | Reject an encoded value larger than this before any backend write (`0` disables). Couchbase's document ceiling is 20 MiB. |
| `CB_TTL_SECONDS` | `0` (off) | Default entry TTL (Couchbase document expiry) applied to value writes. |
| `CB_TTL_REGIONS` | unset | Per-region TTL overrides, e.g. `orders=3600,sessions=900`. |
| `CB_TTL_MODE` | `ttl` | `ttl` (expiry from last write) or `idle` (refresh expiry on reads via get-and-touch). |

## Couchbase backend TLS

| Variable | Default | Description |
|----------|---------|-------------|
| `CB_TLS_ENABLED` | auto (`true` for a `couchbases://` connstr, else `false`) | Force backend TLS on/off. |
| `CB_TLS_CERT_PATH` | unset | Path to the Couchbase cluster CA certificate. |
| `CB_TLS_VERIFY_HOSTNAME` | `true` | Set `false` to disable hostname verification (not recommended). |

## Inbound TLS / mutual TLS

Inbound TLS for the Geode listener is **enabled by the presence of `TLS_KEYSTORE_PATH`**. See
`docs/SECURITY.md` → transport security for rotation and CA-ordering guidance.

| Variable | Default | Description |
|----------|---------|-------------|
| `TLS_KEYSTORE_PATH` | unset (TLS off) | Server keystore path; setting it enables inbound TLS. |
| `TLS_KEYSTORE_PASSWORD` | unset | Keystore password. |
| `TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type (`PKCS12` / `JKS`). |
| `TLS_TRUSTSTORE_PATH` | unset | Truststore path (required for `TLS_CLIENT_AUTH=require`). |
| `TLS_TRUSTSTORE_PASSWORD` | unset | Truststore password. |
| `TLS_TRUSTSTORE_TYPE` | `PKCS12` | Truststore type. |
| `TLS_CLIENT_AUTH` | `none` | `require` to enforce **mutual TLS** (client-cert auth). |
| `TLS_PROTOCOLS` | `TLSv1.3,TLSv1.2` | Allowed protocols (legacy SSLv3/TLS 1.0/1.1 excluded; narrow further e.g. `TLSv1.3`). |
| `TLS_CIPHERS` | unset (JVM defaults for the protocols) | Optional explicit cipher allowlist (comma-separated). |
| `TLS_RELOAD_SECONDS` | `0` (off) | Hot-reload poll interval: rebuild + swap the listener `SslContext` for new connections on keystore change, no restart. |
| `HEALTH_TLS_ENABLED` | `false` | Serve the health endpoint over HTTPS (reuses the inbound keystore; requires `TLS_KEYSTORE_PATH`). |

## Connection limits & frame-decoder guards (DoS hardening)

| Variable | Default | Description |
|----------|---------|-------------|
| `MAX_CONNECTIONS` | `0` (unlimited) | Cap concurrent client connections; excess are rejected + audited. |
| `CONNECTION_IDLE_TIMEOUT_SECONDS` | `300` | Reap idle connections (`0` disables). Subscription feeds are exempt. |
| `FIRST_REQUEST_TIMEOUT_SECONDS` | `10` | Slowloris guard: deadline for the first request after connect (`0` disables). |
| `MAX_FRAME_BYTES` | `52428800` (50 MiB) | Reject an inbound frame larger than this (over-allocation guard). |
| `MAX_FRAME_PARTS` | `100000` | Reject a frame declaring more parts than this. |

## Handler thread pool & backpressure

| Variable | Default | Description |
|----------|---------|-------------|
| `HANDLER_THREADS` | `64` | Worker threads handling blocking backend work off the Netty event loop. |
| `HANDLER_MAX_PENDING_TASKS` | `256` | Per-thread bounded queue; once full the shim **sheds** excess requests (closes the connection) rather than growing the queue toward OOM (`0` = unbounded — not recommended). |

## Query (OQL)

| Variable | Default | Description |
|----------|---------|-------------|
| `OQL_PUSHDOWN` | `false` | Push eligible `WHERE` predicates to Couchbase via N1QL (a managed secondary index); the shim re-filters authoritatively so results are identical to a scan. See `docs/OQL.md`. |
| `PGC_QUERY_PAGE_SIZE` | `100` | Rows per chunk in the chunked query response (advanced tuning). |

## Keyset-metadata sharding

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYSET_SHARDS` | `1` | Split each region's keyset across N docs to lift the single-doc ~20 MiB key-count ceiling and shrink `REMOVE`/`PUT_ALL` rewrites. `1` is byte-identical to the legacy single-doc layout. Set at deploy time — changing it over existing data needs a keyset rebuild. |

## Durable subscriptions (multi-replica HA)

| Variable | Default | Description |
|----------|---------|-------------|
| `DURABLE_PERSISTENCE` | `false` | Persist durable clients' interests/CQs + queued events to Couchbase so they replay on reconnect to **any** replica. Off keeps in-memory single-instance behavior. |
| `DURABLE_MAX_QUEUE` | `100000` | Per-durable-client persisted queue bound (oldest dropped on overflow). |
| `DURABLE_SWEEP_SECONDS` | `60` | Interval of the cross-replica sweep that reclaims expired away durable clients. |
| `DURABLE_AWAY_REFRESH_MS` | `1000` | Refresh interval of a replica's away-durable-client cache (origin enqueue freshness). |

## PDX registry persistence

| Variable | Default | Description |
|----------|---------|-------------|
| `PDX_PERSISTENCE` | `false` | Allocate PDX type/enum ids from a cluster-wide durable Couchbase registry so ids are consistent across replicas and survive a restart (`GET_PDX_TYPE_BY_ID` / bulk discovery / field querying resolve any id on any replica). Off keeps the in-memory per-instance counter. |
| `MAX_PDX_TYPES` | `0` (unlimited) | Cap distinct PDX types in the registry; new-type registration past the cap is rejected (metric + audit). Already-registered types are still served. |
| `MAX_PDX_ENUMS` | `0` (unlimited) | Same cap for PDX enums. |

## Cross-replica eventing backplane

A backplane fans server-pushed events across replicas (so a `CacheListener` fires for a mutation on
another replica). Default is single-instance (no backplane). See `docs/SUBSCRIPTIONS.md`.

| Variable | Default | Description |
|----------|---------|-------------|
| `EVENT_BACKPLANE` | unset (`none`) | `mesh` (broker-less peer mesh) or `redis` (Redis pub/sub). |
| `MESH_PORT` | `40406` | Mesh listen port (when `EVENT_BACKPLANE=mesh`). |
| `MESH_PEER_DNS` | unset | Headless-Service DNS name for peer discovery (Kubernetes). |
| `MESH_PEERS` | unset | Static comma-separated `host:port` peer list (alternative to DNS). |
| `MESH_DISCOVERY_INTERVAL_SECONDS` | `10` | Peer re-discovery interval. |
| `REDIS_HOST` | `127.0.0.1` | Redis host (when `EVENT_BACKPLANE=redis`). |
| `REDIS_PORT` | `6379` | Redis port. |
| `EVENT_BACKPLANE_CHANNEL` | `protogemcouch-events` | Redis pub/sub channel name. |

## Protocol & compatibility

| Variable | Default | Description |
|----------|---------|-------------|
| `SUPPORTED_VERSION_ORDINALS` | `150` (Geode 1.15.x) | Comma-separated accepted client version ordinals; clients outside the set are cleanly refused. Widening to a lower minor needs operator wire-validation (see `docs/COMPATABILITY_MATRIX.md`). |
| `ERROR_RESPONSE_MODE` | `exception` | `exception` (return a Geode `EXCEPTION` frame — validated default) or `close` (close the connection on a backend error). |

## Observability — distributed tracing (OpenTelemetry)

Tracing is **off unless `OTEL_*` is configured** (standard OpenTelemetry SDK variables). See
`docs/OBSERVABILITY.md`. Metrics (`/metrics`) and structured logs are always on.

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_TRACES_EXPORTER` | unset (off) | Set (e.g. `otlp`) to enable trace export. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | OTLP collector endpoint. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | unset | OTLP traces-specific endpoint (overrides the general one). |
| `OTEL_SERVICE_NAME` | unset | Service name attached to spans. |
| `OTEL_SDK_DISABLED` | unset | Standard kill-switch (`true` disables the SDK). |

---

*Internal-tooling variables (e.g. `ROLE`, `VOL_HEX`) used only by `com.protogemcouch.tools` probes are not
operator configuration and are documented at their call sites.*
