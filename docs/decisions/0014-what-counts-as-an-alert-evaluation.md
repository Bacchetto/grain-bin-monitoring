# 0014 - What counts as one alert evaluation

**Status:** Accepted, 2026-09-25 (recorded 2026-09-28)
**Applies to:** `alerts.ThresholdEvaluator`, `alerts.AlertRepository`, the scheduled evaluators

## Context

The README says an alert auto-resolves "when the condition has been clear for 3
consecutive evaluations", to stop it flapping. It does not say what one
evaluation is. That matters: it decides how quickly a real alert can resolve,
and whether something that is not a new measurement can resolve it.

The threshold alerts are evaluated on ingest, and ingest traffic is irregular.
A device on a poor link resends batches it has already delivered, sends a
buffered backlog of many samples at once after an outage, and can deliver an
old batch after a newer one.

## Decision

**On ingest** (`HIGH_TEMPERATURE`, `HIGH_MOISTURE`), one evaluation is one
batch's **newest newly stored reading per sensor**:

1. **Only rows the insert accepted count.** The per-row update counts already
   say which rows were stored and which were duplicates, so this costs no
   query. A resent batch evaluates nothing.
2. **One evaluation per sensor per batch**, using that sensor's newest reading
   in the batch. A buffered backlog is one catch-up, not a dozen checks.
3. **A sensor is skipped if something newer is already stored for it.** One
   indexed query over the narrow slice of time the batch covers finds such
   sensors; normally it returns nothing.
4. **Probe fault values count as neither a detection nor a clear** (ADR 0016).
   A missing moisture value is likewise no evaluation of moisture.

**On a schedule** (`RATE_OF_RISE`, `DEVICE_OFFLINE`), each run is one
evaluation per sensor or device that can be judged.

In both cases, three consecutive clear evaluations resolve an alert, whether or
not it has been acknowledged, and any detection resets the count. The clear
count and the resolution change in one `UPDATE`, as the lifecycle `CHECK`
constraints require.

## Alternatives

**Every reading is an evaluation.** Then a resent batch of clear readings, or
a backlog of twelve, would resolve a real alert without the grain ever being
re-measured three times.

**Evaluate the newest reading in the batch, without the late-batch check.**
Rejected once it was clear that a delayed retry of an old hot sample, arriving
after a newer normal one, would open an alert about the past -- and an old
clear reading would count towards resolving a condition that still holds.

## Consequences

- A healthy bin costs three small indexed reads per batch and **no writes**.
  Writes happen only on a breach, or where an open alert reads clear.
- Resolution needs three *new* measurements, however the device transmits
  them. That is proven by tests that resend duplicates, send backlogs and
  deliver batches out of order.
- The late-batch rule is covered by two tests that fail when it is removed --
  checked by removing it.
- An acknowledged alert still resolves on its own: acknowledging a hot spot
  does not cool it.
