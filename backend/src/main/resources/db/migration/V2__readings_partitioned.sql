-- Sensor readings, range-partitioned by month.
--
-- WHY PARTITION AT ALL: this is the only table that grows without bound. One
-- device with 4 cables x 6 depths reporting every 5 minutes writes roughly
-- 2.5M rows a year. Partitioning by month means a dashboard query over a date
-- range touches only the months it needs, and old data can eventually be
-- dropped by detaching a partition rather than running a DELETE across a very
-- large table.
--
-- WHY THERE IS NO `samples` TABLE: the domain model describes a Sample (one
-- reporting cycle, carrying seq and recorded_at) containing many Readings.
-- Those are modelled here as one denormalised table, with seq and recorded_at
-- repeated on every reading row. A separate samples table would mean a second
-- insert and a join on the hot path to save a few bytes per row.

CREATE TABLE readings (
    device_id     BIGINT       NOT NULL,

    -- Denormalised from devices. Every dashboard query is bin-scoped, and
    -- carrying bin_id here keeps those queries off a join to devices. The
    -- cost is that moving a device to a different bin would not rewrite
    -- history -- which is arguably correct anyway, since those readings really
    -- were taken in the old bin.
    bin_id        BIGINT       NOT NULL,

    -- Device-assigned, monotonically increasing per device. Part of the
    -- idempotency key below.
    seq           BIGINT       NOT NULL,

    cable_index   SMALLINT     NOT NULL,
    depth_index   SMALLINT     NOT NULL,

    -- recorded_at is the device clock and is the partition key. received_at is
    -- the server clock. Both are kept so that clock skew on a device stays
    -- visible instead of silently rewriting history. Sample ordering is always
    -- by recorded_at, never by arrival order.
    recorded_at   TIMESTAMPTZ  NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    temperature_c NUMERIC(4,1) NOT NULL,
    moisture_pct  NUMERIC(4,1),

    -- THE IDEMPOTENCY KEY. A retried batch carries identical values, so
    -- INSERT ... ON CONFLICT DO NOTHING silently discards duplicates and the
    -- per-row update counts tell the caller how many rows were new.
    --
    -- recorded_at is in the key because PostgreSQL requires the partition key
    -- to be part of any unique constraint on a partitioned table. That is a
    -- constraint of partitioning rather than a modelling choice, but it is
    -- harmless here: a duplicate of a given (device, seq, cable, depth) always
    -- carries the same recorded_at.
    CONSTRAINT readings_pkey PRIMARY KEY (device_id, seq, cable_index, depth_index, recorded_at),

    CONSTRAINT readings_cable_index_non_negative CHECK (cable_index >= 0),
    CONSTRAINT readings_depth_index_non_negative CHECK (depth_index >= 0),
    CONSTRAINT readings_moisture_is_a_pct
        CHECK (moisture_pct IS NULL OR (moisture_pct >= 0 AND moisture_pct <= 100))
) PARTITION BY RANGE (recorded_at);

COMMENT ON TABLE  readings IS 'One sensor value from one reporting cycle. Range-partitioned by recorded_at, one partition per month.';
COMMENT ON COLUMN readings.recorded_at IS 'Device clock. Partition key.';
COMMENT ON COLUMN readings.received_at IS 'Server clock at insert.';

-- NOTE ON FOREIGN KEYS: there is deliberately no foreign key from readings to
-- devices or bins. device_id is only ever supplied by the server after
-- authenticating the API key, so a caller cannot forge it, and an FK check
-- costs an extra index probe per row on the one path that has to sustain
-- load. The tradeoff is recorded in an ADR.

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
-- Declared on the parent table, so PostgreSQL creates a matching index on
-- every partition, including partitions created later.
--
-- Two indexes for two different query shapes:
--
--   GET /bins/{id}/latest            -- newest row per sensor
--   GET /bins/{id}/readings?from&to  -- a date range across the whole bin
--
-- The second is not redundant. A query filtering on bin_id and a recorded_at
-- range can only use bin_id as an access predicate in the first index, because
-- cable_index and depth_index sit between them in the key order.
--
-- Both cost write throughput on the ingest path, which already maintains the
-- primary key. They are verified with EXPLAIN ANALYZE and the plans recorded
-- in docs/results.md.
CREATE INDEX readings_bin_sensor_recent_idx
    ON readings (bin_id, cable_index, depth_index, recorded_at DESC);

CREATE INDEX readings_bin_recorded_idx
    ON readings (bin_id, recorded_at);

-- ---------------------------------------------------------------------------
-- Partition management
-- ---------------------------------------------------------------------------
-- Creates the monthly partition covering p_month if it does not already exist.
-- Safe to call repeatedly and from concurrent sessions.
--
-- The application calls this from two places: a scheduled job working ahead of
-- time, and the ingest path itself before writing a batch. The rule is that an
-- insert must never fail because a partition is missing.
CREATE OR REPLACE FUNCTION create_readings_partition(p_month DATE)
    RETURNS TEXT
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_start DATE := date_trunc('month', p_month)::DATE;
    v_end   DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::DATE;
    v_name  TEXT := 'readings_' || to_char(v_start, 'YYYY_MM');
BEGIN
    -- Bounds are written as explicit UTC timestamps. Passing a bare date would
    -- let PostgreSQL cast it to timestamptz using the session TimeZone, so the
    -- same migration would produce partitions starting at 06:00 UTC on a
    -- server set to America/Edmonton. Month boundaries would then not align
    -- with month boundaries -- the kind of bug that only appears late on the
    -- last day of a month.
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF readings FOR VALUES FROM (%L) TO (%L)',
        v_name,
        v_start::TEXT || ' 00:00:00+00',
        v_end::TEXT   || ' 00:00:00+00'
    );
    RETURN v_name;
EXCEPTION
    -- IF NOT EXISTS still races: two sessions can both pass the existence
    -- check and then both attempt creation. Losing that race is success.
    WHEN duplicate_table OR unique_violation THEN
        RETURN v_name;
END;
$fn$;

COMMENT ON FUNCTION create_readings_partition(DATE)
    IS 'Idempotently creates the monthly readings partition covering the given month. Bounds are UTC.';

-- There is deliberately no DEFAULT partition. A default partition would
-- guarantee that inserts never fail, but it accepts out-of-range rows silently
-- and then blocks creating the correct partition for those rows later without
-- moving them under an ACCESS EXCLUSIVE lock. Ensuring the partition exists
-- before inserting is the safer guarantee.

-- Seed a window around the present so a fresh database is immediately usable.
-- One month back covers devices catching up after an outage; three months
-- ahead is well beyond what the scheduled maintenance job needs to stay ahead
-- of. Which months these are depends on when the migration runs, which is
-- expected -- keeping the window rolling is the scheduled job's job.
DO $seed$
DECLARE
    v_month DATE;
BEGIN
    FOR v_month IN
        SELECT generate_series(
            date_trunc('month', now() AT TIME ZONE 'UTC') - INTERVAL '1 month',
            date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '3 months',
            INTERVAL '1 month'
        )::DATE
    LOOP
        PERFORM create_readings_partition(v_month);
    END LOOP;
END;
$seed$;
