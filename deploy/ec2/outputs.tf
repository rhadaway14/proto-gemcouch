output "nlb_dns" {
  description = "Shim NLB endpoint — point the benchmark here (BENCH_HOST). Port 40405."
  value       = aws_lb.shim.dns_name
}

output "grafana_url" {
  description = "Grafana (anonymous viewer; admin/admin to edit)."
  value       = "http://${aws_instance.observability.public_ip}:3000"
}

output "prometheus_url" {
  description = "Prometheus targets page."
  value       = "http://${aws_instance.observability.public_ip}:9090/targets"
}

output "shim_public_ips" {
  description = "Shim hosts (SSH / direct inspection)."
  value       = aws_instance.shim[*].public_ip
}

output "couchbase_public_ip" {
  description = "Couchbase host (SSH; UI on :8091)."
  value       = aws_instance.couchbase.public_ip
}

output "loadgen_public_ips" {
  description = "Load-gen hosts. SSH in and run /opt/pgc-rig/capacity-sweep.sh."
  value       = aws_instance.loadgen[*].public_ip
}

output "capacity_sweep_hint" {
  description = "How to run the capacity sweep."
  value       = "ssh ec2-user@<loadgen_public_ip> then: /opt/pgc-rig/capacity-sweep.sh   (override CONCURRENCY_STEPS, BENCH_DURATION_SECONDS, etc.)"
}
