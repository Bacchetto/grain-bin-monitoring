# 0011 - Store `bin_id` on every reading

**Status:** Accepted, 2026-09-23 (recorded 2026-09-24)
**Applies to:** `V2__readings_partitioned.sql`, `ingest.IngestService`

## Context

A reading belongs to a device, and a device belongs to a bin, so the bin a
reading came from is derivable from `devices`. But every dashboard query is
asked by bin -- the latest value per sensor in a bin, the history for a bin --
and never by device.

## Decision

`readings` carries `bin_id` as well as `device_id`. It is always copied from the
authenticated device's row at ingest, never taken from the request.

## Alternatives

**Derive the bin at read time.** Keep only `device_id` and have each query find
the bin's devices first:
`WHERE device_id IN (SELECT id FROM devices WHERE bin_id = ?)`. This works, but
it puts a subquery or join in front of every dashboard read, and the index
serving those reads would have to be keyed on device rather than bin. With
several devices on one bin -- which the schema allows -- one bin-keyed index
range becomes several device-keyed ones.

## Consequences

- The read index is simply `(bin_id, recorded_at)`, which the history and latest
  queries both use directly. It is also unusually small -- 22 MB per partition
  against 129 MB for the primary key -- because all the sensors of a bin share
  each key value and B-tree deduplication stores it once (ADR 0007).
- About 8 more bytes per row: roughly 20 MB a year per device at the reporting
  rate in ADR 0010.
- **Moving a device to a different bin does not rewrite its history.** Past
  readings keep the bin they were actually taken in, which is arguably the
  correct behaviour rather than a cost.
- Like `device_id`, it has no foreign key (ADR 0001). Its correctness rests on
  ingest taking it from the authenticated device row, which is stated on the
  `devices` package.
