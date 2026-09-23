-- Scope DEVICE_OFFLINE de-duplication to the device rather than the bin.
--
-- SUPERSEDES the alerts_one_active_per_condition_idx index created in
-- V3__alerts.sql. V3 is not edited: migrations are append-only, and a database
-- that has already applied V3 must reach this state by moving forward.
--
-- THE PROBLEM
-- V3 implemented the README's rule literally: at most one non-resolved alert
-- per (bin, type, cable_index, depth_index). DEVICE_OFFLINE has no sensor
-- position, so its key collapsed to (bin, 'DEVICE_OFFLINE', NULL, NULL) --
-- one per bin, however many devices the bin has.
--
-- Nothing in the schema restricts a bin to one device, and the API registers
-- devices through POST /bins/{id}/devices. So with two controllers on a bin,
-- the second one to go offline could not raise an alert, and both ways of
-- handling that conflict are wrong:
--
--   ON CONFLICT DO NOTHING  -- the second outage is silently invisible
--   ON CONFLICT DO UPDATE   -- device_id flips between the two devices, which
--                              reads as one flapping alert rather than two
--                              controllers being down
--
-- Auto-resolve made it worse: when one device recovered, the clear-streak
-- counter would resolve the shared alert while the other device was still
-- offline, leaving a real outage with no alert at all.
--
-- THE FIX
-- Split the rule by what the alert is actually about. Sensor alerts are about
-- a position in a bin; DEVICE_OFFLINE is about a device.

-- ---------------------------------------------------------------------------
-- Guarantee the two partial indexes below are disjoint and complete
-- ---------------------------------------------------------------------------
-- V3 already required DEVICE_OFFLINE to name a device. This adds the mirror
-- rule: a DEVICE_OFFLINE alert must NOT carry a sensor position. Without it a
-- DEVICE_OFFLINE row could in principle carry cable/depth values, and it would
-- then be de-duplicated by the device index while looking like a sensor alert.
ALTER TABLE alerts
    ADD CONSTRAINT alerts_device_offline_has_no_sensor_position
    CHECK (
        type <> 'DEVICE_OFFLINE'
        OR (cable_index IS NULL AND depth_index IS NULL)
    );

-- ---------------------------------------------------------------------------
-- Replace the single de-duplication index with one per alert shape
-- ---------------------------------------------------------------------------
DROP INDEX alerts_one_active_per_condition_idx;

-- Sensor alerts: HIGH_TEMPERATURE, HIGH_MOISTURE, RATE_OF_RISE.
-- Unchanged semantics -- still one non-resolved alert per sensor position.
--
-- NULLS NOT DISTINCT is retained because the schema permits a sensor alert
-- with no position (the alerts_sensor_position_consistent check only requires
-- cable and depth to be both set or both NULL). Without it, two such rows
-- would each be treated as unique.
CREATE UNIQUE INDEX alerts_one_active_sensor_alert_idx
    ON alerts (bin_id, type, cable_index, depth_index) NULLS NOT DISTINCT
    WHERE status <> 'RESOLVED' AND type <> 'DEVICE_OFFLINE';

-- Device alerts: DEVICE_OFFLINE only.
-- Keyed on the device, so two offline controllers on one bin raise two alerts
-- and each resolves on its own recovery.
--
-- No NULLS NOT DISTINCT here: alerts_device_offline_has_device already
-- guarantees device_id is present for this type, so there are no NULLs in this
-- index to disambiguate.
CREATE UNIQUE INDEX alerts_one_active_device_alert_idx
    ON alerts (bin_id, type, device_id)
    WHERE status <> 'RESOLVED' AND type = 'DEVICE_OFFLINE';

COMMENT ON INDEX alerts_one_active_sensor_alert_idx
    IS 'At most one non-resolved sensor alert per (bin, type, cable, depth).';
COMMENT ON INDEX alerts_one_active_device_alert_idx
    IS 'At most one non-resolved DEVICE_OFFLINE alert per (bin, type, device).';
