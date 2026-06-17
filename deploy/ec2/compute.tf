locals {
  ami = data.aws_ssm_parameter.al2023.value

  common = {
    couchbase_username = var.couchbase_username
    couchbase_password = var.couchbase_password
    couchbase_bucket   = var.couchbase_bucket
  }
}

# ---- Couchbase (dedicated backend) ----

resource "aws_instance" "couchbase" {
  ami                    = local.ami
  instance_type          = var.couchbase_instance_type
  subnet_id              = aws_subnet.rig.id
  availability_zone      = var.availability_zone
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.rig.id]

  user_data = templatefile("${path.module}/templates/couchbase-user-data.sh.tftpl", merge(local.common, {
    couchbase_image         = var.couchbase_image
    couchbase_bucket_ram_mb = var.couchbase_bucket_ram_mb
  }))

  root_block_device {
    volume_size = 100
    volume_type = "gp3"
  }

  tags = { Name = "${var.name_prefix}-couchbase", Role = "couchbase" }
}

# ---- Shim tier (scale COUNT to test horizontal scaling) ----

resource "aws_instance" "shim" {
  count                  = var.shim_count
  ami                    = local.ami
  instance_type          = var.shim_instance_type
  subnet_id              = aws_subnet.rig.id
  availability_zone      = var.availability_zone
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.rig.id]

  user_data = templatefile("${path.module}/templates/shim-user-data.sh.tftpl", merge(local.common, {
    shim_image   = var.shim_image
    couchbase_ip = aws_instance.couchbase.private_ip
  }))

  tags = { Name = "${var.name_prefix}-shim-${count.index}", Role = "shim" }
}

# ---- Network Load Balancer in front of the shim tier (single VIP the benchmark targets) ----

resource "aws_lb" "shim" {
  name               = "${var.name_prefix}-nlb"
  load_balancer_type = "network"
  internal           = false
  subnets            = [aws_subnet.rig.id]
  tags               = { Name = "${var.name_prefix}-nlb" }
}

resource "aws_lb_target_group" "shim" {
  name        = "${var.name_prefix}-tg"
  port        = 40405
  protocol    = "TCP"
  vpc_id      = aws_vpc.rig.id
  target_type = "instance"

  # HTTP health check against the shim's readiness endpoint on the health port.
  health_check {
    protocol            = "HTTP"
    port                = "8081"
    path                = "/ready"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }

  # Connection-oriented protocol benchmark: keep clients pinned for the run.
  stickiness {
    enabled = true
    type    = "source_ip"
  }
}

resource "aws_lb_listener" "shim" {
  load_balancer_arn = aws_lb.shim.arn
  port              = 40405
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.shim.arn
  }
}

resource "aws_lb_target_group_attachment" "shim" {
  count            = var.shim_count
  target_group_arn = aws_lb_target_group.shim.arn
  target_id        = aws_instance.shim[count.index].id
  port             = 40405
}

# ---- Load generators (reuse the published image to run the benchmark against the NLB) ----

resource "aws_instance" "loadgen" {
  count                  = var.loadgen_count
  ami                    = local.ami
  instance_type          = var.loadgen_instance_type
  subnet_id              = aws_subnet.rig.id
  availability_zone      = var.availability_zone
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.rig.id]

  user_data = templatefile("${path.module}/templates/loadgen-user-data.sh.tftpl", {
    shim_image = var.shim_image
    nlb_dns    = aws_lb.shim.dns_name
    git_repo   = var.git_repo
    git_ref    = var.git_ref
  })

  tags = { Name = "${var.name_prefix}-loadgen-${count.index}", Role = "loadgen" }
}

# ---- Observability (Prometheus + Grafana, scrape config rendered with every host's private IP) ----

locals {
  node_ips = concat(
    aws_instance.shim[*].private_ip,
    [aws_instance.couchbase.private_ip],
    aws_instance.loadgen[*].private_ip,
  )

  prometheus_yml = templatefile("${path.module}/templates/prometheus.yml.tftpl", {
    shim_ips     = aws_instance.shim[*].private_ip
    couchbase_ip = aws_instance.couchbase.private_ip
    node_ips     = local.node_ips
    cb_user      = var.couchbase_username
    cb_pass      = var.couchbase_password
  })
}

resource "aws_instance" "observability" {
  ami                    = local.ami
  instance_type          = var.observability_instance_type
  subnet_id              = aws_subnet.rig.id
  availability_zone      = var.availability_zone
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.rig.id]

  user_data = templatefile("${path.module}/templates/observability-user-data.sh.tftpl", {
    prometheus_yml_b64 = base64encode(local.prometheus_yml)
    git_repo           = var.git_repo
    git_ref            = var.git_ref
  })

  tags = { Name = "${var.name_prefix}-observability", Role = "observability" }
}
