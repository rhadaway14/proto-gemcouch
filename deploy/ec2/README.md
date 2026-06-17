# ProtoGemCouch capacity rig — EC2 + NLB (Terraform)

Spins up a **dedicated, multi-host** rig on AWS to measure the shim's capacity ceiling and horizontal
scaling efficiency — the numbers the single-box soak can't produce (see `docs/SOAK_RESULTS.md`).

```
[ load-gen EC2 × N ]  →  [ Network LB ]  →  [ shim EC2 × M ]  →  [ Couchbase EC2 ]
  ConcurrentBenchmarkRunner   :40405 VIP      stateless, 1/host    dedicated, host-net
                                   │
                          [ observability EC2 ]  Prometheus + Grafana
                          scrapes every host's node_exporter + shim + Couchbase
```

Everything is in **one AZ** (low, consistent latency) and one public subnet. The load generators and
shims each run `node_exporter`; the observability host scrapes them plus the shim `/metrics` and the
Couchbase Prometheus endpoint, and serves the **Host Metrics** and **Couchbase** Grafana dashboards so
you can attribute the knee to a specific resource on a specific host.

## Prerequisites

- AWS credentials (`aws configure` / env / SSO) with EC2 + VPC + ELB permissions.
- An existing EC2 **key pair** in the target region.
- Terraform ≥ 1.5.

## Configure & launch

```bash
cd deploy/ec2
cat > terraform.tfvars <<'EOF'
region            = "us-east-1"
availability_zone = "us-east-1a"
key_name          = "my-keypair"
ssh_ingress_cidr  = "203.0.113.10/32"   # YOUR public IP /32 — never 0.0.0.0/0
shim_count        = 2
loadgen_count     = 2
EOF

terraform init
terraform apply
```

Outputs include the **NLB DNS**, **Grafana URL**, and the load-gen public IPs.

## Run the capacity sweep

SSH to a load-gen host and run the stepped sweep (it reads the NLB endpoint from `/etc/pgc-rig.env`):

```bash
ssh ec2-user@<loadgen_public_ip>
/opt/pgc-rig/capacity-sweep.sh                       # defaults: concurrency 8…256, mixed profile
# or tune it:
CONCURRENCY_STEPS="16 32 64 128 256 512" P99_SLO_MS=20 \
  BENCH_DURATION_SECONDS=120 BENCH_PROFILE=read-heavy /opt/pgc-rig/capacity-sweep.sh
```

It prints a throughput / p99 / errors table and flags the **knee**. To drive aggregate load, run it on
**every** load-gen host at once, and add hosts (`loadgen_count`) until total throughput stops rising —
only then is the measured ceiling real and not client-bound.

While it runs, open Grafana (`grafana_url`) → **ProtoGemCouch Host Metrics** (shim CPU, network) and
**ProtoGemCouch Couchbase** (disk-write queue, OOM, latency) to see which tier saturates.

## Measure scaling efficiency

Re-run the sweep at `shim_count = 1`, then `2`, then `4`:

```bash
terraform apply -var shim_count=1   # then sweep; repeat for 2, 4
```

Compare peak ops/sec per replica count — that's the horizontal-scaling answer, and where Couchbase (not
the shim) becomes the ceiling shows up as a flat disk-write-queue/OOM climb on the Couchbase dashboard.

## Tear down

```bash
terraform destroy
```

Use **Spot** and destroy promptly — a ceiling sweep is hours, not days.

## Scope & caveats (honest)

- **One Couchbase node** by default (dedicated, but single). A 3-node cluster is more representative;
  it needs server-add + rebalance steps not automated here. Match `CB_DURABILITY` / bucket replicas to
  production when you do — durability changes the write ceiling substantially.
- **Dev credentials** (`Administrator`/`password`) by default. Override `TF_VAR_couchbase_password`
  and lock `ssh_ingress_cidr` to your IP. This rig is for ephemeral testing, not a standing deployment.
- The NLB uses **source-IP stickiness** so connection-oriented clients stay pinned for a run.
- Load gens raise `nofile` and the ephemeral port range; for very high concurrency you may still need
  more load-gen hosts (client-side socket exhaustion, not a shim limit).
- The observability host reuses the repo's Grafana provisioning; its Loki/Jaeger datasources are unused
  on the rig (no log/trace pipeline here) and those panels stay empty — expected.
