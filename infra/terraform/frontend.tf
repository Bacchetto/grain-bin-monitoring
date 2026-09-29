# The public front door: one CloudFront distribution, one HTTPS address.
#
#   /api/*  -> the internal ALB, privately, through a VPC origin; never cached
#   /*      -> the dashboard's built files in a private S3 bucket; cached
#
# One address for both, rather than the dashboard on CloudFront and the API
# on the ALB, for three reasons:
#   - An HTTPS page cannot call an HTTP API; browsers block it as mixed
#     content. The ALB would need its own domain and certificate to be HTTPS.
#   - Same origin: the dashboard calls /api/v1 on its own address, so no CORS
#     is needed in production.
#   - Only /api/* is exposed: the ALB is internal, so nothing else on it is
#     reachable (enhancement E15).
# Recorded in an ADR.

# ---------------------------------------------------------------------------
# The dashboard's files
# ---------------------------------------------------------------------------

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "frontend" {
  bucket = "${var.name}-frontend-${data.aws_caller_identity.current.account_id}-${var.region}"
  # The files are rebuilt from the repository on every deploy, so destroying
  # them with the stack loses nothing.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Origin Access Control: CloudFront signs its requests to S3, and the bucket
# policy below accepts only requests signed for this distribution. The bucket
# itself stays completely private.
resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.name}-frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "AllowThisDistributionOnly"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket     = aws_s3_bucket.frontend.id
  policy     = data.aws_iam_policy_document.frontend_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

# ---------------------------------------------------------------------------
# The API's private connection
# ---------------------------------------------------------------------------

# A VPC origin gives CloudFront a private path to the internal ALB: it creates
# a network interface in the private subnets and reaches the ALB from there,
# over AWS's network. No public address is involved.
resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${var.name}-api"
    arn                    = aws_lb.api.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# ---------------------------------------------------------------------------
# The distribution
# ---------------------------------------------------------------------------

resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for the dashboard's client-side routes"
  code    = file("${path.module}/spa-rewrite.js")
  publish = true
}

# AWS-managed policies, looked up by name.
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header, cookie and query string to the API --
# including Authorization and X-Device-Key, which CloudFront strips by
# default. Safe here only because /api/* is never cached: with caching on, a
# response fetched with one user's token could be served to another.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

locals {
  s3_origin_id  = "dashboard"
  api_origin_id = "api"
  custom_domain = var.domain_name != ""
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  comment             = "Grain Bin Telemetry: dashboard and API"
  default_root_object = "index.html"
  # North America and Europe edge locations only: the cheapest price class,
  # and where the users are.
  price_class = "PriceClass_100"
  aliases     = local.custom_domain ? [var.domain_name] : []

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    origin_id   = local.api_origin_id
    domain_name = aws_lb.api.dns_name

    vpc_origin_config {
      vpc_origin_id = aws_cloudfront_vpc_origin.api.id
    }
  }

  # Everything that is not /api/*: the dashboard.
  default_cache_behavior {
    target_origin_id       = local.s3_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  # The API: every method, nothing cached, every header forwarded.
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.api_origin_id
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # HTTPS certificate: CloudFront's own for *.cloudfront.net, or the given
  # ACM certificate for a custom domain.
  viewer_certificate {
    cloudfront_default_certificate = !local.custom_domain
    acm_certificate_arn            = local.custom_domain ? var.acm_certificate_arn : null
    ssl_support_method             = local.custom_domain ? "sni-only" : null
    minimum_protocol_version       = local.custom_domain ? "TLSv1.2_2021" : null
  }

  lifecycle {
    precondition {
      condition     = !local.custom_domain || var.acm_certificate_arn != ""
      error_message = "domain_name is set, so acm_certificate_arn must be too."
    }
  }
}
