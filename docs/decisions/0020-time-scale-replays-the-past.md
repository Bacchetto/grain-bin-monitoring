# 0020 - The simulator's `--time-scale` replays the past

**Status:** Accepted, 2026-09-28
**Applies to:** `simulator/sim.py`, `simulator/scenarios.py`

## Context

The README asks for `--time-scale` "to compress simulated time so that
multi-day scenarios run in minutes". The `hotspot` scenario needs about five
days to develop: three steady days to give the rate-of-rise rule its baseline
(ADR 0013), then two of warming.

Simulated time cannot run *ahead*, though. The backend rejects samples recorded
more than five minutes in the future, and it judges rate of rise against its
own clock -- the last 24 hours before *now*. Five days compressed into ten
minutes would put four days and twenty-three hours in the future.

## Decision

**Replay the past.** A scenario starts `--history` hours ago and is replayed
`--time-scale` times faster than real time until it catches up with the
present, then carries on live.

- **Default history per scenario:** 120 h for `hotspot`, 24 h for `wet`, 0
  otherwise. `--history` works with any scenario -- `normal` with three days
  behind it gives the dashboard's charts something to show.
- **Default scale 3600**, an hour a second: `hotspot` replays in about two
  minutes. A scale of 1 or less is refused, since the replay could never
  catch up with a present that keeps moving.
- **Each window is capped at `now()`, read afresh**, so no sample is ever in
  the future.
- **Samples are spaced at each device's registered interval**, which
  `.devices.json` now records. The rate-of-rise rule needs half the readings
  that interval implies in each window, so a sparser replay would leave every
  window too thin to judge.
- **History is capped at 29 days**, inside the backend's 30-day sample-age limit.

## Alternatives

**Run the clock forward, with the backend told what time it is.** It would
mean a test-only way to set the server's clock in a running system -- a
production API that believes whatever time a caller sends.

**Generate the history in SQL, bypassing the API.** Faster, but it would not
exercise ingest, and the scenarios would stop being what a device sends.

## Consequences

- **`offline` cannot be compressed.** `DEVICE_OFFLINE` compares the server's
  clock with a `last_seen_at` that is also the server's clock (ADR 0005), so it
  takes three real registered intervals, at least 90 seconds.
- A replay is heavy on purpose: five days at 30 seconds is 345,600 readings per
  bin, sent in about 29 full batches.
- The shapes are checked three ways. `pytest` checks the generators against
  the backend's rules, re-implemented independently. The backend's tests check
  the rules against the same shapes. And `pytest -m e2e` runs each scenario
  against a live stack -- all three raised their alerts, in about two minutes.
