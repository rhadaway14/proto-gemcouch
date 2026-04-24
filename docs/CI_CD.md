# ProtoGemCouch CI/CD

## Purpose

This document explains the GitHub Actions workflows used by ProtoGemCouch for continuous integration and basic delivery readiness.

The current CI/CD setup is focused on:
- building the project
- running tests
- validating Docker image builds
- submitting dependency metadata
- running static security analysis

It is not yet a full automated deployment pipeline.

---

## Current workflows

The repository currently includes these GitHub Actions workflows:

- `build-test.yml`
- `docker-image.yml`
- `dependency-scan.yml`
- `release-candidate.yml` (if enabled)

These workflow files live in:

```text
.github/workflows/