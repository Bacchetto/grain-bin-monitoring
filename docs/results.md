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

Measured 2026-09-24 on PostgreSQL 16 with 6.2 million readings across four
monthly partitions. Single warm runs, for plan shape and order of magnitude --
not load-test figures. Full method, plans and discussion:
[ADR 0007](decisions/0007-read-path-indexes-verified-with-explain-analyze.md).

| Query | Index used | Rows read | Time |
|---|---|---|---|
| `latest`, 7-day lookback | `readings_bin_recorded_idx` | 48,384 | 29 ms |
| `readings`, hourly, 24 h | `readings_bin_recorded_idx` | 6,912 | 6 ms |
| `readings`, daily, 30 days | `readings_bin_recorded_idx` | 207,360 | 93 ms |

The index originally built for `latest` was never used by it and added about 70%
to insert time (1,655 ms vs 971 ms for 345,600 rows), so `V5` dropped it.
