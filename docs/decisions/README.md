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

## Decisions already made, pending write-up

These were settled before implementation started and are recorded here so they
are not lost. Each becomes a numbered ADR during Milestone 1.

- **Spring JDBC over JPA/Hibernate.** The ingest hot path and the time-series
  aggregates are hand-written SQL either way; one persistence model is easier
  to explain than two.
- **Spring Boot 4.1.x over 3.5.x.** The spec says "latest stable".
- **Monthly range partitioning of `readings`.** Why one partition per month,
  and why there is no `samples` table.
- **Filter-based auth rather than Spring Security**, and the admin bearer-token
  tradeoff the README already flags as not production-grade.
- **SHA-256 for device API key hashing, not bcrypt.**
- **`bin_id` denormalised onto `readings`.**
- **Rancher Desktop as the local container runtime** (over Docker Desktop).
