-- Alert conditions detected on a bin, sensor, or device.
--
-- SCOPE: Milestone 1 provides the schema only. The engine that writes to this
-- table -- threshold evaluation on ingest, and the scheduled rate-of-rise and
-- device-offline checks -- arrives in Milestone 2. The schema lands now
-- because the alert lifecycle rules are really integrity rules, and the
-- database is the right place to enforce them.

CREATE TABLE alerts (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bin_id            BIGINT      NOT NULL REFERENCES bins (id) ON DELETE CASCADE,

    -- Set for DEVICE_OFFLINE, which is about a device rather than a sensor
    -- position. Informational only: it is not part of the de-duplication key
    -- below, because the README defines that key as
    -- (bin, type, cable, depth). With more than one device on a bin, two
    -- simultaneously offline devices would collide on that key. That is
    -- accepted for now and noted here so it is a known limit rather than a
    -- surprise.
    device_id         BIGINT      REFERENCES devices (id) ON DELETE CASCADE,

    type              TEXT        NOT NULL,

    -- NULL for alerts that are not about one sensor position, i.e.
    -- DEVICE_OFFLINE.
    cable_index       SMALLINT,
    depth_index       SMALLINT,

    status            TEXT        NOT NULL DEFAULT 'OPEN',

    -- What tripped the alert and what it was measured against, captured at
    -- detection time. Kept on the row so that an alert stays explicable even
    -- after the bin thresholds are later edited.
    trigger_value     NUMERIC(6,2),
    threshold_value   NUMERIC(6,2),

    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Repeat detections update this rather than inserting a new row.
    last_detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    acknowledged_at   TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,

    -- Consecutive evaluations during which the condition has been clear.
    -- The engine auto-resolves at 3, which is what stops an alert flapping
    -- around a threshold.
    clear_streak      SMALLINT    NOT NULL DEFAULT 0,

    CONSTRAINT alerts_type_valid CHECK (
        type IN ('HIGH_TEMPERATURE', 'HIGH_MOISTURE', 'RATE_OF_RISE', 'DEVICE_OFFLINE')
    ),
    CONSTRAINT alerts_status_valid CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')
    ),

    -- Lifecycle invariants. Enforcing these here means a bug in the engine
    -- surfaces as a failed write rather than as an alert that silently claims
    -- to be resolved with no resolution time.
    CONSTRAINT alerts_acknowledged_has_timestamp CHECK (
        status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL
    ),
    CONSTRAINT alerts_resolved_has_timestamp CHECK (
        status <> 'RESOLVED' OR resolved_at IS NOT NULL
    ),
    CONSTRAINT alerts_detection_window_ordered CHECK (
        last_detected_at >= first_detected_at
    ),
    CONSTRAINT alerts_clear_streak_non_negative CHECK (clear_streak >= 0),

    -- A sensor alert names a position; a device alert does not.
    CONSTRAINT alerts_sensor_position_consistent CHECK (
        (cable_index IS NULL) = (depth_index IS NULL)
    ),
    CONSTRAINT alerts_device_offline_has_device CHECK (
        type <> 'DEVICE_OFFLINE' OR device_id IS NOT NULL
    )
);

-- ---------------------------------------------------------------------------
-- De-duplication
-- ---------------------------------------------------------------------------
-- "At most one non-resolved alert per (bin, type, cable, depth)."
--
-- A partial unique index enforces that rule in the database rather than in
-- application logic, so a race between the synchronous ingest evaluation and
-- the scheduled evaluation cannot produce two open alerts for the same
-- condition. The engine can then use ON CONFLICT to turn "detected again"
-- into an update of last_detected_at.
--
-- Resolved rows are excluded from the index, so history accumulates freely:
-- a condition can occur, resolve, and occur again as separate rows.
--
-- NULLS NOT DISTINCT is required (PostgreSQL 15+). By default NULLs compare as
-- distinct in a unique index, so without it every DEVICE_OFFLINE row -- which
-- has NULL cable_index and depth_index -- would be considered unique and the
-- constraint would not apply to exactly the alert type that needs it most.
CREATE UNIQUE INDEX alerts_one_active_per_condition_idx
    ON alerts (bin_id, type, cable_index, depth_index) NULLS NOT DISTINCT
    WHERE status <> 'RESOLVED';

-- ---------------------------------------------------------------------------
-- Query support
-- ---------------------------------------------------------------------------
-- GET /alerts?status=&binId= filters on either or both, newest first.
CREATE INDEX alerts_status_detected_idx ON alerts (status, last_detected_at DESC);
CREATE INDEX alerts_bin_status_idx      ON alerts (bin_id, status);

COMMENT ON TABLE  alerts IS 'A detected condition on a bin, sensor, or device. Lifecycle: OPEN -> ACKNOWLEDGED -> RESOLVED.';
COMMENT ON COLUMN alerts.clear_streak IS 'Consecutive clear evaluations; the engine auto-resolves at 3 to prevent flapping.';
COMMENT ON COLUMN alerts.device_id IS 'Set for DEVICE_OFFLINE. Informational: not part of the de-duplication key.';
