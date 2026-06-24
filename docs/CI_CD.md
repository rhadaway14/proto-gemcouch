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

CI automates building, testing, and the **release/publish** pipeline (it does not roll out to
environments itself). **Continuous deployment is GitOps-based**: Argo CD reconciles the cluster to the
declarative manifests under `gitops/` — staging auto-syncs, prod is a manual promotion gate — and CI
only builds/publishes the image the manifests reference. See [`gitops/README.md`](../gitops/README.md).
A non-GitOps install is still supported via the Helm chart / Docker Compose (`docs/DEPLOYMENT.md`).

---

## Current workflows

The repository currently includes these GitHub Actions workflows:

- `build-test.yml` — unit tests + build (per-PR / push).
- `integration.yml` — Docker-backed integration suite (real Geode client → shim → Couchbase).
- `perf-gate.yml` — query-weighted performance-regression gate.
- `docker-image.yml` — build, Trivy scan, SBOM/provenance, keyless cosign-sign, publish (default branch + `v*` tags).
- `dependency-scan.yml` — CodeQL static analysis + dependency metadata submission.
- `cross-version-matrix.yml` — real Geode 1.13/1.14/1.15 clients vs the shim (on demand, weekly, and `v*` tags).
- `release-candidate.yml` — the `v*`-tag release gate: full `mvn verify` + jar artifact (with the image scan/sign above).

These workflow files live in:

```text
.github/workflows/