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
| E3 | [Treat implausible sensor values as faults](#e3) | Ingest, alerts | Proposed -- consider with Milestone 2 |
| E4 | [Single-statement bulk insert](#e4) | Ingest performance | Proposed -- only if load tests call for it |
| E5 | [Cache device key lookups](#e5) | Ingest performance | Proposed -- only if load tests call for it |
| E6 | [Scheduled check for orphaned readings](#e6) | Data integrity | Proposed |
| E7 | [Revoke device keys](#e7) | Security | Proposed -- **gap** |
| E8 | [Delete bins and devices](#e8) | Admin API, data lifecycle | Proposed |
| E9 | [Retention by dropping old partitions](#e9) | Data lifecycle | Proposed |
| E10 | [Production-grade admin authentication](#e10) | Security | Proposed |
| E11 | [Peppered (HMAC) device key digests](#e11) | Security | Proposed -- low priority |
| E12 | [Track data freshness separately from liveness](#e12) | Devices, dashboard | Proposed |
| E13 | [Faster `latest` for dense bins](#e13) | Query performance | Proposed -- only if bins get dense |
| E14 | [Daily buckets in the site's local time](#e14) | Query API, dashboard | Proposed |

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

---

<a id="e3"></a>
## E3 - Treat implausible sensor values as faults

**Raised:** Milestone 1 Phase 7

**Today.** Temperature is bounded only by what its column can store (±999.9), so
an unrepresentable value is a `400` but an implausible one is stored as a real
reading. Common probes report fixed values on a fault: a disconnected DS18B20
reads **−127 °C**, and one that has just powered on reads **85 °C**.

**Why it matters.** The Milestone 2 alert engine will treat these as real. A
sensor reading −127 and then 12 looks like a **139 °C rise** to `RATE_OF_RISE`,
and 85 °C trips `HIGH_TEMPERATURE`. Both are false alarms caused by hardware,
not grain.

**The idea.** Recognise sensor faults -- known sentinel values, physically
impossible jumps, values outside a plausible range -- and keep them out of
alert evaluation, either by quarantining them or by storing them with a quality
flag. This should be decided alongside the alert engine rather than after it.

---

<a id="e4"></a>
## E4 - Single-statement bulk insert

**Raised:** Milestone 1 Phase 7

**Today.** Ingest sends one JDBC batch of single-row
`INSERT ... ON CONFLICT DO NOTHING` statements, and the per-row update counts
are how accepted readings are told apart from duplicates. The usual driver
speed-up, `reWriteBatchedInserts=true`, cannot be used: it replaces those
counts with `SUCCESS_NO_INFO`.

**The idea.** One statement per batch --
`INSERT ... SELECT FROM unnest(...) ON CONFLICT DO NOTHING RETURNING 1` --
where the number of returned rows is the accepted count and the rest are
duplicates. Fewer round trips, same exact counts.

**Trigger.** Only if the Milestone 4 load tests show database time on the
ingest path is the bottleneck.

---

<a id="e5"></a>
## E5 - Cache device key lookups

**Raised:** Milestone 1 Phase 5

**Today.** Every ingest request resolves its API key with one indexed lookup,
deliberately uncached (`DeviceAuthenticator`).

**The idea.** A short-lived cache keyed by key digest.

**The catch.** A cache means a revoked or deleted key keeps working until its
entry expires. That delay has to be accepted as an explicit decision, and it
interacts with E7: there is no revocation today, but once there is, a cache
changes how quickly it takes effect.

**Trigger.** Only if load tests show the lookup matters.

---

<a id="e6"></a>
## E6 - Scheduled check for orphaned readings

**Raised:** Milestone 1 Phase 3, [ADR 0001](decisions/0001-no-foreign-keys-on-readings.md)

**Today.** `readings` has no foreign keys, for insert throughput. Readings whose
device or bin no longer exists are possible and nothing detects them. The
detecting query already exists, in `devices/package-info.java`.

**The idea.** Run that anti-join on a schedule and report the count as a metric,
so orphans become visible without paying for a foreign key on every insert.

---

<a id="e7"></a>
## E7 - Revoke device keys

**Raised:** Milestone 1, found while compiling this list. **This is a gap, not
just an idea.**

**Today.** There is no way to revoke a device's API key. If a key is lost or
leaked, the only remedy is to register a replacement device -- **and the old key
keeps working indefinitely.**

**The idea.** A `revoked_at` column on `devices`, set by an admin endpoint and
checked by `DeviceAuthenticator` during the lookup it already does. It needs no
data deletion and fits into the existing query, which is why it is listed
separately from deleting devices (E8).

**Related.** Any caching (E5) would delay revocation by its TTL.

---

<a id="e8"></a>
## E8 - Delete bins and devices

**Raised:** Milestone 1 Phase 3, [ADR 0001](decisions/0001-no-foreign-keys-on-readings.md)

**Today.** Neither can be deleted through the API.

**The constraint any implementation must respect.** `devices` and `alerts`
cascade from `bins`, but `readings` does not. A delete that relied on cascades
would silently leave the readings behind. They have to be removed explicitly,
in the same transaction -- which is expensive across a partitioned table, so a
soft delete or a background reclaim may be the better shape.

---

<a id="e9"></a>
## E9 - Retention by dropping old partitions

**Raised:** Milestone 1 Phase 4

**Today.** Partitions accumulate forever.

**The idea.** Detach and drop monthly partitions older than a retention period.
Dropping a partition is close to free, where deleting the same rows would be
slow and leave the table bloated -- this is much of the reason `readings` is
partitioned at all.

**Two constraints.**

- **It must invalidate the partition cache** in `PartitionMaintenanceService`.
  Otherwise an instance keeps believing a dropped partition exists, and every
  insert into that month fails. That class's javadoc says so.
- **Retention must be longer than `app.ingest.max-sample-age`.** If it were
  shorter, ingest could still accept a sample for a month retention had just
  dropped, and the on-demand path would quietly recreate that partition.

---

<a id="e10"></a>
## E10 - Production-grade admin authentication

**Raised:** Milestone 1 Phase 5, [ADR 0003](decisions/0003-filter-based-auth.md)

**Today.** One shared bearer token for all admin and dashboard access. It never
expires, carries no identity, has no scopes, leaves no audit trail, and rotating
it means a redeploy. The README flags this as deliberately not production-grade.

**The idea.** Real identities -- for example OIDC through Amazon Cognito -- with
per-user tokens, expiry and roles. At that point ADR 0003's reasoning flips, and
Spring Security becomes the right tool rather than hand-written filters.

---

<a id="e11"></a>
## E11 - Peppered (HMAC) device key digests

**Raised:** Milestone 1 Phase 5, [ADR 0004](decisions/0004-sha-256-for-device-api-keys.md)

**Today.** A device key is stored as a plain SHA-256 digest. That is appropriate
for a 256-bit random key; ADR 0004 explains why a slow KDF is not.

**The idea.** HMAC-SHA256 with a server-side secret held in Secrets Manager, so
a copy of the database alone is not enough to check a guessed key.

**Priority.** Low. Guessing a 256-bit random key is infeasible either way; this
only matters under a threat model where the database leaks but the application
secrets do not.

---

<a id="e12"></a>
## E12 - Track data freshness separately from liveness

**Raised:** Milestone 1 Phase 7, [ADR 0005](decisions/0005-last-seen-uses-server-clock.md)

**Today.** `devices.last_seen_at` records when the server last stored data from
a device -- liveness, by the server's clock. The bin list reports it as the last
reading time.

**The idea.** A second value: the newest `recordedAt` stored for the device, by
the device's clock -- freshness of the data itself. The two differ during a
back-fill, and a dashboard may want to show both.

**Trigger.** When the dashboard needs it. Nothing does yet.

---

<a id="e13"></a>
## E13 - Faster `latest` for dense bins

**Raised:** Milestone 1 Phase 8, [ADR 0007](decisions/0007-read-path-indexes-verified-with-explain-analyze.md)

**Today.** `GET /bins/{id}/latest` is a plain `DISTINCT ON` that reads every row
in the 7-day lookback window and keeps the newest per sensor. At 24 sensors
reporting every 5 minutes that is about 48,000 rows and 29 ms. Its cost grows
with sensors × readings per week, so a bin with 200 sensors reporting every
minute would read roughly 2 million rows and take well over a second.

**Two measured or considered options**, both set aside in ADR 0007 because the
current query is fast enough:

- **Loose index scan.** Recreate an index on
  `(bin_id, cable_index, depth_index, recorded_at DESC)` and rewrite the query as
  a recursive CTE that probes each sensor position once. Measured at about 1 ms
  to execute plus 4 ms to plan, independent of reporting frequency. The cost is
  the index itself: roughly 70% more insert time and 64 MB per monthly
  partition at the measured scale, which is why `V5` dropped it.
- **Latest-value table.** One row per sensor position, upserted on every ingest
  batch. Reads become trivial, at the cost of an extra write per sensor per
  batch on the hot path.

**Trigger.** Bins dense enough that `latest` becomes noticeably slow on the
dashboard.

---

<a id="e14"></a>
## E14 - Daily buckets in the site's local time

**Raised:** Milestone 1 Phase 8

**Today.** `GET /bins/{id}/readings?bucket=day` aligns buckets to midnight
**UTC**. That is deliberate and consistent -- the two-argument `date_trunc`
would silently use the database session's time zone instead -- but a UTC day is
not the farmer's day. In Alberta, midnight UTC is 6 pm or 5 pm the previous
evening, so each daily point on the chart spans two local afternoons.

**The idea.** Store an IANA time zone per site or bin (for example
`America/Edmonton`) and truncate in that zone:
`date_trunc('day', recorded_at, <site zone>)`. The three-argument form already
used takes the zone as a parameter, so the query barely changes.

**Things to get right.**

- A day in a zone with daylight saving is 23 or 25 hours twice a year, so the
  bucket-count cap and any client code that assumes 24-hour days need care.
- The zone must come from data, never be interpolated from caller input.
- Hourly buckets are unaffected, apart from zones offset by a non-whole hour.
