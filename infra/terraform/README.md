# Main stack

Everything the deployed application runs on, in `ca-central-1`:

```
viewer --HTTPS--> CloudFront --/api/*--> internal ALB --> ECS Fargate task --> RDS PostgreSQL
                             \--/*-----> S3 (the built dashboard)
```

| File | What it creates |
|---|---|
| `network.tf` | VPC across two zones: public subnets (ECS tasks), private subnets (RDS, the ALB), an internet gateway, **no NAT gateway** |
| `ecr.tf` | The API image registry: immutable tags, scan on push, keep the last 10 |
| `secrets.tf` | The `ADMIN_TOKEN` secret, generated on first apply and **never stored in Terraform state** |
| `rds.tf` | PostgreSQL 16, `db.t4g.micro`, single-AZ, private, password managed by RDS |
| `ecs.tf` | Fargate cluster, task (0.5 vCPU / 1 GB) and service; logs to CloudWatch |
| `alb.tf` | **Internal** load balancer; forwards only `/api/*`; accepts traffic only from CloudFront |
| `frontend.tf` | CloudFront (HTTPS), the private dashboard bucket, and the VPC origin that reaches the ALB privately |
| `iam_github_oidc.tf` | GitHub OIDC provider; `plan` (read-only) and `deploy` (behind approval) roles |
| `observability.tf` | Email alarms: API 5xx rate, ECS CPU, RDS free storage |
| `budgets.tf` | Monthly cost budget with email alerts |

## Cost

Running, roughly **$45–60 a month** in `ca-central-1`, most of it by the hour
whether used or not. These are estimates from AWS's list prices as of
writing, not a quote -- check them in the
[AWS Pricing Calculator](https://calculator.aws/), and against the budget
alerts once it is running.

| Item | ~Monthly |
|---|---|
| Application Load Balancer | $18–20 |
| RDS `db.t4g.micro` + 20 GB gp3 | $15–17 |
| Fargate, 0.5 vCPU / 1 GB, always on | $17–19 |
| Public IPv4 address for the task | $3.60 |
| CloudFront, S3, ECR, Secrets Manager, CloudWatch, logs | a few dollars |

**Destroy it when not demoing** (README: cost discipline). An hour of demo
costs a few cents; a forgotten month costs the table above.

## Applying it (the owner does this -- Milestone 3 Phase 6)

Run from the repository root in PowerShell, signed in with the admin
session. `terraform.tfvars` is git-ignored; copy it from the example first.

```powershell
aws sso login --sso-session grain-admin
$env:AWS_PROFILE = "grain-admin"
$env:TF_DATA_DIR = "$env:LOCALAPPDATA\grain-bin-terraform\main"   # see ADR 0021
cd infra\terraform
copy terraform.tfvars.example terraform.tfvars                        # then edit alert_email
terraform init

# 1. The budget first, so the alert exists before anything costs money.
terraform plan -target="aws_budgets_budget.monthly" -out budget.tfplan
terraform apply budget.tfplan

# 2. Everything else, with no API task yet: the image registry is created by
#    this same apply, so there is no image for a task to start from.
terraform plan -var desired_count=0 -out main.tfplan      # read it: ~57 to add
terraform apply main.tfplan                               # 15-25 minutes: RDS and CloudFront are slow

# 3. The first image and the dashboard: deploy.yml does this (Phase 5 and 6).

aws sso logout
aws sso login --sso-session grain
```

Afterwards: confirm the SNS subscription email AWS sends to `alert_email`, or
alarms will not be delivered.

**To see the admin token** (to sign in to the dashboard):

```powershell
aws secretsmanager get-secret-value --secret-id grain-bin/admin-token --query SecretString --output text --profile grain-admin
```

**To tear it all down:** `terraform destroy`, with the same environment
variables. The state bucket (bootstrap) is not affected.

## Deploying through GitHub Actions

After the first apply, two workflows take over:

| Workflow | Runs on | Does |
|---|---|---|
| `plan.yml` | PRs that change `infra/terraform/` | `terraform plan` with the read-only role; posts the result as a PR comment |
| `deploy.yml` | CI passing on `main` | **After the owner approves:** build and push the image, `terraform apply`, roll ECS, publish the dashboard, invalidate CloudFront |

They authenticate with GitHub OIDC -- no AWS keys are stored in GitHub. They
need four settings in the repository, made once after the first apply (the
role ARNs come from `terraform output`):

| Setting | Kind | Value |
|---|---|---|
| `AWS_PLAN_ROLE_ARN` | Variable | `terraform output -raw github_plan_role_arn` |
| `AWS_DEPLOY_ROLE_ARN` | Variable | `terraform output -raw github_deploy_role_arn` |
| `ALERT_EMAIL` | Secret | the same address as `alert_email` in `terraform.tfvars` |
| `production` | Environment | **Required reviewers: the owner.** This is the approval gate |

With the GitHub CLI:

```powershell
gh variable set AWS_PLAN_ROLE_ARN   --body (terraform output -raw github_plan_role_arn)
gh variable set AWS_DEPLOY_ROLE_ARN --body (terraform output -raw github_deploy_role_arn)
gh secret set ALERT_EMAIL                      # prompts for the value
```

The `production` environment is easiest in the browser: **Settings →
Environments → New environment → `production` → Required reviewers**, add
yourself, and restrict **Deployment branches** to `main`.

Until the two variables exist, both workflows skip themselves, so merging
them before the stack exists is harmless.

**A deploy always runs one task** (`desired_count=1`), which is also how the
service starts after the first apply's `desired_count=0`.

## Planning without applying

A read-only role can plan but cannot take the state lock, so read-only plans
pass `-lock=false`. Safe: a plan never writes state.

```powershell
$env:AWS_PROFILE = "grain-readonly"
terraform plan -lock=false
```
