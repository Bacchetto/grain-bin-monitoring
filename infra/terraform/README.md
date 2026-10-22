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

**Destroy it when not demoing** (spec: cost discipline). An hour of demo
costs a few cents; a forgotten month costs the table above.

## Bringing it up (the owner does this)

The same steps work the first time and every time after a destroy. A destroy
removes the OIDC roles along with everything else, so no workflow can bring the
stack back: the first apply is always by hand.

Run them from the repository root in PowerShell, in a **new window** signed in
with the admin session. When you're done, close the window rather than running
`aws sso logout`: logout signs out *every* SSO session on the machine,
including the read-only one, while a closed window just lets the admin token
expire. `terraform.tfvars` and `backend.hcl` are git-ignored. Copy them from the
examples the first time.

```powershell
aws sso login --sso-session grain-admin
$env:AWS_PROFILE = "grain-admin"
$env:TF_DATA_DIR = "$env:LOCALAPPDATA\grain-bin-terraform\main"   # see ADR 0021
cd infra\terraform
copy terraform.tfvars.example terraform.tfvars                        # first time: then edit alert_email
copy backend.hcl.example backend.hcl                                  # first time: then put in the account ID
terraform init "-backend-config=backend.hcl"   # quoted: PowerShell splits it at the dot otherwise

# 1. The budget first, so the alert exists before anything costs money.
terraform plan -target="aws_budgets_budget.monthly" -out budget.tfplan
terraform apply budget.tfplan

# 2. Everything else, with no API task yet: the image registry is created by
#    this same apply, so there is no image for a task to start from.
terraform plan -var desired_count=0 -out main.tfplan      # read it: 70 to add after the budget
terraform apply main.tfplan                               # 15-25 minutes: RDS and CloudFront are slow
```

Then:

1. **Confirm the SNS subscription email** that AWS sends to `alert_email`, or
   alarms will not be delivered. It comes from `no-reply@sns.amazonaws.com`.
2. **Switch the workflows on:** `gh variable set AWS_WORKFLOWS_ENABLED --body true`.
   The secrets survive a destroy. Update them only if a value changed: the role
   ARNs keep their names, and the state bucket is the bootstrap's.
3. **Deploy:** merge anything to `main`, or re-run the latest Deploy run, and
   approve it. That builds and pushes the image, applies with one task, and
   publishes the dashboard. The CloudFront address is new each time the stack is
   created; `terraform output -raw site_url` prints it.

**To see the admin token** (to sign in to the dashboard, or for
`sim.py --admin-token`). The read-only role cannot read secret values, on
purpose:

```powershell
aws secretsmanager get-secret-value --secret-id grain-bin/admin-token --query SecretString --output text --profile grain-admin
```

## Tearing it down

```powershell
terraform plan -destroy -out destroy.tfplan    # with the same environment variables as above
terraform apply destroy.tfplan                 # 15-20 minutes; CloudFront is the slow part
gh variable set AWS_WORKFLOWS_ENABLED --body false
```

Everything goes, including the budget and the OIDC roles: ECR and the dashboard
bucket are emptied first (`force_delete`, `force_destroy`), RDS keeps no final
snapshot, and the admin-token secret is deleted immediately, so the stack can
be recreated under the same names straight away. The state bucket (bootstrap)
is not affected. Switching the workflows off stops them failing for want of the
roles.

## Deploy history

| Date | Commit | What happened |
|---|---|---|
| 2026-10-22 | `431e8ca` | **First deploy.** The main stack was applied by hand with no task (71 resources), then `deploy.yml` ran after approval in about 4 minutes. One task became healthy; Flyway applied V1-V5 to the empty database. Checks: the dashboard and deep links served, `/api/v1/bins` returned 401 without a token, and `/actuator/prometheus` was not reachable. The simulator seeded 3 bins and ran `normal`, `hotspot` and `offline` against the CloudFront URL, and `DEVICE_OFFLINE`, its auto-resolve, and `RATE_OF_RISE` all fired as designed. About 100 requests, 0 server errors, p50 12 ms and p95 192 ms at the load balancer. Destroyed the same evening. Found and fixed on the way, in #19: the trust policies now match GitHub's immutable OIDC subjects, and the account ID is kept out of workflow logs and plan comments. |

## Deploying through GitHub Actions

After the first apply, two workflows take over:

| Workflow | Runs on | Does |
|---|---|---|
| `plan.yml` | PRs that change `infra/terraform/` | `terraform plan` with the read-only role; posts the result as a PR comment |
| `deploy.yml` | CI passing on `main` | **After the owner approves:** build and push the image, `terraform apply`, roll ECS, publish the dashboard, invalidate CloudFront |

They authenticate with GitHub OIDC -- no AWS keys are stored in GitHub. They
need six settings in the repository, made once after the first apply (the
role ARNs come from `terraform output`):

| Setting | Kind | Value |
|---|---|---|
| `AWS_PLAN_ROLE_ARN` | Secret | `terraform output -raw github_plan_role_arn` |
| `AWS_DEPLOY_ROLE_ARN` | Secret | `terraform output -raw github_deploy_role_arn` |
| `TF_STATE_BUCKET` | Secret | the state bucket's name, as in `backend.hcl` |
| `ALERT_EMAIL` | Secret | the same address as `alert_email` in `terraform.tfvars` |
| `AWS_WORKFLOWS_ENABLED` | Variable | `true` |
| `production` | Environment | **Required reviewers: the owner.** This is the approval gate |

**Why secrets, when role ARNs aren't sensitive?** They contain the account ID,
which this public repository keeps out of view, and workflow logs are public.
GitHub masks secrets everywhere they would be printed, including the inputs a
step lists before it runs, which is where a role ARN in a variable showed up in
full. `AWS_WORKFLOWS_ENABLED` exists because a job's `if:` cannot read secrets.

With the GitHub CLI, from this directory:

```powershell
gh secret set AWS_PLAN_ROLE_ARN   --body (terraform output -raw github_plan_role_arn)
gh secret set AWS_DEPLOY_ROLE_ARN --body (terraform output -raw github_deploy_role_arn)
gh secret set TF_STATE_BUCKET     --body grain-bin-tfstate-<ACCOUNT_ID>-ca-central-1
gh secret set ALERT_EMAIL                          # prompts for the value
gh variable set AWS_WORKFLOWS_ENABLED --body true
```

The `production` environment is easiest in the browser: **Settings →
Environments → New environment → `production` → Required reviewers**, add
yourself, and restrict **Deployment branches** to `main`.

Until `AWS_WORKFLOWS_ENABLED` is `true`, both workflows skip themselves, so
merging them before the stack exists is harmless.

**The roles trust GitHub's immutable subjects.** This repository's tokens name
it with numeric IDs as well as names
(`repo:Bacchetto@51663220/grain-bin-monitoring@1400530202:...`), and the trust
policies in `iam_github_oidc.tf` match that exactly. If the repository is ever
recreated, its new ID goes in `github_repository_id` (from
`gh api repos/Bacchetto/grain-bin-monitoring --jq .id`) and the change is
applied by hand, since no workflow can assume the roles until it is.

**A deploy always runs one task** (`desired_count=1`), which is also how the
service starts after the first apply's `desired_count=0`.

## Planning without applying

A read-only role can plan but cannot take the state lock, so read-only plans
pass `-lock=false`. Safe: a plan never writes state.

```powershell
$env:AWS_PROFILE = "grain-readonly"
terraform plan -lock=false
```
