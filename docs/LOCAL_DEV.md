

# ProtoGemCouch Local Development

## Purpose

This document explains the fastest way to run ProtoGemCouch locally for development and testing.

---

## Recommended local workflow

The recommended local workflow is:

- run Couchbase and ProtoGemCouch with Docker Compose
- let Compose initialize Couchbase automatically
- use the Java probes, integration tests, or benchmark harness against the shim

---

## Prerequisites

You need:

- Docker / Docker Compose
- Java 17+
- Maven
- the project checked out locally

---

## Project files used

Local dev uses:

- `docker-compose.yml`
- `Dockerfile`
- `.env`
- `scripts/init-couchbase.sh`

---

## Start the stack

```bash
docker compose up --build