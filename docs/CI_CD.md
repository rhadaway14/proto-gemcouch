# ProtoGemCouch CI/CD

## Purpose

This document explains the GitHub Actions workflows used by ProtoGemCouch for continuous integration and basic delivery readiness.

The current CI/CD setup covers:
- building the project and running unit + Docker-backed integration tests
- building, vulnerability-scanning (Trivy), SBOM/provenance-attesting, and **keyless cosign-signing**
  the Docker image, and publishing it on default-branch pushes and `v*` tags
- a signed release gate on `v*` tags: full `mvn verify`, the perf-regression gate, and the image
  scan/sign/publish (see `docs/RELEASE_CHECKLIST.md`)
- static security analysis (CodeQL) and dependency metadata submission

It automates continuous integration and the **release/publish** pipeline. It does **not** perform
continuous *deployment* (automatic rollout to running environments) — deployment is operator-driven via
the Helm chart / Docker Compose.

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