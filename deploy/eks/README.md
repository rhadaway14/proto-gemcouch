# ProtoGemCouch capacity rig — EKS (eksctl + Helm)

The same capacity rig as `deploy/ec2/`, but on EKS — so the measured ceiling **includes the real
production deployment path**: the production Helm chart (`charts/protogemcouch`), the Service load
balancer, and the CNI/kube-proxy overhead you'll actually ship. Use this *after* the clean EC2 baseline
to quantify the k8s-path delta (see the recommendation in the project history).

```
[ loadgen Job pods ] → shim-protogemcouch (ClusterIP, kube-proxy)
                       └─ or → shim-nlb (NLB)              → [ shim pods × M, 1/node ] → [ Couchbase StatefulSet ]
kube-prometheus-stack: Prometheus + Grafana + node_exporter DaemonSet (host metrics on every node)
```

## What it reuses

- **The production Helm chart** for the shim tier — `helm upgrade --install shim ../../charts/protogemcouch`.
  Autoscaling and the PDB are turned off for a controlled test, and a one-pod-per-node anti-affinity
  ensures each shim replica owns a node (no co-location contention).
- **kube-prometheus-stack** for observability — it brings Prometheus, Grafana, and a node_exporter
  DaemonSet for free. The repo's host-metrics + Couchbase dashboards are imported as ConfigMaps.

## Prerequisites

`aws` (configured), `eksctl`, `kubectl`, `helm`.

## Launch

```bash
cd deploy/eks
./deploy.sh
```

This creates the cluster (`cluster.yaml`), installs monitoring, deploys a dedicated Couchbase, deploys
the shim via the chart, adds an external NLB + the shim/Couchbase ServiceMonitors, and imports the
dashboards. It prints the shim NLB and Grafana endpoints at the end.

## Run load & sweep

```bash
# in-cluster load (kube-proxy spreads across shim pods); raise parallelism / re-apply for a sweep
kubectl apply -n pgc -f manifests/loadgen-job.yaml
kubectl logs -n pgc -l job-name=loadgen -f          # PERF_RESULT lines

# stepped concurrency sweep: edit BENCH_CONCURRENCY in the Job and re-apply (delete the old Job first)
kubectl delete -n pgc job/loadgen ; kubectl apply -n pgc -f manifests/loadgen-job.yaml
```

Horizontal-scaling sweep — re-deploy the shim at 1 → 2 → 4 replicas (bump the nodegroup so each gets a
node), and compare peak ops/sec per replica count:

```bash
helm upgrade shim ../../charts/protogemcouch -n pgc -f shim-values.yaml --set replicaCount=4
eksctl scale nodegroup --cluster pgc-capacity --name rig --nodes 6
```

Watch Grafana → **ProtoGemCouch Host Metrics** + **ProtoGemCouch Couchbase** to attribute the knee.

## Tear down

```bash
./teardown.sh    # deletes LB Services first (releases the NLBs), then the cluster
```

## Scope & caveats (honest)

- **Single Couchbase node** (StatefulSet `replicas: 1`), same as the EC2 rig. A 3-node cluster +
  rebalance is more representative but not automated here.
- **Dev credentials** (`Administrator`/`password`) baked into the manifests/values — fine for an
  ephemeral rig; inject real secrets (`--set couchbase.password=…`, a managed Secret) otherwise.
- `manifests/couchbase.yaml` uses StorageClass `gp2`; adjust to your cluster's default SC if different.
- The external NLB uses the in-tree `aws-load-balancer-type: nlb` annotation (no AWS Load Balancer
  Controller needed). In-cluster load via the ClusterIP Service avoids the NLB entirely.
- EKS vs EC2: expect the EKS ceiling to be **at or below** the EC2 baseline — the delta is the
  CNI/kube-proxy/scheduling overhead of the production path, which is itself a useful sizing input.
