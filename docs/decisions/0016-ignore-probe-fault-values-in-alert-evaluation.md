# 0016 - Keep probe fault values out of alert evaluation

**Status:** Accepted, 2026-09-25 (recorded 2026-09-28)
**Applies to:** `alerts.SensorPlausibility`, and every evaluator that reads temperatures
**Partly addresses:** enhancement E3

## Context

Temperature is validated at ingest only against what its column can store
(±999.9), so a value that is merely implausible is stored as a real reading.
Probes report fixed values on a fault. Assuming DS18B20 probes, common in
digital grain cables:

- **85.0 °C** is the DS18B20 datasheet's power-on reset value (`0x0550`),
  returned when the probe is read before its first conversion completes.
- **−127 °C** is not a sensor value at all. It is `DEVICE_DISCONNECTED_C`, what
  the widely used Arduino DallasTemperature library returns when it cannot read
  the probe.

Fed to the engine, an 85 trips `HIGH_TEMPERATURE` at once, and a −127 followed
by 12 looks like a 139 °C rise.

## Decision

**The engine ignores implausible temperatures**, chosen by the project owner.
They are still stored and shown -- a probe reporting faults is itself worth
seeing -- but count as neither a detection nor a clear.

Implausible means **exactly 85.0 °C, or anything outside −60 to 100 °C**. The
rule exists in Java and as a SQL predicate for the rate-of-rise averages; a test
checks that the two agree.

## Why the range is wide

The two possible mistakes do not cost the same. Treating a fault as real costs
a false alarm. Treating a real reading as a fault costs a *missed* alarm, and
the readings most at risk are the extreme ones -- which are the ones that
matter most. Heating grain can pass 60 °C on its way to combustion, so the
first draft's −40 to 60 °C would have hidden exactly that reading. The range
therefore excludes only what no bin can physically report, and the power-on
value is matched exactly instead: a bin genuinely at 85 °C still alerts on
84.9 and 85.1.

The floor was lowered from −50 to −60 °C at the owner's request: prairie air
temperatures have passed −50 °C.

## Alternatives

**Do nothing now (defer E3).** Leaves the false alarms above, and the demo could
not show otherwise.

**Reject implausible readings at ingest.** Cleaner data, but it discards the
evidence of a failing probe, and changes Milestone 1's ingest contract.

**A quality flag on each stored reading.** The better long-term answer, and why
E3 stays open: it would let the dashboard mark suspect readings too. A larger
change than Milestone 2 needs.

## Consequences

- No false alarms from the two known fault codes. Tests prove that 85.0 and
  −127 raise nothing, and neither counts as a clear.
- A probe stuck at a *plausible* wrong value is not caught; that needs E3's
  fuller treatment, or E2's device-behaviour checks.
- Another probe type has other fault codes, and this class would need them.
