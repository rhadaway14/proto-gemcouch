# ProtoGemCouch Runbook

## Purpose

This runbook explains how to start, stop, validate, troubleshoot, and operate ProtoGemCouch.

ProtoGemCouch is a GemFire/Geode protocol shim that accepts a subset of client operations and translates them into Couchbase operations.

---

## 1. Preconditions

Before starting ProtoGemCouch, confirm:

- Java is installed
- Maven is installed if running from source
- Couchbase is reachable
- the target bucket, scope, and collection exist
- required environment variables are set
- the shim port is open and available

---

## 2. Required configuration

Expected configuration values:

- `CB_CONNSTR`
- `CB_USERNAME`
- `CB_PASSWORD`
- `CB_BUCKET`
- `CB_SCOPE`
- `CB_COLLECTION`
- `SHIM_PORT`

Example values:

```bash
export CB_CONNSTR=couchbase://127.0.0.1
export CB_USERNAME=Administrator
export CB_PASSWORD=password
export CB_BUCKET=test
export CB_SCOPE=_default
export CB_COLLECTION=_default
export SHIM_PORT=40405