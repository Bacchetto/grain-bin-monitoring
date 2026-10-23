# 0026 - Two OIDC roles, and an approval gate on every deploy

**Status:** Accepted, 2026-09-29; immutable subjects 2026-10-22 (recorded 2026-10-23)
**Applies to:** `infra/terraform/iam_github_oidc.tf`, `.github/workflows/plan.yml`, `deploy.yml`

## Context

GitHub Actions has to reach AWS twice: to run `terraform plan` on pull requests
that change the infrastructure, and to deploy after a merge to `main`. The spec
requires GitHub OIDC rather than stored access keys, with the role scoped to
this repository and the `main` branch.

Two constraints shaped the details. Until the pipeline is trusted, **nothing
changes in AWS without the owner's say-so**. And a plan, which only reads,
should not hold the permissions that an apply needs.

## Decision

**Two roles, each trusting one exact OIDC subject** (`StringEquals`, with no
wildcards):

| Role | Trusted subject | Permissions |
|---|---|---|
| `grain-bin-github-plan` | `...:pull_request` | `ReadOnlyAccess`, plus writing and deleting the state's lock object only |
| `grain-bin-github-deploy` | `...:environment:production` | `PowerUserAccess`, plus IAM only for this stack's own roles, `PassRole` only to ECS |

**Every deploy waits for approval.** `deploy.yml` runs its job in the GitHub
environment `production`. The environment has the owner as required reviewer
and accepts deployments from `main` only. Because the deploy role trusts only
tokens issued *for that environment*, the gate can't be skipped by editing a
workflow: a job outside the environment can't assume the role.

**Subjects use GitHub's immutable format,** which adds the owner's and the
repository's numeric IDs:
`repo:Bacchetto@51663220/grain-bin-monitoring@1400530202:pull_request`. This
repository was created after GitHub made that format the default for new
repositories. The first plan run showed it: the trust policies, written for
the name-only form, refused every request. Matching the immutable form is also
the stronger trust, because a repository created later under the same name has
a different ID.

## Alternatives

**Access keys stored as repository secrets.** Ruled out by the spec, and
long-lived keys are exactly what OIDC removes: nothing to leak, rotate, or
forget to revoke.

**One role for both workflows.** Simpler, but then every pull request's plan
would run with apply permissions.

**Trusting the `main` branch** (`...:ref:refs/heads/main`), as the spec words
it. Any workflow running on `main` could then deploy, with no approval step.
Trusting the `production` environment is narrower, and the environment's
branch rule still limits deploys to `main`. This is a deliberate deviation from
the spec's wording.

**Least-privilege policies listing every action the stack needs.** Tighter,
but long, brittle, and broken by every new resource type. `PowerUserAccess`
with IAM held back is contained by the approval gate instead.

## Consequences

- **No AWS credential exists in GitHub.** Each job gets temporary credentials,
  valid for an hour at most.
- **The plan role reads the whole account, not just this stack.**
  `ReadOnlyAccess` includes reading S3 objects, for example. Any branch in this
  repository with a pull request can obtain it. Acceptable because the account
  holds nothing but this stack and no secrets are in state (ADR 0024).
  Pull requests from forks get no OIDC token at all.
- **The deploy role is powerful,** and three things contain it: the approval
  gate, IAM scoped to this stack's own roles, and one-hour credentials.
- **Recreating the repository breaks the trust.** Its new ID has to go in
  `github_repository_id`, and the change has to be applied by hand, since no
  workflow can assume the roles until it is. A destroy also deletes the roles,
  so the first apply after one is always by hand (ADR 0027).
