# 0027 - The budget first, then a first apply with no task

**Status:** Accepted, 2026-09-29 (recorded 2026-10-23)
**Applies to:** `infra/terraform/budgets.tf`, `variables.tf` (`desired_count`, `image_tag`), `infra/terraform/README.md`, `.github/workflows/plan.yml`

## Context

Two ordering problems come with bringing the stack up from nothing.

**Money first.** The spec says to create the budget alert *first*, so that an
alert exists before anything can cost money. But `budgets.tf` is part of the
main stack, and a plain `terraform apply` creates everything at once.

**The image comes after the registry.** The ECS service needs an image in ECR
to start a task, and ECR is created by the same apply. A service asked to run a
task with no image to pull keeps failing, and the apply waits on it.

## Decision

The stack is brought up in three steps, by hand, the first time and after
every destroy:

1. **`terraform apply -target=aws_budgets_budget.monthly`**: the budget alone,
   with email alerts at 80 % of actual and 100 % of forecast spend.
2. **`terraform apply -var desired_count=0`**: everything else, with the
   service defined but running no tasks.
3. **An approved `deploy.yml` run**: it builds and pushes the image, then
   applies with `image_tag` set to the commit SHA and `desired_count=1`.

`desired_count` and `image_tag` are variables, not fixed values, because they
belong to the deploy rather than to the code. So that a pull request's plan
compares the code with what is actually running, `plan.yml` reads both from
the live ECS service and passes them in. Otherwise every plan would show the
service "changing" back to the defaults.

## Alternatives

**The budget in the bootstrap config.** It would exist before the main stack
and survive its destroys. But the spec places `budgets.tf` in the main stack,
and the console budgets created with the account already cover the time when
no stack exists.

**ECR in the bootstrap config,** so an image can be pushed before the main
stack exists. That removes the `desired_count=0` step, but it leaves images, and
their storage cost, behind after every destroy, and splits one application's
resources across two configurations.

**A placeholder public image** for the first apply. The task would start, but
with the wrong application, and fail health checks or serve nothing useful
until the first real deploy replaced it.

## Consequences

- The budget exists before anything that costs money. It is also destroyed
  with the stack, so a console zero-spend budget is the backstop between
  demos.
- **The first apply is always by hand.** A destroy removes the OIDC roles too
  (ADR 0026), so no workflow can bring the stack back. The runbook in
  `infra/terraform/README.md` covers it, and the first deploy followed it on
  2026-10-22.
- **Planned against reality:** `plan.yml` reading the live count and tag means
  a pull request's plan shows only what the change itself would do.
