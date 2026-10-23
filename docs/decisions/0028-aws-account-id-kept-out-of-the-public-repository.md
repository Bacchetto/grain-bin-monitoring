# 0028 - The AWS account ID is kept out of the public repository

**Status:** Accepted, 2026-10-01; workflow output 2026-10-22 (recorded 2026-10-23)
**Applies to:** `infra/terraform/versions.tf`, `backend.hcl.example`, `.github/workflows/plan.yml`, `deploy.yml`

## Context

The repository is public. AWS states that an account ID is not a secret: it
grants nothing by itself. But it is the first fact an attacker needs to probe
an account. It helps to enumerate which roles and resources exist, to test
resource policies that trust the account, and to make phishing convincing.
Keeping it private is cheap, as long as it is done consistently.

It turned up in three places:

- **The code:** the state bucket's name, `grain-bin-tfstate-<account>-<region>`,
  was written into the backend block, and so into the history.
- **Workflow logs,** which are public for a public repository. Terraform's
  plan and apply output and the ECR registry address are full of ARNs that
  contain the ID.
- **The plan comment** that `plan.yml` posts on pull requests.

## Decision

- **Not in tracked files.** The backend block has no bucket. It is supplied at
  `init`, from a git-ignored `backend.hcl` locally (`backend.hcl.example` shows
  the shape) and from a repository secret in the workflows. The history was
  rewritten to remove the ID from every past version, before the repository was
  made public.
- **Role ARNs and the bucket name are repository secrets, not variables.**
  GitHub masks a secret wherever it would be printed, including the list of
  inputs a step prints before it runs. A role ARN held in a variable appeared
  in full there on the first plan run, before any masking could start.
- **`mask-aws-account-id: true`** on `configure-aws-credentials` masks the ID
  in every later log line of both workflows.
- **The plan text is redacted before it is posted.** Masking applies to logs,
  not to text posted through the API, so `plan.yml` replaces the ID with
  `<ACCOUNT_ID>` in the plan before printing or posting it.

## Alternatives

**Accept it in public.** Defensible, since AWS doesn't treat it as a secret.
Rejected because the cost of keeping it private is a few lines of
configuration.

**A random suffix in the bucket name instead of the account ID.** That takes it
out of the backend block, but every ARN in plan output still contains it, so
the workflow measures would be needed anyway.

## Consequences

- **Six repository settings instead of three,** and more steps in the runbook.
  The workflows also need an `AWS_WORKFLOWS_ENABLED` variable, because a job's
  `if:` cannot read secrets.
- **Masking is best-effort.** It catches the ID wherever it is printed in a
  known form. A new step that prints it some other way would leak it, as the
  first run did before the role ARNs became secrets. That run's log was
  deleted. When a workflow changes, its first run's log is worth a check:
  `gh run view <id> --log` and a search for the ID.
- The ID still appears in places only the account's own users can see: the
  state, `terraform output`, and the AWS console.
