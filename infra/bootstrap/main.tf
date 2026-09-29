# The S3 bucket that holds the main stack's Terraform state.
#
# State can contain secrets -- the generated ADMIN_TOKEN will be in it
# (see the Milestone 3 plan) -- so the bucket is private, encrypted, reachable
# over TLS only, and versioned so a bad write can be rolled back.

# Who is running this: used to make the bucket name unique. S3 bucket names
# are global across every AWS account, so "grain-bin-tfstate" alone could
# already be taken; the account ID makes it ours.
data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "state" {
  bucket = "${var.state_bucket_prefix}-${data.aws_caller_identity.current.account_id}-${var.region}"

  # Terraform refuses to plan any change that would delete this bucket.
  # Losing it means losing the record of what the main stack created, and
  # having to find and delete every resource by hand. To delete it on
  # purpose, remove this block first.
  lifecycle {
    prevent_destroy = true
  }
}

# Every write keeps the previous version, so a corrupted or mistaken state
# file can be restored from S3's version history.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Encrypted at rest with S3-managed keys (SSE-S3). A customer-managed KMS key
# would add a key policy and about $1 a month for no benefit at this scale.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# All four public-access switches on: no ACL or bucket policy can ever make
# this bucket, or anything in it, public -- even by mistake.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# The bucket owner owns every object, and ACLs are switched off entirely;
# access is decided by IAM and the bucket policy alone. AWS's recommended
# setting for new buckets.
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Refuse any request not made over HTTPS. Terraform and the AWS CLI always
# use TLS; this makes sure nothing else can read state in plain text.
resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.tls_only.json

  # The public-access block must be in place before a policy is attached.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

data "aws_iam_policy_document" "tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

# Old state versions are kept for 90 days, then deleted, so versioning does
# not grow the bucket forever. Also clears the small lock files that
# use_lockfile leaves behind as they are replaced.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  # Lifecycle rules on a versioned bucket must be applied after versioning.
  depends_on = [aws_s3_bucket_versioning.state]
}
