locals {
  vpc_cidr = "10.42.0.0/16"
}

# Amazon Linux 2023 (x86_64) — resolved from the public SSM parameter so no AMI id is hardcoded.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_vpc" "rig" {
  cidr_block           = local.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = "${var.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "rig" {
  vpc_id = aws_vpc.rig.id
  tags   = { Name = "${var.name_prefix}-igw" }
}

# Single public subnet in one AZ — keep the whole rig co-located for low, consistent latency.
resource "aws_subnet" "rig" {
  vpc_id                  = aws_vpc.rig.id
  cidr_block              = "10.42.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true
  tags                    = { Name = "${var.name_prefix}-subnet" }
}

resource "aws_route_table" "rig" {
  vpc_id = aws_vpc.rig.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.rig.id
  }
  tags = { Name = "${var.name_prefix}-rt" }
}

resource "aws_route_table_association" "rig" {
  subnet_id      = aws_subnet.rig.id
  route_table_id = aws_route_table.rig.id
}
