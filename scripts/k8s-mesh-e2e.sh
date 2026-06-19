#!/usr/bin/env bash
#
# End-to-end cross-replica eventing validation on a real Kubernetes cluster, using the self-contained
# peer-mesh backplane (no external broker). Deploys Couchbase + a 2-replica shim (EVENT_BACKPLANE=mesh),
# then runs two pods pinned to two *different* shim replicas by pod IP:
#   - subscriber (replica A): registers interest + a CacheListener
#   - mutator    (replica B): puts a key
# If the mesh works, the mutation on B reaches the subscriber's feed on A and the check prints
# CROSS_REPLICA_EVENT_CHECK PASS — proving subscriptions/CQ now span replicas.
#
# Run on a host with kubectl + helm + a kubeconfig for the target cluster (e.g. the test cluster's
# master node). Requires the chart + couchbase manifest alongside (pscp them over):
#   CHART_DIR (default ./protogemcouch), COUCHBASE_MANIFEST (default ./couchbase-e2e.yaml).
#
# Env: IMAGE_REPO/IMAGE_TAG (the image carrying tools.CrossReplicaEventCheck), NS (default pgc-e2e),
#      CB_PASSWORD (default "password"), KEEP=1 to skip teardown.
#
set -uo pipefail

NS="${NS:-pgc-e2e}"
CHART_DIR="${CHART_DIR:-./protogemcouch}"
COUCHBASE_MANIFEST="${COUCHBASE_MANIFEST:-./couchbase-e2e.yaml}"
IMAGE_REPO="${IMAGE_REPO:-docker.io/rhadaway14/protogemcouch}"
IMAGE_TAG="${IMAGE_TAG:?set IMAGE_TAG to an image containing tools.CrossReplicaEventCheck}"
CB_PASSWORD="${CB_PASSWORD:-password}"
RELEASE=pgc

say() { echo; echo "=== $* ==="; }
cleanup() {
  [ "${KEEP:-0}" = "1" ] && { echo "KEEP=1 — leaving $NS in place"; return; }
  say "Teardown"
  helm uninstall "$RELEASE" -n "$NS" >/dev/null 2>&1 || true
  kubectl delete pod xr-subscriber xr-mutator -n "$NS" --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete namespace "$NS" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

say "Couchbase"
kubectl apply -f "$COUCHBASE_MANIFEST"
kubectl rollout status statefulset/couchbase -n "$NS" --timeout=240s
# Initialize the single-node cluster + create the bucket (idempotent-ish).
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

say "Shim (2 replicas, EVENT_BACKPLANE=mesh)"
helm upgrade --install "$RELEASE" "$CHART_DIR" -n "$NS" \
  --set image.repository="$IMAGE_REPO" --set image.tag="$IMAGE_TAG" --set image.pullPolicy=Always \
  --set replicaCount=2 --set eventing.backplane=mesh \
  --set couchbase.connectionString="couchbase://couchbase.$NS.svc" \
  --set couchbase.bucket=test --set couchbase.username=Administrator --set couchbase.password="$CB_PASSWORD" \
  --set podDisruptionBudget.enabled=false
kubectl rollout status deployment/"$RELEASE"-protogemcouch -n "$NS" --timeout=240s

say "Pod IPs"
mapfile -t PODS < <(kubectl get pods -n "$NS" -l app.kubernetes.io/name=protogemcouch -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}')
[ "${#PODS[@]}" -ge 2 ] || { echo "need 2 shim pods, got ${#PODS[@]}"; exit 1; }
A="${PODS[0]}"; B="${PODS[1]}"
echo "subscriber -> replica A ($A:40405);  mutator -> replica B ($B:40405)"

say "Subscriber pod (replica A)"
kubectl run xr-subscriber -n "$NS" --image="$IMAGE_REPO:$IMAGE_TAG" --image-pull-policy=Always --restart=Never \
  --env ROLE=subscriber --env TARGET="$A:40405" --env TIMEOUT_SECONDS=120 \
  --command -- java -cp /app/protogemcouch.jar com.protogemcouch.tools.CrossReplicaEventCheck subscriber
# Wait for the subscriber to be registered before mutating.
for _ in $(seq 1 60); do
  kubectl logs xr-subscriber -n "$NS" 2>/dev/null | grep -q SUBSCRIBER_READY && break; sleep 2
done
kubectl logs xr-subscriber -n "$NS" 2>/dev/null | grep -q SUBSCRIBER_READY \
  || { echo "subscriber never became ready"; kubectl logs xr-subscriber -n "$NS" | tail -20; exit 1; }

say "Mutator pod (replica B)"
kubectl run xr-mutator -n "$NS" --image="$IMAGE_REPO:$IMAGE_TAG" --image-pull-policy=Always --restart=Never \
  --env ROLE=mutator --env TARGET="$B:40405" --env MUTATE_SECONDS=90 \
  --command -- java -cp /app/protogemcouch.jar com.protogemcouch.tools.CrossReplicaEventCheck mutator

say "Verdict"
VERDICT=""
for _ in $(seq 1 70); do
  VERDICT="$(kubectl logs xr-subscriber -n "$NS" 2>/dev/null | grep -E 'CROSS_REPLICA_EVENT_CHECK (PASS|FAIL)' | tail -1)"
  [ -n "$VERDICT" ] && break; sleep 2
done
echo "${VERDICT:-CROSS_REPLICA_EVENT_CHECK FAIL (no verdict line)}"
echo "$VERDICT" | grep -q PASS
