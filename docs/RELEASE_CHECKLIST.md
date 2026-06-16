# ProtoGemCouch Release Checklist

## Purpose

This checklist is used before cutting a release tag, sharing a build, or declaring a deployment candidate ready for scoped use.

---

## 1. Source and build

- [ ] Working tree is clean
- [ ] Branch is correct
- [ ] `mvn clean test` passes
- [ ] `mvn verify` passes
- [ ] `mvn clean package` produces the runnable jar
- [ ] Docker image builds successfully
- [ ] GitHub Actions build/test workflow passes
- [ ] GitHub Actions Docker build workflow passes
- [ ] `CHANGELOG.md` has a dated entry for this version (no stale "not supported" claims)
- [ ] `docs/COMPATABILITY_MATRIX.md` contract reflects this version's supported surface + non-goals
- [ ] Version chosen per semver (pre-1.0: minor bump for new parity, patch for fixes)

> The `v*` tag triggers the release gate: `release-candidate.yml` (full `mvn verify` integration suite),
> `docker-image.yml` (Trivy scan + SBOM + cosign signature), and `perf-gate.yml` (the automated
> perf-regression gate, `scripts/perf-gate.sh`) — all before publishing.

Commands:

```bash
mvn clean test
mvn verify
mvn clean package
docker build -t protogemcouch:release-candidate .