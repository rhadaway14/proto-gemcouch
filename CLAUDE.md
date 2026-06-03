# Project memory — ProtoGemCouch

## Kubernetes / Helm testing (standing instruction)

All Kubernetes and Helm testing or deployments use the operator's local-network cluster — **do not
require a local cluster**:

- **Host:** `master` — single-node **kubeadm** cluster, Kubernetes **v1.30.11**, containerd runtime,
  API server at `https://10.0.0.103:6443`. `helm` (v3.17) and `kubectl` are installed on the server;
  `rob`'s kubeconfig is at `~/.kube/config`. `rob` has sudo.
- **User:** `rob`. **Credentials:** see `.claude/k8s-test-server.local` (gitignored).
- **Connect non-interactively** (no `sshpass` locally; use PuTTY tools, host key already cached):
  - `plink -ssh -batch -pw <pw> rob@master "<command>"`
  - `pscp -pw <pw> -r <localpath> rob@master:<remotepath>`
- **Run Helm from the server:** copy the chart over with `pscp` then `helm ...` on `master`
  (kubeconfig is already there), or run `helm --kube-*`/`kubectl` locally against the cluster.

### Cluster gotcha — yearly cert expiry
This kubeadm cluster's leaf certificates expire ~yearly. If the API server is down with
`6443: connection refused`, renew them:
```
sudo kubeadm certs renew all          # CAs are valid to 2035; only leaf certs expire
# restart control-plane static pods (move /etc/kubernetes/manifests/*.yaml out ~20s and back)
sudo cp -f /etc/kubernetes/admin.conf /home/rob/.kube/config && sudo chown rob:rob /home/rob/.kube/config
```
Certs last renewed 2026-06-03 (valid to ~2027-06).

### Running the shim itself on the cluster
The `protogemcouch` image is not in a registry, so a full in-cluster run requires: (1) getting the
image onto the cluster (`docker save` → `pscp` → `sudo ctr -n k8s.io images import`, or push to a
registry), and (2) a reachable Couchbase. The Helm chart objects themselves are validated on the
live cluster (`charts/protogemcouch`).

## Build / test quick reference

- Unit tests: `mvn -o test`
- Integration tests (Docker-backed, real Geode client): `mvn -o verify`
- App run / soak: `scripts/observability-up.sh`, `scripts/soak.sh`
