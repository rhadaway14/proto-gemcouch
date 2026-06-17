#!/usr/bin/env bash
#
# Tear down the EKS capacity rig. Deletes the LoadBalancer Services first (so their AWS NLBs/ELBs are
# released before the cluster goes away and don't linger), then the workloads, then the cluster.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CLUSTER="${CLUSTER:-pgc-capacity}"
REGION="${REGION:-us-east-1}"
NS="${NS:-pgc}"
MON_NS="${MON_NS:-monitoring}"

# Release cloud load balancers first.
kubectl delete -n "$NS" -f manifests/shim-nlb-service.yaml --ignore-not-found
kubectl delete -n "$MON_NS" svc monitoring-grafana --ignore-not-found

# Workloads.
kubectl delete -n "$NS" -f manifests/loadgen-job.yaml --ignore-not-found
helm uninstall shim -n "$NS" || true
kubectl delete -n "$NS" -f manifests/couchbase.yaml --ignore-not-found
helm uninstall monitoring -n "$MON_NS" || true

# Cluster (also deletes the nodegroup, VPC, etc. eksctl created).
eksctl delete cluster --name "$CLUSTER" --region "$REGION" --wait
