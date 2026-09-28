# Architecture Decision Records

A short record of every non-obvious choice in this project: what the situation
was, what was decided, what else was considered, and what it costs us.

The point is not ceremony. This project exists to be explained out loud, and
"why did you do it that way?" is easier to answer well when the reasoning was
written down at the time rather than reconstructed months later.

## Format

One file per decision, named `NNNN-short-title.md`, numbered in the order they
are recorded. Numbers are never reused and files are never deleted --
a decision that gets reversed is superseded by a new ADR that says so, and the
old one stays as a record of what we believed then.

Each ADR has four sections:

| Section | Answers |
|---|---|
| **Context** | What forced a decision? What constraints applied? |
| **Decision** | What we chose, stated plainly. |
| **Alternatives** | What else was on the table, and why it lost. |
| **Consequences** | What this buys us, and what it costs. Include the bad. |

Keep them short. A page is plenty.

## Where ADRs get referenced from

An ADR that nobody finds at the moment they need it has not been recorded, only
filed. So each one is linked from the code whose author needs to know -- and
that is usually *not* the code the decision is about.

The no-foreign-key decision is a property of `readings`, but the person who
needs warning is whoever later writes a delete endpoint in `bins`, so that is
where the warning lives.

**Link from `package-info.java`, not from a migration.** Flyway checksums
applied migrations, and `validate-on-migrate` is on, so a comment inside a
migration cannot be corrected once it has been committed -- including to point
at an ADR that did not exist yet. Migration comments explain the SQL in front
of them; package docs carry anything that might need updating later.

## Index

| # | Decision | Applies to |
|---|---|---|
| [0001](0001-no-foreign-keys-on-readings.md) | No foreign keys on the `readings` table | `V2` |
| [0002](0002-device-scoped-offline-alert-dedupe.md) | `DEVICE_OFFLINE` alerts de-duplicate per device, not per bin | `V4` |
| [0003](0003-filter-based-auth.md) | Servlet filters for authentication, not Spring Security | `config` |
| [0004](0004-sha-256-for-device-api-keys.md) | SHA-256 for device API keys, not bcrypt | `devices` |
| [0005](0005-last-seen-uses-server-clock.md) | `last_seen_at` records the server's clock, not the device's | `ingest` |
| [0006](0006-reject-samples-older-than-a-configurable-age.md) | Reject samples older than a configurable age (default 30 days) | `ingest` |
| [0007](0007-read-path-indexes-verified-with-explain-analyze.md) | Read-path indexes, verified with `EXPLAIN ANALYZE`; unused sensor index dropped | `readings`, `V5` |
| [0008](0008-spring-jdbc-over-jpa.md) | Spring JDBC rather than JPA | all repositories |
| [0009](0009-spring-boot-4.md) | Spring Boot 4.1, not 3.5 | `pom.xml` |
| [0010](0010-monthly-range-partitioning.md) | Range-partition `readings` by month | `V2`, `readings` |
| [0011](0011-bin-id-denormalised-onto-readings.md) | Store `bin_id` on every reading | `V2`, `ingest` |
| [0012](0012-rancher-desktop-for-local-containers.md) | Rancher Desktop as the local container runtime | local development |
| [0013](0013-rate-of-rise-on-daily-averages.md) | Measure rate of rise on daily averages | `alerts` |
| [0014](0014-what-counts-as-an-alert-evaluation.md) | What counts as one alert evaluation | `alerts`, `ingest` |
| [0015](0015-advisory-lock-for-scheduled-alert-jobs.md) | One instance at a time for scheduled alert jobs, by advisory lock | `alerts`, `config` |
| [0016](0016-ignore-probe-fault-values-in-alert-evaluation.md) | Keep probe fault values out of alert evaluation | `alerts` |
| [0017](0017-cors-filter-before-authentication.md) | A CORS filter ahead of the authentication filters | `config` |
| [0018](0018-dashboard-stack-and-in-memory-token.md) | Dashboard stack, and a token held in memory only | `frontend` |
| [0019](0019-structured-json-logging.md) | Structured JSON logging, built into Spring Boot | logging |
| [0020](0020-time-scale-replays-the-past.md) | The simulator's `--time-scale` replays the past | `simulator` |
| [0021](0021-local-tooling-on-a-network-drive.md) | Local tooling that works from a network drive: poll, don't watch; build, don't mount | `frontend`, `ops`, Compose |
