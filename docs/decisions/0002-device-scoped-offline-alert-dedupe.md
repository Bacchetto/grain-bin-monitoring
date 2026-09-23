# 0002 - DEVICE_OFFLINE alerts de-duplicate per device, not per bin

**Status:** Accepted, 2026-09-23
**Applies to:** `V4__device_scoped_offline_alert_dedupe.sql`
**Amends:** the alert lifecycle rules in `README.md`

## Context

The README states one de-duplication rule for all alert types:

> At most one non-resolved alert per `(bin, type, cable, depth)`.

`V3__alerts.sql` implemented that literally, as a partial unique index with
`NULLS NOT DISTINCT`. That works for the three sensor alert types, which name a
cable and a depth. It does not work for `DEVICE_OFFLINE`, which has no sensor
position: its key collapses to `(bin, 'DEVICE_OFFLINE', NULL, NULL)`, which is
**one alert per bin no matter how many devices the bin has**.

The rest of the specification does not assume one device per bin. The domain
model describes a device as *"a monitoring controller attached to one bin"* --
which constrains devices to one bin, not bins to one device -- devices are
registered through `POST /bins/{id}/devices`, and nothing in the schema
prevents a bin having several. A large bin with cables split across two
controllers is a reasonable deployment.

With two devices on a bin, the second to go offline could not raise an alert,
and both ways of resolving that conflict are wrong:

- `ON CONFLICT DO NOTHING` -- the second device's outage is silently invisible.
- `ON CONFLICT DO UPDATE` -- `device_id` flips between the two devices on
  alternating evaluations, which reads as one alert flapping rather than two
  controllers being down.

Auto-resolve compounded it. When the first device recovered, the clear-streak
counter would resolve the shared alert while the second device was still
offline, leaving a genuine outage with no alert at all. That is the worst
possible failure for a monitoring system: not a false alarm, but a real
condition that is confidently reported as fine.

## Decision

De-duplicate by what the alert is actually about.

| Alert types | Active-alert key |
|---|---|
| `HIGH_TEMPERATURE`, `HIGH_MOISTURE`, `RATE_OF_RISE` | `(bin, type, cable, depth)` |
| `DEVICE_OFFLINE` | `(bin, type, device)` |

Implemented as two disjoint partial unique indexes, both still scoped to
`status <> 'RESOLVED'` so that history accumulates freely. A new `CHECK`
constraint forbids a `DEVICE_OFFLINE` alert from carrying a sensor position,
which guarantees the two indexes are disjoint and together cover every
non-resolved row.

**The README's alert section is updated to match.** The specification was
slightly wrong here, not the implementation, and leaving the two disagreeing
would be worse than either.

## Alternatives

**Enforce one device per bin** with `UNIQUE (bin_id)` on `devices`. This makes
the README self-consistent without touching the alert rules. Rejected because
it contradicts the plural registration endpoint and makes a legitimate physical
arrangement -- one bin, cables on two controllers -- unmodellable, in order to
work around an alerting detail.

**Add `device_id` to the key for every alert type.** Uniform and simple to
state. Rejected because it changes sensor-alert semantics for no benefit: it is
ambiguous whether two devices on one bin can report the same `(cable, depth)`,
and if they can, this would allow two open alerts for what is physically one
hot spot.

**Leave it and document the limit.** Acceptable only if one device per bin is
genuinely the deployment model. It is not stated anywhere that it is, and the
failure mode is silent rather than loud, so this was rejected.

## Consequences

- Two offline controllers on one bin now raise two alerts, and each resolves on
  its own recovery.
- The Milestone 2 alert engine must pick the right conflict target per alert
  type. There is no single `ON CONFLICT` clause that covers both indexes; the
  `DEVICE_OFFLINE` path targets `(bin_id, type, device_id)` and the sensor path
  targets `(bin_id, type, cable_index, depth_index)`.
- `GET /alerts` may now return several `DEVICE_OFFLINE` rows for one bin. The
  dashboard's "worst open alert" badge is unaffected, since it reduces to a
  severity rather than a count.
- `devices` still has no `UNIQUE (bin_id)`, so multi-device bins remain
  supported everywhere, not just in alerting.

## See also

- [0001 - No foreign keys on the readings table](0001-no-foreign-keys-on-readings.md)
