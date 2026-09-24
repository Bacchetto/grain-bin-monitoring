# 0010 - Range-partition `readings` by month

**Status:** Accepted, 2026-09-23 (recorded 2026-09-24)
**Applies to:** `V2__readings_partitioned.sql`, `readings.PartitionMaintenanceService`

## Context

`readings` is the only table that grows without bound. One device with 24
sensors reporting every five minutes writes about **2.5 million rows a year**
(24 × 288 × 365), and every other table stays small by comparison.

Every read the dashboard makes is bounded in time -- the latest week, or a
chosen range -- and old data will eventually need removing. The README asks for
native range partitioning by `recorded_at`, one partition per month, and says an
insert must never fail because a partition is missing.

## Decision

- `readings` is `PARTITION BY RANGE (recorded_at)`, **one partition per calendar
  month**, with bounds written as explicit UTC literals.
- Partitions are created by an idempotent SQL function, called by a scheduled job
  that works ahead and, as a backstop, by the ingest path before each batch.
- There is **no default partition**.
- There is **no separate `samples` table**. `seq` and `recorded_at` are repeated
  on every reading row.

## Alternatives

**No partitioning.** Perfectly adequate at small scale. But removing old data
would mean a `DELETE` across a very large table, which is slow and leaves it
bloated, while the indexes grow forever. Partitioning makes retention a matter
of dropping a partition.

**Smaller partitions -- daily or weekly.** Tighter pruning, but hundreds of
partitions a year, and planning time grows with the number of partitions a query
has to consider. Monthly already confines every measured query to one or two
partitions (ADR 0007).

**Larger partitions -- yearly.** Too coarse: a 30-day query would still open a
whole year.

**Hash partitioning by device.** Spreads writes, but helps neither time-range
queries nor retention, which are the two reasons to partition here.

**TimescaleDB.** Purpose-built for exactly this, with automatic chunking and
retention. Rejected because the production target is Amazon RDS for PostgreSQL,
which does not offer the TimescaleDB extension, and native partitioning is the
skill the project sets out to demonstrate.

**A `samples` table** holding `seq` and `recorded_at`, with readings referencing
it. More normalised, but it costs a second insert per sample and a join on every
read, to save a few bytes per row.

## Consequences

- **`recorded_at` is part of the primary key**, because PostgreSQL requires the
  partition key in any unique constraint on a partitioned table. It is harmless:
  a duplicate of a reading always carries the same `recorded_at`.
- **A device's clock chooses the partition**, since `recorded_at` is the device's
  time. That is why ingest bounds it on both sides -- five minutes into the
  future, 30 days into the past (ADR 0006) -- which also caps which partitions a
  device can cause to exist.
- **Partition bounds must be UTC literals.** A bare date is cast using the
  session's time zone and would start every month at 06:00 UTC on a server set
  to Edmonton. Verified against such a server.
- **Partitions must exist before inserts arrive.** That is the job of
  `PartitionMaintenanceService`, including its cache, which may only record a
  month after the DDL creating it has committed.
- **Some things cannot be done online.** A `NOT VALID` foreign key cannot be added
  to a partitioned table in PostgreSQL 16 (ADR 0001), and an index cannot be
  dropped concurrently from one (V5).
- Pruning was confirmed with `EXPLAIN ANALYZE` on 6.2 million rows (ADR 0007).
