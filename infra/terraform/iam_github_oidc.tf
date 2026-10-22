# How GitHub Actions gets into AWS: OpenID Connect, with no stored keys.
#
# THE FLOW. When a workflow job runs, GitHub can issue it a short-lived signed
# token (a JWT) that says which repository, branch, pull request or
# environment the job belongs to. The job presents that token to AWS STS;
# AWS checks the signature against GitHub's published keys, checks the
# token's claims against a role's trust policy below, and if they match,
# hands back temporary credentials for that role -- valid for about an hour.
#
# So no AWS access key is ever stored in GitHub. There is nothing long-lived
# to leak, rotate, or forget to revoke. Spec requirement.
#
# TWO ROLES, least privilege for each job:
#   plan   -- pull requests; read-only, for `terraform plan` (plan.yml)
#   deploy -- only jobs in the `production` GitHub environment, which
#             requires the owner's approval; applies changes (deploy.yml)

# Tells AWS to trust tokens issued by GitHub Actions. One per AWS account.
resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
  # The token's audience: GitHub sets it to sts.amazonaws.com when the
  # workflow uses aws-actions/configure-aws-credentials.
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  github_oidc = "token.actions.githubusercontent.com"
  owner       = split("/", var.github_repository)[0]
  repo_name   = split("/", var.github_repository)[1]
  # "Bacchetto@51663220/grain-bin-monitoring@1400530202": owner and
  # repository, each followed by its numeric ID.
  repo_subject = "${local.owner}@${var.github_owner_id}/${local.repo_name}@${var.github_repository_id}"
  account_id   = data.aws_caller_identity.current.account_id
  state_arn    = "arn:aws:s3:::grain-bin-tfstate-${local.account_id}-${var.region}"
}

# A trust policy for GitHub tokens whose "sub" (subject) claim matches.
# The subject names exactly where the job came from:
#   repo:Bacchetto@51663220/grain-bin-monitoring@1400530202:pull_request
#   repo:Bacchetto@51663220/grain-bin-monitoring@1400530202:environment:production
# StringEquals, not StringLike: no wildcards, so no other repository, fork or
# branch can ever match.
#
# IMMUTABLE SUBJECTS. This repository's tokens carry the owner's and the
# repository's numeric IDs as well as their names (GitHub's immutable subject
# format, the default for repositories created since it was introduced). It is
# the safer form: if this repository were deleted, or renamed, a new one
# created under the same name has a different ID, so it can never assume
# these roles. A trust policy written for names alone would trust it.
data "aws_iam_policy_document" "github_trust" {
  for_each = {
    plan   = "repo:${local.repo_subject}:pull_request"
    deploy = "repo:${local.repo_subject}:environment:production"
  }

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc}:sub"
      values   = [each.value]
    }
  }
}

# ---------------------------------------------------------------------------
# plan: read-only
# ---------------------------------------------------------------------------

resource "aws_iam_role" "github_plan" {
  name                 = "${var.name}-github-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_trust["plan"].json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "github_plan_readonly" {
  role       = aws_iam_role.github_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# ReadOnlyAccess can read the state but not lock it. terraform plan takes the
# state lock -- a small .tflock object next to the state -- so this allows
# writing and deleting exactly that one object, nothing else.
data "aws_iam_policy_document" "github_plan_lock" {
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.state_arn}/main/terraform.tfstate.tflock"]
  }
}

resource "aws_iam_role_policy" "github_plan_lock" {
  name   = "terraform-state-lock"
  role   = aws_iam_role.github_plan.id
  policy = data.aws_iam_policy_document.github_plan_lock.json
}

# ---------------------------------------------------------------------------
# deploy: applies this stack
# ---------------------------------------------------------------------------
# Applying this stack means creating and changing nearly every kind of
# resource in it, IAM roles included, so this role is necessarily powerful.
# Three things contain it:
#   1. only a job in the `production` environment can assume it, and that
#      environment requires the owner's approval before any job starts;
#   2. PowerUserAccess covers every service here except IAM, and IAM is
#      granted only for roles and the OIDC provider named after this stack;
#   3. credentials last an hour at most.

resource "aws_iam_role" "github_deploy" {
  name                 = "${var.name}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_trust["deploy"].json
  max_session_duration = 3600
}

# Every AWS service except IAM, Organizations and account management.
resource "aws_iam_role_policy_attachment" "github_deploy_power_user" {
  role       = aws_iam_role.github_deploy.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

data "aws_iam_policy_document" "github_deploy_iam" {
  # Manage this stack's own roles -- and nothing else in IAM.
  statement {
    sid     = "ManageThisStacksRoles"
    actions = ["iam:*"]
    resources = [
      "arn:aws:iam::${local.account_id}:role/${var.name}-*",
      aws_iam_openid_connect_provider.github.arn,
    ]
  }

  # Hand the execution role to ECS, and only to ECS.
  statement {
    sid       = "PassExecutionRoleToEcs"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.api_execution.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # Read-only IAM calls Terraform makes while planning.
  statement {
    sid       = "ReadIam"
    actions   = ["iam:Get*", "iam:List*"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy_iam" {
  name   = "manage-this-stacks-iam"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy_iam.json
}
