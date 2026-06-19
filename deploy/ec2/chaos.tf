# Self-driving failure-injection experiment infrastructure (only created when chaos_experiment=true).
#
# The load-gen and Couchbase hosts run the experiment themselves on boot (see chaos-autorun.sh) and
# upload results here; the operator fetches them with the AWS CLI — no SSH, no manual steps.

locals {
  chaos = var.chaos_experiment ? 1 : 0
}

# ---- Results bucket (private; force_destroy so `terraform destroy` removes it with its objects) ----

resource "aws_s3_bucket" "chaos" {
  count         = local.chaos
  bucket_prefix = "${var.name_prefix}-chaos-"
  force_destroy = true
  tags          = { Name = "${var.name_prefix}-chaos-results" }
}

resource "aws_s3_bucket_public_access_block" "chaos" {
  count                   = local.chaos
  bucket                  = aws_s3_bucket.chaos[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---- Instance profile letting the load-gen + Couchbase hosts upload results to the bucket ----

data "aws_iam_policy_document" "chaos_assume" {
  count = local.chaos
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "chaos" {
  count              = local.chaos
  name_prefix        = "${var.name_prefix}-chaos-"
  assume_role_policy = data.aws_iam_policy_document.chaos_assume[0].json
}

data "aws_iam_policy_document" "chaos_s3" {
  count = local.chaos
  statement {
    actions   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.chaos[0].arn, "${aws_s3_bucket.chaos[0].arn}/*"]
  }
}

resource "aws_iam_role_policy" "chaos" {
  count  = local.chaos
  name   = "chaos-results-s3"
  role   = aws_iam_role.chaos[0].id
  policy = data.aws_iam_policy_document.chaos_s3[0].json
}

resource "aws_iam_instance_profile" "chaos" {
  count       = local.chaos
  name_prefix = "${var.name_prefix}-chaos-"
  role        = aws_iam_role.chaos[0].name
}
