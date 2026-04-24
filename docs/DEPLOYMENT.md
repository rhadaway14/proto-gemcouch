# ProtoGemCouch Deployment

## Purpose

This document explains how to build and run ProtoGemCouch as a Docker container for `RawShimServer`.

---

## Prerequisites

You need:

- Docker installed
- a reachable Couchbase cluster
- valid Couchbase credentials
- bucket, scope, and collection already created, or automated initialization enabled through Docker Compose

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

## Build the jar locally

```bash
mvn clean package