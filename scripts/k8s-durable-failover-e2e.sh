#!/usr/bin/env bash
#
# End-to-end multi-replica DURABLE-SUBSCRIPTION FAILOVER validation on a real Kubernetes cluster
# (1.2.0-M1 Slice 4). Deploys Couchbase + a 2-replica shim with DURABLE_PERSISTENCE=true and NO
# eventing backplane (EVENT_BACKPLANE unset) — so durable delivery to an away client must come purely
# from the Couchbase-backed registry/queue (Slices 1-3), not from any cross-replica in-memory path.
#
# Flow (the durable client uses a stable durable id across reconnects):
#   1. subscribe (pinned to replica A): durable client registers interest, readyForEvents(), then closes
#      keeping its subscription -> A persists the away record to Couchbase.
#   2. KILL pod A (hard, --force) -> the replica the client was on is gone.
#   3. mutate (pinned to replica B): a plain client puts the key. As the origin, B reads the persisted
#      registry and enqueues the event for the away client.
#   4. verify (pinned to replica B): the durable client reconnects (same durable id) and readyForEvents()
#      must replay the event missed while away -> DURABLE_FAILOVER_CHECK PASS.
#
# Run on a host with kubectl + helm + a kubeconfig for the target cluster. Copy the chart + couchbase
# manifest alongside (pscp them over):
#   CHART_DIR (default ./protogemcouch), COUCHBASE_MANIFEST (default ./couchbase-e2e.yaml).
#
# Env: IMAGE_REPO/IMAGE_TAG (the image carrying tools.DurableFailoverCheck), NS (default pgc-e2e),
#      CB_PASSWORD (default "password"), KEEP=1 to skip teardown.
#
set -uo pipefail

NS="${NS:-pgc-e2e}"
CHART_DIR="${CHART_DIR:-./protogemcouch}"
COUCHBASE_MANIFEST="${COUCHBASE_MANIFEST:-./couchbase-e2e.yaml}"
IMAGE_REPO="${IMAGE_REPO:-docker.io/rhadaway14/protogemcouch}"
IMAGE_TAG="${IMAGE_TAG:?set IMAGE_TAG to an image containing tools.DurableFailoverCheck}"
CB_PASSWORD="${CB_PASSWORD:-password}"
RELEASE=pgc
DURABLE_ID="K8SFAILOVER1"
REGION="durFailover"
KEY="missed-after-kill"
VALUE="replayed-from-couchbase"

say() { echo; echo "=== $* ==="; }

# Gate: wait until a replica's away-registry actually shows an away durable client before mutating on it.
# The origin only enqueues for clients in its awayRegistryCache (refreshed from Couchbase on an interval
# via a REQUEST_PLUS query); a blind sleep raced that refresh on a freshly-indexed bucket and made this
# flaky. Mirror of the 1.2.0-M4 Slice 1 IT fix: poll protogemcouch_durable_away_registered>=1 on the
# replica that will be the origin. Best-effort (a real miss still surfaces at the verify step).
await_away_registered() {
  local ip="$1"
  kubectl run "awaitaway-$RANDOM" -n "$NS" --rm -i --restart=Never --image=curlimages/curl:8.10.1 \
    --command -- sh -c '
      for i in $(seq 1 60); do
        v=$(curl -s -m3 "http://'"$ip"':8081/metrics" | grep "^protogemcouch_durable_away_registered " | tr -s " " | cut -d" " -f2)
        case "$v" in
          ""|0|0.0|0.000000) sleep 2 ;;
          *) echo "away_registered=$v"; exit 0 ;;
        esac
      done
      echo "TIMEOUT waiting for away-registry"; exit 1' \
    || echo "WARN: away-registry gate did not confirm; proceeding (verify will catch a real miss)"
}
cleanup() {
  [ "${KEEP:-0}" = "1" ] && { echo "KEEP=1 — leaving $NS in place"; return; }
  say "Teardown"
  helm uninstall "$RELEASE" -n "$NS" >/dev/null 2>&1 || true
  kubectl delete pod dur-subscribe dur-mutate dur-verify -n "$NS" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete namespace "$NS" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "Couchbase"
kubectl apply -f "$COUCHBASE_MANIFEST"
kubectl rollout status statefulset/couchbase -n "$NS" --timeout=240s
kubectl exec -n "$NS" couchbase-0 -- couchbase-cli cluster-init -c 127.0.0.1:8091 \
  --cluster-username Administrator --cluster-password "$CB_PASSWORD" \
  --services data,index,query --cluster-ramsize 1024 --cluster-index-ramsize 512 >/dev/null 2>&1 || true
for _ in $(seq 1 20); do
  if kubectl exec -n "$NS" couchbase-0 -- couchbase-cli bucket-list -c 127.0.0.1:8091 \
       -u Administrator -p "$CB_PASSWORD" 2>/dev/null | grep -qw test; then break; fi
  kubectl exec -n "$NS" couchbase-0 -- couchbase-cli bucket-create -c 127.0.0.1:8091 \
    -u Administrator -p "$CB_PASSWORD" --bucket test --bucket-type couchbase --bucket-ramsize 512 --wait >/dev/null 2>&1 || true
  sleep 5
done

say "Shim (2 replicas, DURABLE_PERSISTENCE=true, no backplane)"
helm upgrade --install "$RELEASE" "$CHART_DIR" -n "$NS" \
  --set image.repository="$IMAGE_REPO" --set image.tag="$IMAGE_TAG" --set image.pullPolicy=Always \
  --set replicaCount=2 \
  --set couchbase.connectionString="couchbase://couchbase.$NS.svc" \
  --set couchbase.bucket=test --set couchbase.username=Administrator --set couchbase.password="$CB_PASSWORD" \
  --set podDisruptionBudget.enabled=false \
  --set extraEnv[0].name=DURABLE_PERSISTENCE --set-string extraEnv[0].value=true
kubectl rollout status deployment/"$RELEASE"-protogemcouch -n "$NS" --timeout=240s

say "Pod IPs / names"
mapfile -t ROWS < <(kubectl get pods -n "$NS" -l app.kubernetes.io/name=protogemcouch \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.podIP}{"\n"}{end}')
[ "${#ROWS[@]}" -ge 2 ] || { echo "need 2 shim pods, got ${#ROWS[@]}"; exit 1; }
A_NAME="$(echo "${ROWS[0]}" | awk '{print $1}')"; A_IP="$(echo "${ROWS[0]}" | awk '{print $2}')"
B_IP="$(echo "${ROWS[1]}" | awk '{print $2}')"
echo "replica A = $A_NAME ($A_IP);  replica B = $B_IP   (durable client starts on A, reconnects to B)"

say "1) subscribe (durable client on replica A)"
kubectl run dur-subscribe -n "$NS" --image="$IMAGE_REPO:$IMAGE_TAG" --image-pull-policy=Always --restart=Never \
  --env TARGET="$A_IP:40405" --env DURABLE_ID="$DURABLE_ID" --env REGION="$REGION" \
  --command -- java -cp /app/protogemcouch.jar com.protogemcouch.tools.DurableFailoverCheck subscribe
for _ in $(seq 1 60); do
  kubectl logs dur-subscribe -n "$NS" 2>/dev/null | grep -q DURABLE_SUBSCRIBED && break; sleep 2
done
kubectl logs dur-subscribe -n "$NS" 2>/dev/null | grep -q DURABLE_SUBSCRIBED \
  || { echo "subscribe never completed"; kubectl logs dur-subscribe -n "$NS" | tail -20; exit 1; }

say "2) KILL replica A ($A_NAME) — hard"
kubectl delete pod "$A_NAME" -n "$NS" --grace-period=0 --force >/dev/null 2>&1 || true
# Wait until replica B (the origin for the next mutation) actually sees the away client in its
# registry, rather than a blind sleep that could race B's REQUEST_PLUS refresh on a fresh bucket index.
await_away_registered "$B_IP"

say "3) mutate (plain client on replica B — origin enqueues for the away client)"
kubectl run dur-mutate -n "$NS" --image="$IMAGE_REPO:$IMAGE_TAG" --image-pull-policy=Always --restart=Never \
  --env TARGET="$B_IP:40405" --env REGION="$REGION" --env KEY="$KEY" --env VALUE="$VALUE" \
  --command -- java -cp /app/protogemcouch.jar com.protogemcouch.tools.DurableFailoverCheck mutate
for _ in $(seq 1 40); do
  kubectl logs dur-mutate -n "$NS" 2>/dev/null | grep -q DURABLE_MUTATED && break; sleep 2
done

say "4) verify (durable client reconnects to replica B and replays)"
kubectl run dur-verify -n "$NS" --image="$IMAGE_REPO:$IMAGE_TAG" --image-pull-policy=Always --restart=Never \
  --env TARGET="$B_IP:40405" --env DURABLE_ID="$DURABLE_ID" --env REGION="$REGION" \
  --env KEY="$KEY" --env VALUE="$VALUE" --env TIMEOUT_SECONDS=60 \
  --command -- java -cp /app/protogemcouch.jar com.protogemcouch.tools.DurableFailoverCheck verify

say "Verdict"
VERDICT=""
for _ in $(seq 1 60); do
  VERDICT="$(kubectl logs dur-verify -n "$NS" 2>/dev/null | grep -E 'DURABLE_FAILOVER_CHECK (PASS|FAIL)' | tail -1)"
  [ -n "$VERDICT" ] && break; sleep 2
done
echo "${VERDICT:-DURABLE_FAILOVER_CHECK FAIL (no verdict line)}"
echo "$VERDICT" | grep -q PASS
