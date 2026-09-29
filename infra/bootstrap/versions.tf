# The bootstrap config: creates the S3 bucket that stores the MAIN stack's
# Terraform state (infra/terraform). It is applied once, by hand, before
# anything else.
#
# WHY A SEPARATE CONFIG: Terraform keeps a "state" file -- its record of which
# real resources it manages. The main stack keeps that file in S3, so it is
# shared between your machine and GitHub Actions and never lost with a laptop.
# But the bucket has to exist before any config can store state in it, so it
# cannot be created by the config whose state it holds. This small config
# creates it, and keeps its OWN state as a local file (terraform.tfstate here,
# git-ignored), which is acceptable because it manages one bucket that almost
# never changes.

terraform {
  # 1.10 introduced S3-native state locking (use_lockfile), which the main
  # stack relies on instead of a DynamoDB lock table.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # "~> 6.66": any 6.x from 6.66 up, never 7.0. Minor versions add
      # features without breaking; a major version may break things, so it
      # has to be chosen deliberately. .terraform.lock.hcl then pins the
      # exact version, so every run uses the same one.
      version = "~> 6.66"
    }
  }
}

provider "aws" {
  region = var.region

  # Tags every resource this config creates, so the AWS console and the bill
  # show what belongs to this project and how it was made.
  default_tags {
    tags = {
      Project   = "grain-bin-monitoring"
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}
