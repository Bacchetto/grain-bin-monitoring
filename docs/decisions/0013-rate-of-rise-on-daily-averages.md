# 0013 - Measure rate of rise on daily averages

**Status:** Accepted, 2026-09-25 (recorded 2026-09-28)
**Applies to:** `alerts.RateOfRiseEvaluator`
**Refines:** the README's `RATE_OF_RISE` rule, which does not say how a rise is measured

## Context

The README defines `RATE_OF_RISE` as a sensor's temperature having "risen at
least `rise_threshold_c` (default 2.0) over the trailing `rise_window_hours`
(default 72)". It does not say what "risen" is measured between.

Grain near the top of a bin warms and cools with the air above it. In the
simulator's `normal` scenario the top sensors swing about 3 °C every day, and
real bins do the same. The default threshold is 2 °C, so any rule that
compares a warm afternoon reading with a cool night-time one fires every
afternoon, on every healthy bin.

## Decision

**Compare two whole-day averages**, chosen by the project owner:

```
rise = avg(last 24 h) - avg(the 24 h that ended rise_window_hours ago)
```

A rise at or above the threshold is a detection; below it is a clear.

A sensor is judged only if **each window holds at least half the readings**
its device's registered interval implies for a day. Otherwise that run is
neither a detection nor a clear. Probe fault values are left out of both
averages (ADR 0016).

## Alternatives

**Latest reading minus the window's minimum.** The simplest rule to explain,
and the most sensitive. Rejected because it turns the daily swing into a false
alarm every afternoon on the top two depths of every bin.

**Latest reading minus the oldest in the window.** Cancels the daily swing only
when the window is a whole number of days, and a single noisy reading at either
end decides the result.

**A regression slope over the window.** Robust, but harder to explain than two
averages, and the slope of a daily cycle depends on where in the cycle the
window starts.

## Consequences

- **The daily cycle cancels exactly**, because every hour of the day is counted
  once in each window. Measured on 6.2 million generated readings with a
  ±1.5 °C daily swing, every sensor's rise came out at 0.000
  (`docs/results.md`), and a test proves that a swing larger than the
  threshold raises nothing.
- **It reacts over a day, not instantly.** A rise shows fully only once a day
  of it has been averaged. That suits grain, where spoilage builds over days,
  and `HIGH_TEMPERATURE` still fires on the first hot reading.
- **Two bounded reads per bin, whatever the window.** The query reads two
  24-hour ranges of the `(bin_id, recorded_at)` index. It measured 11.6 ms per
  bin, and a one-year window would cost the same as a three-day one.
- **A new device has no baseline** until it has reported for
  `rise_window_hours` plus a day. The coverage rule makes that silence
  explicit rather than comparing against a partial day.
- **A sensor that stops reporting is neither detected nor cleared.** It cannot
  quietly resolve a rise that was never re-measured; a dead device is
  `DEVICE_OFFLINE`'s job.
