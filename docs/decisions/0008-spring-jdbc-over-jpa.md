# 0008 - Spring JDBC rather than JPA

**Status:** Accepted, 2026-09-23 (recorded 2026-09-24)
**Applies to:** every repository in the backend

## Context

The README's first Milestone 1 item asks for "JDBC or JPA (pick one and justify
it in an ADR)". The choice turns on what the database work actually is:

- **Ingest** is the hot path: a batch of `INSERT ... ON CONFLICT DO NOTHING`
  statements into a partitioned table, where the per-row update counts *are*
  the accepted/duplicate split returned to the device.
- **The dashboard reads** are `DISTINCT ON` and `date_trunc` aggregates over a
  time range, written to be checked with `EXPLAIN ANALYZE`.
- **Alert de-duplication** (Milestone 2) relies on partial unique indexes and
  `ON CONFLICT` targets chosen per alert type.
- The remaining entities, bins and devices, are small and simple.

## Decision

**Plain Spring JDBC.** `JdbcClient` for ordinary queries, `JdbcTemplate` for the
batched ingest insert. No Hibernate anywhere. The schema is owned by Flyway
alone.

## Alternatives

**Spring Data JPA (Hibernate).** Less boilerplate for bin and device CRUD, and
more familiar to many teams. Rejected because every part of this system that
matters would be native SQL anyway, and several things JPA would actively get in
the way of:

- `ON CONFLICT DO NOTHING` has no JPQL equivalent, and Hibernate does not
  surface per-row update counts -- the mechanism ingest is built on.
- Hibernate transparently disables JDBC insert batching for entities with
  `IDENTITY` ids, and `readings` has no surrogate id at all; its primary key is a
  five-column composite that exists to satisfy partitioning.
- A partial update is one `UPDATE ... SET x = COALESCE(?, x)` statement here.
  The JPA shape is load, merge, and flush.

The result would be two persistence models in one codebase -- entities for
CRUD, native SQL for everything interesting -- which is more to explain, not
less.

**Spring Data JDBC.** Lighter than JPA and aggregate-oriented, but it adds a
mapping model while still not expressing `ON CONFLICT` or returning per-row
counts. The same SQL would still be hand-written.

**JPA for CRUD, JDBC for ingest and queries.** Honest about where each fits, but
it is two models to keep consistent, and the CRUD it would save is small.

## Consequences

- Every query is visible in full, in the repository that runs it. ADR 0007
  measured the *exact* SQL the application executes, copied from the source, not
  a guess at what an ORM generates.
- No lazy loading, no N+1 queries, no dirty checking, no first-level cache to
  reason about.
- Row mapping is written by hand -- for example `BinRepository.toDetail`. This is
  the real cost, and it is paid once per query.
- There is no automatic optimistic locking. Nothing needs it yet; the only
  read-modify-write path, the threshold update, is a single statement.
- Renaming a column means updating SQL strings. The compiler will not catch it;
  the integration tests against real PostgreSQL will.
