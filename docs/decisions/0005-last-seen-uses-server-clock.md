# 0005 - `last_seen_at` records the server's clock, not the device's

**Status:** Accepted, 2026-09-24
**Applies to:** `ingest.IngestService`, `devices.last_seen_at`
**Supersedes:** the Milestone 1 plan's wording, which set it to "the max accepted `recorded_at`"

## Context

`devices.last_seen_at` has one job: the Milestone 2 `DEVICE_OFFLINE` alert fires
when

```
now - last_seen_at > 3 × expected_interval_seconds
```

The README fixes *when* it moves -- only after a successful insert, so a device
that sends nothing but duplicates or rejected data is still offline -- but not
*which clock* it records. There are two candidates:

- the device's clock: the latest `recorded_at` among the readings just stored
- the server's clock: the moment the server stored them

The original plan used the first. On inspection, it is wrong.

## Decision

`last_seen_at` is set to the **server's receive time**, taken once per request
from the injected `Clock`. The same instant is written to every row's
`received_at`, and is used for the five-minute future-timestamp cutoff, so all
three agree.

It only moves forward: the update is `GREATEST(last_seen_at, ?)`, so two batches
from one device committing in the opposite order to the one they started in
cannot move it backwards.

## Alternatives

**The device's clock (max accepted `recorded_at`).** Rejected, because the
offline check compares `last_seen_at` against the *server's* `now`. Mixing clocks
in one comparison makes the alert a measure of clock skew as much as of
liveness:

- A device whose clock runs 20 minutes slow, reporting every 5 minutes,
  permanently has `now - last_seen_at ≈ 20 min > 15 min` and is **always
  declared offline while it is reporting normally**.
- A device back-filling a buffered outage oldest-first sets `last_seen_at` to
  hours ago with each batch, and stays "offline" until it has caught up --
  exactly while it is demonstrably online.
- A device whose clock runs fast pushes `last_seen_at` into the future and
  cannot be declared offline until real time catches up.

A false offline alert on a device that is working is precisely the kind of
noise that teaches operators to ignore alerts.

**Both, in two columns.** A "latest reading recorded at" alongside "last heard
from". Reasonable, but nothing needs the first yet: data freshness is visible
from the `latest` endpoint, which reports actual `recorded_at` values. Deferred
until something does.

## Consequences

- The offline check compares two readings of the same clock, so device clock
  skew cannot cause or suppress `DEVICE_OFFLINE`.
- `GET /bins` reports `lastReadingAt` from this column. It therefore means "when
  the server last stored data for this bin", not "how old the newest reading
  is". For a working device those are within one reporting interval of each
  other; during a back-fill they differ, and the server's view is the one that
  answers "is it alive".
- A device with a wildly wrong clock is still correctly seen as online. Its
  readings may land in odd partitions or be rejected as future-dated, but
  that is a data-quality problem, reported through the ingest response, not a
  liveness one.

## See also

- `IngestIntegrationTest.LastSeen`, which pins each of these rules, including
  that a reading recorded three days ago still sets `last_seen_at` to now.
