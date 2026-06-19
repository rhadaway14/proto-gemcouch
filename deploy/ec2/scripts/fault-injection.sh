#!/usr/bin/env bash
#
# Inject realistic backend faults on the Couchbase host while the load generators drive sustained load
# against the shim NLB. This covers the failure modes the in-tree chaos integration test does NOT
# (it only does a hard container stop/start, single-box): backend LATENCY, packet LOSS, a PARTIAL
# outage (frozen/unresponsive backend), and a network PARTITION of the data path — all WHILE the shim
# is under real load on the rig.
#
# Run this ON THE COUCHBASE HOST (it manipulates that host's docker container, tc qdisc, and iptables).
# Drive load from the load-gen host(s) in parallel (see "Operator workflow" below).
#
# Every fault is applied for a bounded window and then healed; an EXIT trap guarantees the host is
# restored (qdisc cleared, iptables rules removed, container unpaused/started) even on Ctrl-C or error.
#
# Subcommands:
#   latency  [MS]   [SECONDS]   add MS ms egress delay to the backend NIC      (default 200ms / 120s)
#   loss     [PCT]  [SECONDS]   drop PCT% of egress packets on the backend NIC (default 5%   / 120s)
#   partial  [SECONDS]         freeze the backend (docker pause) — connections hang, no RST (default 60s)
#   partition[SECONDS]         drop the Couchbase KV ports (11210/11207) — data path cut    (default 90s)
#   outage   [SECONDS]         hard stop the backend container, then start it               (default 60s)
#   scenario                   run all of the above in sequence with healthy windows between
#   heal                       remove every fault this script can apply (idempotent panic button)
#
# Env:
#   CB_CONTAINER   Couchbase container name (default: couchbase)
#   NIC            network interface to shape (default: the default-route device)
#   HEAL_SECONDS   healthy window between steps in `scenario` (default 60)
#
set -uo pipefail

CB_CONTAINER="${CB_CONTAINER:-couchbase}"
NIC="${NIC:-$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')}"
HEAL_SECONDS="${HEAL_SECONDS:-60}"
KV_PORTS="11210,11207"   # Couchbase KV (plain + TLS) — the data path the shim uses; mgmt/metrics stay up

ts()  { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log() { echo "[$(ts)] $*"; }

ensure_tools() {
  # AL2023: tc lives in iproute-tc; iptables maps to the nft backend. Install on first use.
  command -v tc       >/dev/null 2>&1 || sudo dnf install -y iproute-tc >/dev/null 2>&1 || true
  command -v iptables >/dev/null 2>&1 || sudo dnf install -y iptables    >/dev/null 2>&1 || true
}

# ---- heal: undo everything, unconditionally ----
heal() {
  [ -n "${NIC:-}" ] && sudo tc qdisc del dev "$NIC" root 2>/dev/null || true
  # Remove our partition rules (loop in case a window was stacked).
  while sudo iptables -D INPUT  -p tcp -m multiport --dports "$KV_PORTS" -j DROP 2>/dev/null; do :; done
  while sudo iptables -D OUTPUT -p tcp -m multiport --sports "$KV_PORTS" -j DROP 2>/dev/null; do :; done
  sudo docker unpause "$CB_CONTAINER" 2>/dev/null || true
  sudo docker start   "$CB_CONTAINER" 2>/dev/null || true
}
trap heal EXIT

require_nic() {
  if [ -z "${NIC:-}" ]; then
    echo "Could not determine NIC; set NIC=<iface> (see: ip route show default)" >&2
    exit 1
  fi
}

netem() {                                   # netem <delay|loss> <value>
  require_nic; ensure_tools
  sudo tc qdisc replace dev "$NIC" root netem "$1" "$2"
}
clear_netem() { sudo tc qdisc del dev "$NIC" root 2>/dev/null || true; }

fault_latency() {
  local ms="${1:-200}" secs="${2:-120}"
  log "LATENCY +${ms}ms on ${NIC} for ${secs}s — expect p99 to climb, errors to stay ~0 (shim should ride it out)"
  netem delay "${ms}ms"
  sleep "$secs"
  clear_netem
  log "LATENCY healed"
}

fault_loss() {
  local pct="${1:-5}" secs="${2:-120}"
  log "LOSS ${pct}% on ${NIC} for ${secs}s — expect a small bounded error/retry rate, no hang, recovery on heal"
  netem loss "${pct}%"
  sleep "$secs"
  clear_netem
  log "LOSS healed"
}

fault_partial() {
  local secs="${1:-60}"
  log "PARTIAL outage (docker pause ${CB_CONTAINER}) for ${secs}s — backend frozen, in-flight ops must fail BOUNDED (no infinite hang)"
  sudo docker pause "$CB_CONTAINER"
  sleep "$secs"
  sudo docker unpause "$CB_CONTAINER"
  log "PARTIAL healed (unpaused) — shim should recover without restart"
}

fault_partition() {
  local secs="${1:-90}"; ensure_tools
  log "PARTITION KV ports ${KV_PORTS} for ${secs}s — data path cut while mgmt/metrics stay up (node_exporter + CB prometheus keep scraping)"
  sudo iptables -I INPUT  -p tcp -m multiport --dports "$KV_PORTS" -j DROP
  sudo iptables -I OUTPUT -p tcp -m multiport --sports "$KV_PORTS" -j DROP
  sleep "$secs"
  sudo iptables -D INPUT  -p tcp -m multiport --dports "$KV_PORTS" -j DROP 2>/dev/null || true
  sudo iptables -D OUTPUT -p tcp -m multiport --sports "$KV_PORTS" -j DROP 2>/dev/null || true
  log "PARTITION healed — shim should reconnect and resume"
}

fault_outage() {
  local secs="${1:-60}"
  log "HARD outage (docker stop ${CB_CONTAINER}) for ${secs}s — connections RST, ops fail fast/clean"
  sudo docker stop "$CB_CONTAINER"
  sleep "$secs"
  sudo docker start "$CB_CONTAINER"
  log "HARD outage healed (started) — wait for Couchbase to warm up; shim recovers on its own"
}

scenario() {
  log "=== fault-injection scenario start (NIC=${NIC}, container=${CB_CONTAINER}, heal window=${HEAL_SECONDS}s) ==="
  log "Keep sustained load running on the load-gen host(s) for the whole scenario; watch Grafana Host+Couchbase dashboards."
  fault_latency 200 120;  log "--- healthy ${HEAL_SECONDS}s ---"; sleep "$HEAL_SECONDS"
  fault_loss      5 120;  log "--- healthy ${HEAL_SECONDS}s ---"; sleep "$HEAL_SECONDS"
  fault_partial    60;    log "--- healthy ${HEAL_SECONDS}s ---"; sleep "$HEAL_SECONDS"
  fault_partition  90;    log "--- healthy ${HEAL_SECONDS}s ---"; sleep "$HEAL_SECONDS"
  fault_outage     60;    log "--- healthy ${HEAL_SECONDS}s ---"; sleep "$HEAL_SECONDS"
  log "=== scenario complete; backend fully healed. Verdict = read the load-gen PERF_RESULT errors + the Grafana throughput/latency recovery after each window. ==="
}

cmd="${1:-}"; shift || true
case "$cmd" in
  latency)   fault_latency   "${1:-200}" "${2:-120}" ;;
  loss)      fault_loss      "${1:-5}"   "${2:-120}" ;;
  partial)   fault_partial   "${1:-60}" ;;
  partition) fault_partition "${1:-90}" ;;
  outage)    fault_outage    "${1:-60}" ;;
  scenario)  scenario ;;
  heal)      heal; trap - EXIT; log "healed (all faults removed)" ;;
  *) grep '^#' "$0" | grep -v '#!/usr/bin/env' | sed 's/^# \{0,1\}//'; exit 0 ;;
esac
