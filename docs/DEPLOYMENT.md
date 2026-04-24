# ProtoGemCouch Deployment

## Purpose

This document explains how to build and run ProtoGemCouch as a Docker container for the `RawShimServer`.

---

## Prerequisites

You need:

- Docker installed
- a reachable Couchbase cluster
- valid Couchbase credentials
- bucket, scope, and collection already created

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

---

## Build the jar locally

```bash
mvn clean package