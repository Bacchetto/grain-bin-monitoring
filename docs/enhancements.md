# Possible enhancements

Ideas that are worth doing but deliberately not done yet. Each entry says where
it came from, what happens today, and what the change would involve, so it can
be picked up later without rediscovering the reasoning.

An entry here is not a commitment. When one is taken on, it moves into a
milestone; if it is rejected, it stays here marked as such, with the reason.

| # | Enhancement | Area | Status |
|---|---|---|---|
| E1 | [Reject invalid readings individually instead of failing the batch](#e1) | Ingest | Proposed |
| E2 | [Detect device clock faults from `recordedAt` behaviour](#e2) | Ingest, alerts | Proposed |

---

<a id="e1"></a>
## E1 - Reject invalid readings individually instead of failing the batch

**Raised:** 2026-09-24, Milestone 1 Phase 7

**Today.** A structurally invalid reading -- a missing field, or a value its
column cannot store -- fails the *whole* batch with `400`. Nothing is stored, and
the response names the offending reading by path, for example
`samples[3].sensors[0].temperatureC`. Only future-dated and too-old samples are
rejected individually.

**The problem.** A device whose firmware produces one malformed reading per batch
will retry that same batch forever and never deliver anything, including the
readings that were fine. From the outside it looks like a device that is online
but sends no data.

**The idea.** Treat an invalid reading like an out-of-window one: count it as
`rejected`, store the rest of the batch, and still report which readings were
rejected and why, so the fault stays visible rather than silently dropped.

**Trade-offs to settle first.**

- Partial acceptance of malformed input can hide a firmware bug that strict
  rejection would force someone to fix. A metric or alert on the rejection rate
  would need to come with it.
- The response would need a way to say *why* each reading was rejected, since
  `rejected` alone would mix clock problems with malformed data.
- It widens the spec's rejection rules, so the README would change too.

**Decision so far.** Keep the strict behaviour for now.

---

<a id="e2"></a>
## E2 - Detect device clock faults from `recordedAt` behaviour

**Raised:** 2026-09-24, Milestone 1 Phase 7, alongside
[ADR 0006](decisions/0006-reject-samples-older-than-a-configurable-age.md)

**Today.** A fixed window: samples more than 5 minutes in the future or more than
30 days old are rejected. That bounds the damage a bad clock can do, but it is
blunt. It says nothing about a clock that is wrong *within* the window, like a
device that drifts two hours slow, and it cannot tell a real back-fill from a
clock that has jumped.

**The idea.** Watch how each device's `recordedAt` behaves over time and flag a
device whose clock appears to have faulted: a sudden jump, a reset to the epoch,
or steady drift.

**Why this is cheaper than it sounds.** Most of the data already exists. Every
stored reading carries both `recorded_at` (device clock) and `received_at`
(server clock), so a device's skew is `received_at - recorded_at`, computable
from existing rows with no schema change.

**Signals worth considering.**

- **Skew jumps.** A device whose skew has been stable at around zero and
  suddenly shifts by hours has had its clock reset or corrected.
- **Time going backwards in `seq` order.** `seq` is monotonic per device, so
  `recordedAt` should rise with it. A higher `seq` with a much *earlier*
  `recordedAt` is a clock fault regardless of the order the samples arrived in.
  This matters because out-of-order arrival is normal here and must not be
  mistaken for a fault.
- **Persistent drift** beyond some tolerance, even when no single jump occurs.
- **A high rate of rejected samples** from one device, which today is visible
  only in individual responses.

**Open questions.**

- Output: a new alert type, such as `DEVICE_CLOCK_FAULT`, or a metric? A new
  type would need a migration, because `alerts.type` is restricted by a `CHECK`
  constraint.
- Where it runs: on ingest, per batch (cheap, immediate), or as a scheduled scan
  (can see longer trends).
- Whether a detected fault should affect ingest, for example by quarantining
  that device's readings rather than storing them as history.
- How it interacts with `DEVICE_OFFLINE`, which already deliberately ignores the
  device clock ([ADR 0005](decisions/0005-last-seen-uses-server-clock.md)).

**Relationship to the fixed limit.** This would complement ADR 0006, not
replace it. The fixed floor stays as the hard bound on which partitions a
device can cause to exist; detection adds the ability to notice a clock that
is wrong but still inside the window.
