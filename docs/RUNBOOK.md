
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