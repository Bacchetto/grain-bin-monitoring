# 0006 - Reject samples older than a configurable age

**Status:** Accepted, 2026-09-24
**Applies to:** `ingest.IngestService`, `app.ingest.max-sample-age`
**Extends:** the README's ingest rules, which define only a future-timestamp rejection

## Context

The README rejects samples recorded more than five minutes in the future and
says nothing about the past. Ingest therefore accepted any `recordedAt`, however
old, and created a partition for its month on demand.

That is right for the case it was meant for -- a device back-filling readings it
buffered during an outage -- but it has no floor. An authenticated device whose
real-time clock resets to the epoch, a common failure after a battery or power
fault, would have its readings accepted and cause a `readings_1970_01` partition
to be created. More generally, any authenticated device could cause DDL for an
arbitrary past month, and the data it wrote would be nonsense filed as history.

## Decision

Samples recorded longer ago than `app.ingest.max-sample-age`, measured against
the server's clock, are counted as **rejected**, exactly like future-dated ones:
the rest of the batch continues, and they appear in the response's `rejected`
count.

The default is **30 days**, chosen by the project owner. It is configurable, and
can be overridden at deploy time through the `APP_INGEST_MAX_SAMPLE_AGE`
environment variable. It must be at least a day.

## Alternatives

**No limit.** The previous behaviour. Rejected for the reasons above.

**Reject the whole batch.** Inconsistent with the future-timestamp rule, and it
would stop a device's valid recent readings from arriving because one old one
was mixed in.

**Detect clock faults per device instead of applying a fixed floor.** Better in
principle -- a device whose readings suddenly jump by decades has a clock fault
whatever the threshold -- but a larger piece of work. Recorded in
[`docs/enhancements.md`](../enhancements.md) as a follow-on. The fixed floor is
what bounds the damage in the meantime.

## Consequences

- A device offline for more than 30 days loses the part of its back-fill older
  than that. It is told so -- those readings come back as `rejected`, not as
  errors -- but the data is not stored. Deployments where long outages are
  expected should raise the limit.
- **Under the default, the on-demand partition path becomes a backstop that
  normal traffic never reaches.** Every sample inside a 30-day window falls in
  the current or previous month; the migration seeds the previous month and the
  scheduled job keeps the current month and three ahead. The on-demand call
  still matters if the scheduler fails, or if the limit is raised. The web
  integration tests run with a one-year limit so that path stays tested end to
  end, and a separate test pins the 30-day default.
- The limit caps which partitions an authenticated device can cause to exist,
  which is the property that matters most here.
