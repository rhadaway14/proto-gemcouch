# One security group for every rig host. Intra-rig traffic is wide open (self-referencing) so
# Prometheus can scrape node_exporter (9100) / Couchbase (8091) / the shim (8081), the shim can reach
# Couchbase (8091/11210), and load generators can reach the shim (40405). External access is locked to
# the operator CIDR.
resource "aws_security_group" "rig" {
  name        = "${var.name_prefix}-sg"
  description = "ProtoGemCouch capacity rig"
  vpc_id      = aws_vpc.rig.id

  tags = { Name = "${var.name_prefix}-sg" }
}

# All traffic between rig hosts.
resource "aws_vpc_security_group_ingress_rule" "self_all" {
  security_group_id            = aws_security_group.rig.id
  referenced_security_group_id = aws_security_group.rig.id
  ip_protocol                  = "-1"
  description                  = "intra-rig (scrapes, shim to couchbase, loadgen to shim)"
}

# NLB health checks + data plane originate from within the VPC (the NLB ENIs and the load-gen hosts).
resource "aws_vpc_security_group_ingress_rule" "vpc_shim_data" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = local.vpc_cidr
  from_port         = 40405
  to_port           = 40405
  ip_protocol       = "tcp"
  description       = "shim data port from NLB / load gens"
}

resource "aws_vpc_security_group_ingress_rule" "vpc_shim_health" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = local.vpc_cidr
  from_port         = 8081
  to_port           = 8081
  ip_protocol       = "tcp"
  description       = "shim health/metrics port from NLB health checks / Prometheus"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = var.ssh_ingress_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
  description       = "SSH from operator"
}

resource "aws_vpc_security_group_ingress_rule" "grafana" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = var.ssh_ingress_cidr
  from_port         = 3000
  to_port           = 3000
  ip_protocol       = "tcp"
  description       = "Grafana from operator"
}

# Optional: drive load against the internet-facing NLB from the operator's machine. NLB preserves the
# client source IP, so the shim SG must admit it.
resource "aws_vpc_security_group_ingress_rule" "ext_shim" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = var.ssh_ingress_cidr
  from_port         = 40405
  to_port           = 40405
  ip_protocol       = "tcp"
  description       = "external load against the NLB from operator"
}

resource "aws_vpc_security_group_ingress_rule" "prometheus" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = var.ssh_ingress_cidr
  from_port         = 9090
  to_port           = 9090
  ip_protocol       = "tcp"
  description       = "Prometheus from operator"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.rig.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "all egress (pull images, OS updates)"
}
