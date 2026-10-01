# The main stack: everything the deployed application runs on.
#
#   cloudfront.net (HTTPS) --/api/*--> internal ALB --> ECS Fargate task --> RDS PostgreSQL
#                          \--/*-----> S3 (the built dashboard)
#
# Applied by the owner, never by an assistant; see infra/README.md for the
# runbook. State lives in the bucket that infra/bootstrap created.

terraform {
  # 1.11 for write-only arguments: the generated ADMIN_TOKEN is written to
  # Secrets Manager through one (secret_string_wo), so it never appears in
  # the state file or a saved plan. See secrets.tf.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }

  # Where this stack's state lives: the bootstrap bucket, one object per
  # stack. use_lockfile makes Terraform write a small .tflock object next to
  # the state while it works, so two runs -- your laptop and GitHub Actions,
  # say -- can never write the state at the same time. (Before Terraform
  # 1.10 this needed a DynamoDB table.)
  #
  # The BUCKET is deliberately not written here: its name contains the AWS
  # account ID, which this public repository does not publish. It is
  # supplied at `terraform init` instead (a "partial backend
  # configuration"):
  #   - locally, from backend.hcl -- git-ignored; copy backend.hcl.example
  #       terraform init "-backend-config=backend.hcl"   (quoted, for PowerShell)
  #   - in GitHub Actions, from the TF_STATE_BUCKET repository variable
  backend "s3" {
    key          = "main/terraform.tfstate"
    region       = "ca-central-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "grain-bin-monitoring"
      ManagedBy = "terraform"
      Stack     = "main"
    }
  }
}
