#!/usr/bin/env bash
#
# Cross-version client matrix runner. Exercises the shim against a chosen Geode CLIENT protocol
# version while the shim itself stays at its built version, to validate wire interoperability across
# Geode releases. The shim does NOT do versioned serialization (it emits the wire forms validated for
# the Geode 1.15.x line, ordinal 150), so this is how an operator confirms a different client version
# is wire-compatible before widening SUPPORTED_VERSION_ORDINALS in production.
#
# Geode client protocol version -> ordinal (from KnownVersion):
#   8.1=35  9.0=45  1.1.0=50 ... 1.11.0=110  1.12.0=115  1.13.0=120  1.14.0=125  1.15.0=150 (CURRENT)
# Patch releases within a minor (e.g. all of 1.15.x) share their minor's ordinal, so cross-PATCH
# interop is guaranteed by the protocol and validated implicitly by the whole 1.15.x integration suite.
#
# Assumes the stack is already up (e.g. `mvn -o clean package -DskipTests && docker compose up -d --build`).
#
# Usage:
#   ./scripts/cross-version-matrix.sh 1.15.4            # run the core suite with a 1.15.4 client
#   CLIENT_TESTS='ProtoGemCouchCrudIntegrationTest' ./scripts/cross-version-matrix.sh 1.15.4
#
# OFFLINE CAVEAT: `mvn -o` can only resolve a client version whose FULL transitive dependency tree is
# already in ~/.m2 — otherwise it fails before any test runs. CI (with network) can run the full
# matrix; a stock offline checkout typically only has the build's pinned client version cached.
#
# NOTE: this script recompiles the whole project (incl. the shim's MAIN code) against the client version
# — fine for PATCH interop (same minor) but fragile across MINORS, since the shim uses Geode *internal*
# APIs that can differ. The authoritative MINOR-version matrix (real 1.13/1.14/1.15 clients) is the
# standalone `cross-version-client/` harness (public client API only, no shim recompile), driven by
# `.github/workflows/cross-version-matrix.yml`.
set -euo pipefail

CLIENT_VERSION="${1:-}"
if [[ -z "${CLIENT_VERSION}" ]]; then
  echo "usage: $0 <geode-client-version>   e.g. $0 1.15.4" >&2
  echo "cached client versions:" >&2
  ls "${HOME}/.m2/repository/org/apache/geode/geode-core/" 2>/dev/null | grep -E '^[0-9]' | sed 's/^/  /' >&2 || true
  exit 2
fi

# The negotiation refusal path needs no alternate client jar (it replays a real handshake with the
# ordinal swapped), so it always runs; the rest is the representative core surface.
CLIENT_TESTS="${CLIENT_TESTS:-ProtoGemCouchVersionNegotiationIntegrationTest,ProtoGemCouchCrudIntegrationTest,ProtoGemCouchQueryIntegrationTest,ProtoGemCouchAtomicOpsIntegrationTest}"

echo "=== cross-version matrix: client geode.version=${CLIENT_VERSION} -> running shim ==="
echo "    tests: ${CLIENT_TESTS}"

mvn -o -Dgeode.version="${CLIENT_VERSION}" clean test-compile failsafe:integration-test \
    -Dit.test="${CLIENT_TESTS}"

echo "=== cross-version matrix PASSED for client ${CLIENT_VERSION} ==="
