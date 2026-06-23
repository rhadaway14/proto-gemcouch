variable "region" {
  description = "AWS region for the capacity rig."
  type        = string
  default     = "us-east-1"
}

variable "availability_zone" {
  description = "Single AZ for the whole rig — keep every host in one AZ so inter-host latency is low and consistent (capacity measurements should not be polluted by cross-AZ jitter)."
  type        = string
  default     = "us-east-1a"
}

variable "key_name" {
  description = "Name of an existing EC2 key pair for SSH access to the rig hosts."
  type        = string
}

variable "ssh_ingress_cidr" {
  description = "CIDR allowed to SSH (22) to the hosts and to reach Grafana (3000) / the shim NLB. Set to YOUR public IP /32, not 0.0.0.0/0."
  type        = string
}

variable "name_prefix" {
  description = "Prefix for all resource names."
  type        = string
  default     = "pgc-cap"
}

# ---- Topology ----

variable "shim_count" {
  description = "Number of shim hosts behind the NLB. Sweep 1 -> 2 -> 4 to measure horizontal scaling efficiency."
  type        = number
  default     = 2
}

variable "loadgen_count" {
  description = "Number of load-generator hosts. Add more until total throughput stops rising (only then is the measured ceiling real and not client-bound)."
  type        = number
  default     = 2
}

# ---- Instance sizing (see README for the rationale) ----

variable "shim_instance_type" {
  description = "Shim hosts: compute-optimized; scale COUNT (not size) to test horizontal scaling."
  type        = string
  default     = "c6i.xlarge"
}

variable "couchbase_instance_type" {
  description = "Couchbase host: memory-optimized, prod-sized."
  type        = string
  default     = "r6i.xlarge"
}

variable "loadgen_instance_type" {
  description = "Load generators."
  type        = string
  default     = "c6i.2xlarge"
}

variable "observability_instance_type" {
  description = "Prometheus + Grafana host."
  type        = string
  default     = "t3.large"
}

# ---- Application / config ----

variable "shim_image" {
  description = "Published shim image (also reused as the load-generator: it carries the benchmark main in the same fat jar)."
  type        = string
  default     = "docker.io/rhadaway14/protogemcouch:latest"
}

variable "couchbase_image" {
  description = "Couchbase Server image (Enterprise is licensed for dev/test; switch to community if required)."
  type        = string
  default     = "couchbase:enterprise-7.6.2"
}

variable "couchbase_bucket" {
  description = "Bucket the shim uses; the rig creates it on the Couchbase host."
  type        = string
  default     = "test"
}

variable "couchbase_username" {
  description = "Couchbase admin user (dev/test rig). Prefer injecting via TF_VAR_couchbase_password from a secret store; do not commit real credentials."
  type        = string
  default     = "Administrator"
}

variable "couchbase_password" {
  description = "Couchbase admin password (dev/test rig). Override via TF_VAR_couchbase_password."
  type        = string
  default     = "password"
  sensitive   = true
}

variable "couchbase_bucket_ram_mb" {
  description = "Bucket quota (MiB). Size to the host's memory for a representative backend."
  type        = number
  default     = 4096
}

variable "git_repo" {
  description = "Repo cloned on the observability host for the Grafana dashboards + provisioning + alert rules."
  type        = string
  default     = "https://github.com/rhadaway14/proto-gemcouch.git"
}

variable "git_ref" {
  description = "Branch/tag/SHA to check out on the observability + load-gen hosts. Defaults to main (the trunk; the old master branch was retired in the 2026-06 trunk migration)."
  type        = string
  default     = "main"
}

# ---- Self-driving failure-injection experiment (hands-off; no SSH) ----

variable "chaos_experiment" {
  description = "When true, the rig runs the failure-injection experiment itself on boot (load-gens drive sustained load; the Couchbase host injects latency/loss/partial/partition/outage) and uploads results to an S3 bucket. Off = a normal rig you drive manually."
  type        = bool
  default     = false
}

variable "chaos_load_duration" {
  description = "Seconds the load-gens drive sustained load during the experiment (must comfortably exceed warmup + the fault scenario, ~750s)."
  type        = number
  default     = 1500
}

variable "chaos_warmup" {
  description = "Seconds the Couchbase host waits after the shim is reachable before injecting the first fault (lets load ramp and dashboards fill)."
  type        = number
  default     = 180
}

variable "chaos_profile" {
  description = "Benchmark profile for the experiment load. read-heavy gives the cleanest latency/loss signal."
  type        = string
  default     = "read-heavy"
}

variable "chaos_concurrency" {
  description = "Per-load-gen benchmark concurrency during the experiment."
  type        = number
  default     = 64
}
