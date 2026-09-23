# Architecture Decision Records

A short record of every non-obvious choice in this project: what the situation
was, what was decided, what else was considered, and what it costs us.

The point is not ceremony. This project exists to be explained out loud, and
"why did you do it that way?" is easier to answer well when the reasoning was
written down at the time rather than reconstructed months later.

## Format

One file per decision, named `NNNN-short-title.md`, numbered in the order
decisions were made. Numbers are never reused and files are never deleted --
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

## Index

*(Empty. ADRs are written as the decisions they describe are implemented.)*

## Decisions already made, pending write-up

These were settled before implementation started and are recorded here so they
are not lost. Each becomes a numbered ADR during Milestone 1.

- **Spring JDBC over JPA/Hibernate.** The ingest hot path and the time-series
  aggregates are hand-written SQL either way; one persistence model is easier
  to explain than two.
- **Spring Boot 4.1.x over 3.5.x.** The spec says "latest stable".
- **Monthly range partitioning of `readings`**, including why there is no
  foreign key from `readings` to `devices`.
- **Filter-based auth rather than Spring Security**, and the admin bearer-token
  tradeoff the README already flags as not production-grade.
- **SHA-256 for device API key hashing, not bcrypt.**
- **`bin_id` denormalised onto `readings`.**
- **Rancher Desktop as the local container runtime** (over Docker Desktop).
