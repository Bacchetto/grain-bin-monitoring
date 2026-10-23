# 0024 - Secrets kept out of Terraform state

**Status:** Accepted, 2026-09-29 (recorded 2026-10-23)
**Applies to:** `infra/terraform/secrets.tf`, `rds.tf`, `ecs.tf`

## Context

The API needs two secrets in AWS: the database password, and `ADMIN_TOKEN`, the
bearer token for the admin and dashboard endpoints. The spec puts both in
Secrets Manager.

Terraform records every attribute of every resource it manages in its **state
file**, in plain text, and a saved plan file carries the same values. Marking an
attribute `sensitive` only hides it from terminal output. The state lives in an
encrypted, private S3 bucket. But the CI plan role can read it, and so can
anyone who can read the bucket, and old versions are kept for 90 days. So a
secret that Terraform generates or passes in the ordinary way is a secret in
the state as well.

## Decision

**Neither secret is ever known to Terraform.**

- **The database password is managed by RDS** (`manage_master_user_password =
  true`). RDS generates it, stores it in a Secrets Manager secret it owns, and
  can rotate it. Terraform only knows the secret's ARN.
- **The admin token** comes from an **ephemeral** `random_password` and is
  written with **`secret_string_wo`**, a write-only argument. An ephemeral
  value exists only while Terraform runs and is never saved to state or a plan.
  A write-only argument is sent to AWS and not recorded. To rotate the token,
  bump `admin_token_version` and apply; the version number is what Terraform
  stores.
- **The ECS task definition references both secrets by ARN** (`valueFrom`), and
  ECS injects them as environment variables when a task starts. The execution
  role may read exactly those two secrets.

## Alternatives

**A regular `random_password` resource.** The usual pattern, and the first
draft of this stack. The value then sits in the state file, readable by the
plan role and anyone with access to the bucket's object versions.

**A secret created by hand in the console.** Keeps it out of state, but adds a
manual step to every recreation, and a destroy-and-recreate cycle is the
normal way this stack is run.

**Secrets Manager's own generation and rotation** (a rotation Lambda). It works,
but it is a function to build and maintain for a single static token.

## Consequences

- The state contains no secret values, so the read-only plan role can be
  trusted with it. That is also why the plan role needs no access to secret
  values at all. A read-only plan refreshes the write-only secret version
  without reading it, as the first deploy confirmed.
- **Nothing in Terraform can print the token.** It is read from Secrets
  Manager with the admin profile when it is needed (the runbook shows the
  command), and the read-only role cannot read it, by design.
- **A rotated secret takes effect only when a task starts,** because ECS
  injects secrets at startup. `deploy.yml` forces a new deployment on every
  run, so the next deploy picks it up.
- The secret is deleted immediately on destroy (`recovery_window_in_days = 0`).
  Otherwise the default 7-30 day recovery window would block recreating a
  secret with the same name.
- Write-only arguments and ephemeral resources need a recent Terraform (1.11
  or later, pinned at 1.16 here) and AWS provider.
