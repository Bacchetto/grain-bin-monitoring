# Results

> **Status: no measurements recorded yet.** Numbers land here as the
> milestones that produce them are completed. Every figure must carry the date
> and the environment it was measured in, because a number without those is
> not quotable.

## Container image size

*(Milestone 3.)* Target is under 250 MB. Record the actual size, the base
image tag, and the date.

| Date | Image tag | Size | Notes |
|---|---|---|---|
| — | — | — | Not yet built. |

## Load test: local

*(Milestone 4.)* From `load-tests/k6/ingest.js`.

| Metric | Value |
|---|---|
| Requests/sec | — |
| Readings/sec | — |
| p95 latency | — |
| p99 latency | — |
| Error rate | — |
| Duplicate detection correct | — |

Environment: —
Date: —

## Load test: AWS

*(Milestone 4.)* Same script against the deployed ALB.

| Metric | Value |
|---|---|
| Requests/sec | — |
| Readings/sec | — |
| p95 latency | — |
| p99 latency | — |
| Error rate | — |
| Duplicate detection correct | — |

Environment (instance sizes, task count): —
Date: —

## Methodology

*(Milestone 4.)* How each run was performed, so that anyone -- including a
author, months later -- can reproduce it exactly.

## Query plans

*(Milestone 1.)* `EXPLAIN ANALYZE` output for the `latest` and bucketed
`readings` queries, with the indexes they rely on.
