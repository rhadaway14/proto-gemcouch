#!/usr/bin/env bash
#
# Stand up the EKS capacity rig: cluster -> monitoring (Prometheus + Grafana + node_exporter) ->
# dedicated Couchbase -> the shim (via the production Helm chart) -> external NLB + scrape hooks ->
# Grafana dashboards. Idempotent-ish: re-running upgrades in place.
#
# Prereqs: aws CLI (configured), eksctl, kubectl, helm. Run from deploy/eks/.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CLUSTER="${CLUSTER:-pgc-capacity}"
REGION="${REGION:-us-east-1}"
NS="${NS:-pgc}"
MON_NS="${MON_NS:-monitoring}"
CHART="${CHART:-../../charts/protogemcouch}"
DASHBOARDS="${DASHBOARDS:-../../grafana/dashboards}"

echo "==> EKS cluster ($CLUSTER)"
if ! eksctl get cluster --name "$CLUSTER" --region "$REGION" >/dev/null 2>&1; then
  eksctl create cluster -f cluster.yaml
fi
aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

echo "==> Monitoring (kube-prometheus-stack: Prometheus + Grafana + node_exporter DaemonSet)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
helm repo update >/dev/null
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  --namespace "$MON_NS" --create-namespace \
  --set grafana.service.type=LoadBalancer \
  --set grafana.adminPassword=admin

echo "==> Couchbase (dedicated, single node)"
kubectl apply -n "$NS" -f manifests/couchbase.yaml
kubectl rollout status -n "$NS" statefulset/couchbase --timeout=360s
kubectl wait -n "$NS" --for=condition=complete job/couchbase-init --timeout=360s

echo "==> Shim (production Helm chart)"
helm upgrade --install shim "$CHART" --namespace "$NS" -f shim-values.yaml
kubectl rollout status -n "$NS" deployment/shim-protogemcouch --timeout=300s

echo "==> External NLB + scrape hooks"
kubectl apply -n "$NS" -f manifests/shim-nlb-service.yaml
kubectl apply -f manifests/monitoring.yaml

echo "==> Import the repo Grafana dashboards (host metrics, Couchbase, observability)"
for d in "$DASHBOARDS"/*.json; do
  name="dash-$(basename "$d" .json)"
  kubectl create configmap "$name" -n "$MON_NS" --from-file="$(basename "$d")=$d" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl label configmap "$name" -n "$MON_NS" grafana_dashboard=1 --overwrite
done

echo
echo "==> Endpoints (LoadBalancers may take a minute to get an address):"
echo "    shim NLB   : kubectl get -n $NS svc shim-nlb -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'"
echo "    Grafana    : kubectl get -n $MON_NS svc monitoring-grafana -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'  (admin/admin)"
echo
echo "==> Run load (in-cluster, kube-proxy spreads across shim pods):"
echo "    kubectl apply -n $NS -f manifests/loadgen-job.yaml ; kubectl logs -n $NS -l job-name=loadgen -f"
echo "==> Scale the shim tier for a horizontal-scaling sweep:"
echo "    helm upgrade shim $CHART -n $NS -f shim-values.yaml --set replicaCount=4   # also bump the nodegroup"
