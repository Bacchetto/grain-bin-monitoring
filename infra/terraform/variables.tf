# Inputs. Values without defaults go in a git-ignored terraform.tfvars; see
# terraform.tfvars.example.

variable "region" {
  description = "AWS region for everything except CloudFront, which is global."
  type        = string
  default     = "ca-central-1"
}

variable "name" {
  description = "Prefix for resource names, so everything this stack creates is recognisable in the console."
  type        = string
  default     = "grain-bin"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository whose workflows may assume the plan and deploy roles."
  type        = string
  default     = "Bacchetto/grain-bin-monitoring"
}

# ---------------------------------------------------------------------------
# Deployment
# ---------------------------------------------------------------------------

variable "image_tag" {
  description = "The API image tag in ECR to run -- deploy.yml passes the git commit SHA."
  type        = string
  default     = "latest"
}

variable "desired_count" {
  description = <<-EOT
    How many API tasks to run. 1 normally. Set 0 for the very first apply:
    the ECR repository is created by that same apply, so no image exists yet
    for a task to start from.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count >= 0 && var.desired_count <= 4
    error_message = "desired_count must be between 0 and 4."
  }
}

# ---------------------------------------------------------------------------
# Notifications and cost
# ---------------------------------------------------------------------------

variable "alert_email" {
  description = "Where CloudWatch alarms and budget alerts are emailed. AWS sends a confirmation email that must be accepted before alarms arrive."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email must be an email address."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget in USD. Alerts at 80% of actual spend and 100% of forecast."
  type        = number
  default     = 25
}

# ---------------------------------------------------------------------------
# Optional custom domain (README: HTTPS on a custom domain if one is provided)
# ---------------------------------------------------------------------------

variable "domain_name" {
  description = "Optional custom domain for the site, e.g. grain.example.com. Empty uses the free *.cloudfront.net address."
  type        = string
  default     = ""
}

variable "acm_certificate_arn" {
  description = "ACM certificate for domain_name. CloudFront requires it to be issued in us-east-1. Required when domain_name is set."
  type        = string
  default     = ""

  validation {
    condition     = var.acm_certificate_arn == "" || can(regex("^arn:aws:acm:us-east-1:", var.acm_certificate_arn))
    error_message = "CloudFront only accepts ACM certificates issued in us-east-1."
  }
}
