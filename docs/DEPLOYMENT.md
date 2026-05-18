# ProtoGemCouch Deployment

## Purpose

This document explains how to build and run ProtoGemCouch as a Docker container for `RawShimServer`.

ProtoGemCouch runs a Geode-compatible shim port and a separate health/admin port.

---

## Prerequisites

You need:

- Docker installed
- Maven installed for local builds
- Java compatible with the project build
- a reachable Couchbase cluster, or the included Docker Compose Couchbase service
- valid Couchbase credentials
- target bucket, scope, and collection already created, or automated initialization enabled through Docker Compose

---

## Runtime configuration

ProtoGemCouch uses environment variables at startup.

Required:

- `CB_CONNSTR`
- `CB_USERNAME`
- `CB_PASSWORD`
- `CB_BUCKET`
- `CB_SCOPE`
- `CB_COLLECTION`

Optional:

- `SHIM_PORT`  
  Default: `40405`
- `HEALTH_PORT`  
  Default: `8081`

`HEALTH_PORT` must be different from `SHIM_PORT`.

---

## Example `.env`

For local Docker Compose:

```env
CB_CONNSTR=couchbase://couchbase
CB_USERNAME=Administrator
CB_PASSWORD=password
CB_BUCKET=test
CB_SCOPE=_default
CB_COLLECTION=_default
SHIM_PORT=40405
HEALTH_PORT=8081
```

For an external Couchbase cluster:

```env
CB_CONNSTR=couchbase://your-couchbase-host
CB_USERNAME=your-user
CB_PASSWORD=your-password
CB_BUCKET=your-bucket
CB_SCOPE=your-scope
CB_COLLECTION=your-collection
SHIM_PORT=40405
HEALTH_PORT=8081
```

Do not commit real credentials to source control.

---

## Build the jar locally

```bash
mvn clean package
```

The Dockerfile expects the packaged jar at:

```text
target/protogemcouch.jar
```

---

## Build the Docker image

```bash
docker build -t protogemcouch:local .
```

---

## Run with Docker

```bash
docker run --rm \
  --name protogemcouch-shim \
  --env-file .env \
  -p 40405:40405 \
  -p 8081:8081 \
  protogemcouch:local
```

Expected exposed ports:

```text
40405 -> Geode client shim protocol port
8081  -> health/admin HTTP port
```

---

## Run with Docker Compose

For local development and integration testing, Docker Compose can start:

```text
Couchbase
Couchbase initialization container
ProtoGemCouch shim
```

Run:

```bash
docker compose up -d --build
```

Check container status:

```bash
docker ps
```

Follow shim logs:

```bash
docker logs -f protogemcouch-shim
```

Stop and remove local state:

```bash
docker compose down -v
```

---

## Health checks

The health/admin port is separate from the Geode shim protocol port.

Readiness:

```bash
curl -fs http://127.0.0.1:8081/ready
```

If `/live` is enabled in the current health server implementation:

```bash
curl -fs http://127.0.0.1:8081/live
```

Expected behavior:

```text
/ready returns success when the shim has started and required dependencies/configuration are usable.
/live returns success when the process is alive.
```

---

## Client connection

A Geode Java client should connect to the shim host and shim port.

Example:

```java
ClientCache cache = new ClientCacheFactory()
        .addPoolServer("127.0.0.1", 40405)
        .setPoolSubscriptionEnabled(false)
        .set("log-level", "warn")
        .create();

Region<String, Object> region = cache
        .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
        .create("helloWorld");
```

For PDX read-serialized validation:

```java
ClientCache cache = new ClientCacheFactory()
        .addPoolServer("127.0.0.1", 40405)
        .setPoolSubscriptionEnabled(false)
        .setPdxReadSerialized(true)
        .set("log-level", "warn")
        .create();
```

---

## Smoke test

After startup, run the current test suite:

```bash
mvn clean verify
```

Latest expected integration result:

```text
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

For fast response-writer coverage only:

```bash
mvn -Dtest=GemResponseWriterTest test
```

---

## Current validated behavior

The current deployment has been validated for:

```text
put / get
putAll / getAll
remove
containsKey / containsKeyOnServer
containsValueForKey
sizeOnServer
keySetOnServer
PDX / PdxInstance round-tripping
large key-set and bulk operation boundary handling
```

Large collection boundary coverage includes:

```text
>127 keys / entries
>252 keys / entries
```

---

## Operational notes

Current observability:

```text
structured logs
startup validation logs
handler-level operation logs
health/readiness endpoint
```

Planned observability additions:

```text
operation counters
success/error counters
latency tracking
response byte-size tracking
/metrics/json endpoint
Prometheus-format /metrics endpoint
```

---

## Rollback guidance

For local Docker Compose deployments:

```bash
docker compose down -v
git checkout <known-good-commit>
mvn clean package
docker compose up -d --build
```

For container-image deployments:

```text
redeploy the previous known-good image tag
verify /ready
run a small Geode client smoke test
monitor shim logs for protocol or serialization errors
```

---

## Security notes

- Do not commit `.env` files containing real credentials.
- Use environment variables or secret management for credentials.
- Restrict network access to the shim protocol port.
- Use a private network path between the shim and Couchbase.
- Add TLS / transport hardening before broader deployment.
- Define shim-side client authentication before broader deployment.
