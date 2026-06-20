#!/usr/bin/env bash
#
# Full-surface soak: exercises the WHOLE request surface together — CRUD, bulk, metadata, OQL queries,
# transactions, the in-transaction getEntry, and PDX writes (profile=full-surface) — AND drives the
# eventing subsystem in parallel with an interest + CQ consumer (BENCH_SUBSCRIPTIONS=true), so every
# write flows back as a server-pushed event and a CQ event. It then renders the same STABILITY VERDICT
# as scripts/soak.sh (no error growth, no leak, no degradation) plus the subscription event totals.
#
# Assumes the stack is already up (mvn -o clean package -DskipTests && docker compose up -d --build).
#
# Usage:
#   ./scripts/full-surface-soak.sh
#   ./scripts/full-surface-soak.sh --duration 600 --concurrency 16 --sample-interval 30
#
# This is a thin wrapper over scripts/soak.sh — it just pins the full-surface profile and turns the
# subscription/CQ consumer on; all thresholds (SOAK_MAX_ERRORS, SOAK_MAX_MEM_GROWTH_PCT, …) and flags
# pass through unchanged.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default to a longer run than the CRUD smoke; override with --duration.
HAS_DURATION=false
for a in "$@"; do [[ "$a" == "--duration" ]] && HAS_DURATION=true; done

EXTRA_ARGS=(--profile full-surface)
if [[ "$HAS_DURATION" == "false" ]]; then
    EXTRA_ARGS+=(--duration 600)
fi

echo "=== Full-surface soak (profile=full-surface, subscriptions=on) ==="
BENCH_SUBSCRIPTIONS=true exec "${SCRIPT_DIR}/soak.sh" "${EXTRA_ARGS[@]}" "$@"
