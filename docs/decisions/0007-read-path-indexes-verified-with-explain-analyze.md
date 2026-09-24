# 0007 - Read-path indexes, verified with EXPLAIN ANALYZE

**Status:** Accepted, 2026-09-24
**Applies to:** `readings.ReadingQueryRepository`, `V2__readings_partitioned.sql`, `V5__drop_unused_sensor_recent_index.sql`

## Context

The README asks for indexes to match the API's query patterns, and for those
indexes to be verified with `EXPLAIN ANALYZE`, with the results noted here.

`V2` created two indexes on `readings` beyond the primary key, one per read
query:

| Index | Intended for |
|---|---|
| `readings_bin_recorded_idx (bin_id, recorded_at)` | `GET /bins/{id}/readings` -- a date range for the whole bin |
| `readings_bin_sensor_recent_idx (bin_id, cable_index, depth_index, recorded_at DESC)` | `GET /bins/{id}/latest` -- newest row per sensor |

## Method

- PostgreSQL 16 in a throwaway container, with migrations `V1`-`V4` applied.
- **6.2 million readings**: 10 bins, one device each, 24 sensors (4 cables × 6
  depths) reporting every 5 minutes for 90 days, spread over four monthly
  partitions. `ANALYZE` run after loading.
- The exact SQL from `ReadingQueryRepository`, for one bin. Timestamps were
  literal values rather than parameters, which matches the custom plans the
  PostgreSQL JDBC driver uses for a statement's first executions.
- Each query was run once to warm the cache, then measured with
  `EXPLAIN (ANALYZE, BUFFERS)`.

These are single runs on a laptop, useful for plan shape and order of
magnitude. They are **not** load-test figures; Milestone 4 measures under load.

## Results

| Query | Plan | Rows read | Time |
|---|---|---|---|
| `latest`, 7-day lookback | Bitmap scan of `readings_bin_recorded_idx` → sort → unique | 48,384 | **29 ms** |
| `readings`, hourly, 24 h | Bitmap scan of `readings_bin_recorded_idx` → sort → group | 6,912 | **6 ms** |
| `readings`, daily, 30 days | Parallel bitmap scan of `readings_bin_recorded_idx` over 2 partitions → sort → group | 207,360 | **93 ms** |

### What the plans show

**Partition pruning works.** Every query opened only the partitions overlapping
its time range. The 24-hour query touched one partition out of seven; the older
months never appeared in any plan.

**The history query uses its index as designed.** `readings_bin_recorded_idx`
is also far smaller than the others -- 22 MB per partition against 129 MB for
the primary key -- because of B-tree deduplication: the 24 sensors of a bin
share each `(bin_id, recorded_at)` value, so each key is stored once with a list
of rows.

**`latest` never used the index built for it.** `DISTINCT ON` has to read every
row in the lookback window, whichever index supplies them. A bitmap scan of the
smaller index followed by an in-memory sort of 48,000 rows was cheaper than an
ordered scan of the larger one, so the planner chose it every time.

**That unused index was expensive.** Measured separately:

| | With `readings_bin_sensor_recent_idx` | Without |
|---|---|---|
| Insert 345,600 rows | 1,655 ms | **971 ms** |
| Size per monthly partition | 64 MB | -- |

It added roughly 70% to insert time on the one path that has to sustain load,
and about 44% on top of the table's own 146 MB per partition, in exchange for
nothing.

Two smaller observations, noted but not acted on:

- The 30-day daily query's sort **spilled to disk** (external merge, about 7 MB)
  at PostgreSQL's default `work_mem` of 4 MB, which is also RDS's default.
  93 ms is acceptable for a dashboard call, so it is left alone, but it is the
  first thing to look at if the Milestone 4 load tests show this query as slow.
- `latest` has no upper time bound, so it also opens the empty future partitions
  the scheduler creates ahead of time. Each costs microseconds because they are
  empty. Bounding the query at "now plus five minutes" would prune them, but the
  saving is too small to be worth the extra condition.

## Decision

- **Drop `readings_bin_sensor_recent_idx`** (`V5`).
- Keep both queries as written. `latest` stays a plain `DISTINCT ON`.

This was chosen by the project owner from the measurements above.

## Alternatives

**Keep the index and rewrite `latest` to use it.** A recursive "loose index
scan" -- find the first sensor position, then repeatedly the next one after it,
then the newest reading for each -- makes 24 index probes instead of reading a
week of rows. Measured at **about 1 ms** to execute plus 4 ms to plan, and it
stays fast however often sensors report. Rejected because it keeps the full
insert and storage cost of the index on the hot path to speed up a dashboard
call that is already fast enough, and the SQL is considerably harder to read and
explain.

**Maintain a latest-value table on ingest.** One row per sensor position,
upserted with every batch; `latest` becomes a trivial read. Also rejected for
now: it moves the cost onto ingest as an extra write per sensor per batch.

Both are recorded as enhancement E13, for use if bins ever become dense enough
for the simple query to be slow.

## Consequences

- Ingest maintains two indexes on `readings` instead of three.
- The cost of `latest` grows with **sensors × readings per lookback window**.
  At 24 sensors reporting every 5 minutes that is 48,384 rows and 29 ms. A bin
  with 200 sensors reporting every minute would read about 2 million rows --
  roughly 42 times as many -- and, since sorting grows slightly faster than
  linearly, take well over a second. That is the point at which E13 becomes
  worth doing.
- `V2`'s comment still describes two indexes for two query shapes. Applied
  migrations cannot be edited, so `V5` carries the correction.
