-- Bins and the devices that report on them.
--
-- Flyway migrations are append-only. Once this file has been committed it must
-- never be edited: spring.flyway.validate-on-migrate=true compares a checksum
-- of each applied migration and refuses to start the application if one has
-- changed. Corrections go in a later migration.

-- ---------------------------------------------------------------------------
-- bins
-- ---------------------------------------------------------------------------
-- A physical grain bin, plus its per-bin alert thresholds. Thresholds live on
-- the bin rather than in application config because the API exposes
-- PATCH /bins/{id}/thresholds -- they are data, not deployment settings.
--
-- The defaults below are the ones named in the README. They are placeholders
-- chosen to make the demo behave sensibly, not agronomic guidance.
CREATE TABLE bins (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              TEXT         NOT NULL,
    site              TEXT         NOT NULL,
    grain_type        TEXT         NOT NULL,
    capacity_bushels  INTEGER,

    -- Alert thresholds. NUMERIC rather than DOUBLE PRECISION: these are
    -- compared for ordering and shown to humans, and NUMERIC(4,1) says
    -- exactly what is representable (-999.9 to 999.9 at one decimal place)
    -- instead of inheriting binary floating-point rounding.
    max_temperature_c NUMERIC(4,1) NOT NULL DEFAULT 20.0,
    max_moisture_pct  NUMERIC(4,1) NOT NULL DEFAULT 14.5,
    rise_threshold_c  NUMERIC(4,1) NOT NULL DEFAULT 2.0,
    rise_window_hours INTEGER      NOT NULL DEFAULT 72,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A bin name only has to be unique within its site: two farm yards may
    -- each have a "Bin 1".
    CONSTRAINT bins_site_name_key UNIQUE (site, name),

    CONSTRAINT bins_name_not_blank       CHECK (length(btrim(name)) > 0),
    CONSTRAINT bins_site_not_blank       CHECK (length(btrim(site)) > 0),
    CONSTRAINT bins_grain_type_not_blank CHECK (length(btrim(grain_type)) > 0),
    CONSTRAINT bins_capacity_positive    CHECK (capacity_bushels IS NULL OR capacity_bushels > 0),
    CONSTRAINT bins_moisture_is_a_pct    CHECK (max_moisture_pct >= 0 AND max_moisture_pct <= 100),
    CONSTRAINT bins_rise_window_positive CHECK (rise_window_hours > 0),
    CONSTRAINT bins_rise_threshold_positive CHECK (rise_threshold_c > 0)
);

COMMENT ON TABLE  bins IS 'A physical grain bin and its per-bin alert thresholds.';
COMMENT ON COLUMN bins.rise_window_hours IS 'Trailing window for the RATE_OF_RISE alert.';

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------
-- A monitoring controller attached to a bin. It authenticates with its own API
-- key and never sends its own id -- the id is derived from the key, so a
-- device cannot write readings attributed to some other bin.
CREATE TABLE devices (
    id                        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bin_id                    BIGINT      NOT NULL REFERENCES bins (id) ON DELETE CASCADE,

    -- Only ever the hash. The plaintext key is shown once, at registration,
    -- and is not recoverable afterwards. See the API key hashing ADR for why
    -- this is a SHA-256 hex digest rather than a bcrypt/argon2 hash.
    api_key_hash              TEXT        NOT NULL,

    -- How often this device is expected to report. The DEVICE_OFFLINE alert
    -- fires at 3x this interval without a stored reading.
    expected_interval_seconds INTEGER     NOT NULL DEFAULT 300,

    -- Advanced only after a reading is successfully stored, never on a mere
    -- connection. A device that connects but sends only duplicate or rejected
    -- data is still offline from a data standpoint.
    last_seen_at              TIMESTAMPTZ,

    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- UNIQUE also gives us the index that every ingest request uses to look
    -- the device up by key hash.
    CONSTRAINT devices_api_key_hash_key UNIQUE (api_key_hash),
    CONSTRAINT devices_interval_positive CHECK (expected_interval_seconds > 0)
);

-- For listing a bin's devices, and to keep the ON DELETE CASCADE from the
-- bins foreign key doing a sequential scan.
CREATE INDEX devices_bin_id_idx ON devices (bin_id);

COMMENT ON TABLE  devices IS 'A monitoring controller attached to one bin.';
COMMENT ON COLUMN devices.api_key_hash IS 'SHA-256 hex digest of the device API key. Plaintext is never stored.';
