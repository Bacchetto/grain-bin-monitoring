# 0001 - No foreign keys on the readings table

**Status:** Accepted, 2026-09-23
**Applies to:** `V2__readings_partitioned.sql`

## Context

`readings` is the only table in this system that grows without bound, and
`POST /api/v1/readings` is the only endpoint expected to sustain load. A batch
may carry up to 500 samples, and a sample carries one row per sensor, so a
single maximum-size request can insert on the order of 12,000 rows.

The natural modelling choice is a foreign key from `readings.device_id` to
`devices.id`, and from `readings.bin_id` to `bins.id`. The question is whether
that referential integrity is worth what it costs on this particular path.

## Decision

**`readings` has no foreign keys.** `device_id` and `bin_id` are plain
`BIGINT NOT NULL` columns.

The safety argument is that `device_id` is never supplied by a caller. A device
authenticates with its API key, the server resolves the key hash to a device
row, and `device_id` and `bin_id` are taken from that row. A client cannot
write readings attributed to a device or bin it does not own, because it never
names one.

## Alternatives

**Keep the foreign keys.** Measured on PostgreSQL 16, inserting 300,000 rows
into otherwise-identical partitioned tables:

| | Time | Per row |
|---|---|---|
| No FK | 666 ms | — |
| FK to `devices(id)` | 4,449 ms | **+12.6 µs** |

A foreign key is not a one-time check. PostgreSQL fires an `AFTER ROW` trigger
per inserted row which takes `SELECT ... FOR KEY SHARE` on the parent row, so
every reading costs an index probe plus row-lock bookkeeping. At 12.6 µs/row a
maximum-size batch would carry roughly **150 ms of pure foreign-key overhead**.

Two caveats on that figure, recorded so it is not over-claimed. It was measured
with a server-side `INSERT ... SELECT`, with no network in the loop, so the
relative penalty shrinks once JDBC round-trips and JSON parsing are included;
treat 6.7x as an upper bound on the effect rather than an end-to-end
prediction. It was also measured single-session, so it does *not* include
contention between concurrent transactions taking share locks on the same
device row, which would make matters worse rather than better.

**Foreign key to `bins` only.** Half the cost for a fraction of the benefit;
`bin_id` is derived from the same authenticated device row as `device_id`, so
if one can be trusted so can the other.

## Consequences

### What this buys

Roughly a 6.7x faster insert path under the measured conditions, no row locks
taken on `devices` during ingest, and no lock contention on a hot parent row
when many devices report concurrently.

### What it costs, and what future work must account for

These are the standing constraints created by this decision. **Any future
change touching deletion, data repair, or bulk loading must handle them
explicitly, because the database will not.**

1. **Orphaned readings are possible and will be silent.**
   `bins -> devices` cascades on delete, but nothing cascades to `readings`.
   Deleting a bin or a device leaves its readings in place permanently: they
   consume storage, are counted by any aggregate that does not filter, and no
   constraint will ever flag them.

   There is no `DELETE` endpoint in the API today, so this is currently
   unreachable through normal use. **If one is ever added, it must delete the
   corresponding readings explicitly in application code, inside the same
   transaction.** Note that this is itself an expensive operation across a
   partitioned table, which should be weighed when designing any such endpoint.

2. **There is no database-level backstop against a bogus `device_id`.**
   The claim that a bad `device_id` cannot be written is a claim about the
   correctness of application code, not a guarantee enforced by the schema. A
   bug in the ingest path, a careless manual `INSERT`, a data-repair script, or
   a future bulk-import path could write readings referencing a device that
   does not exist, and the write would succeed.

   **Anything that writes to `readings` outside the authenticated ingest path
   must validate `device_id` and `bin_id` itself.**

3. **Reconciliation is a manual job.** Detecting orphans requires an explicit
   anti-join, for example:

   ```sql
   SELECT r.bin_id, count(*)
   FROM readings r
   LEFT JOIN devices d ON d.id = r.device_id
   WHERE d.id IS NULL
   GROUP BY r.bin_id;
   ```

   Nothing runs this today. If orphans ever become a real concern, this belongs
   in a scheduled check that reports a metric rather than in a constraint.

### Reversing this decision gets harder over time

The usual online escape hatch is not available. Verified on PostgreSQL 16:

```
ERROR:  cannot add NOT VALID foreign key on partitioned table "readings"
DETAIL:  This feature is not yet supported on partitioned tables.
```

`ADD CONSTRAINT ... NOT VALID`, the standard way to add a foreign key without a
blocking validation scan, **does not work on partitioned tables**. The only
available path is a fully validated `ADD CONSTRAINT`, which takes
`ShareRowExclusiveLock` on the parent table *and every partition*. Reads
continue; **all writes block** for the duration of the scan.

That scan was 27 ms over 300,000 rows, because validation runs as a single
anti-join rather than per-row triggers. But it scales with total row count. At
a few hundred million rows it means stopping ingest for minutes, which in
practice means a maintenance window.

**This decision is cheap now and progressively more expensive to unmake.** It
should be revisited deliberately -- with the load-test numbers from Milestone 4
in hand -- rather than drifted into.

## See also

- [0002 - DEVICE_OFFLINE alerts de-duplicate per device, not per bin](0002-device-scoped-offline-alert-dedupe.md)
  for the other place where the schema deliberately departs from the obvious
  modelling.
