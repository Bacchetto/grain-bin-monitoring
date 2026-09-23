package com.grainbin.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the Flyway schema enforces the rules the README states, against
 * a real PostgreSQL 16.
 *
 * <p>These are deliberately tests of the <em>database</em>, not of Java code.
 * Partition routing, {@code ON CONFLICT DO NOTHING} and
 * {@code NULLS NOT DISTINCT} are behaviours of PostgreSQL, and the application
 * is about to be built on the assumption that they work exactly as described.
 * Each of these would pass trivially, and meaninglessly, against H2.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaIntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private long insertBin() {
		return jdbc.sql("""
				INSERT INTO bins (name, site, grain_type)
				VALUES (?, ?, 'canola')
				RETURNING id
				""")
				.param("Bin " + System.nanoTime())
				.param("Test Yard")
				.query(Long.class)
				.single();
	}

	private long insertDevice(long binId) {
		return jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash)
				VALUES (?, ?)
				RETURNING id
				""")
				.param(binId)
				.param("hash-" + System.nanoTime())
				.query(Long.class)
				.single();
	}

	private int insertReading(long deviceId, long binId, long seq, OffsetDateTime recordedAt) {
		return jdbc.sql("""
				INSERT INTO readings
					(device_id, bin_id, seq, cable_index, depth_index, recorded_at, temperature_c)
				VALUES (?, ?, ?, 0, 0, ?, 11.4)
				ON CONFLICT DO NOTHING
				""")
				.param(deviceId).param(binId).param(seq).param(recordedAt)
				.update();
	}

	/** Which physical partition a row actually landed in. */
	private String partitionHolding(long seq) {
		return jdbc.sql("SELECT tableoid::regclass::text FROM readings WHERE seq = ?")
				.param(seq)
				.query(String.class)
				.single();
	}

	// -----------------------------------------------------------------------
	// bins
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("bin threshold defaults match the values named in the README")
	void binThresholdDefaults() {
		long binId = insertBin();

		var row = jdbc.sql("""
				SELECT max_temperature_c, max_moisture_pct, rise_threshold_c, rise_window_hours
				FROM bins WHERE id = ?
				""").param(binId).query().singleRow();

		assertThat(row.get("max_temperature_c")).hasToString("20.0");
		assertThat(row.get("max_moisture_pct")).hasToString("14.5");
		assertThat(row.get("rise_threshold_c")).hasToString("2.0");
		assertThat(row.get("rise_window_hours")).isEqualTo(72);
	}

	@Test
	@DisplayName("a bin name may repeat across sites but not within one")
	void binNameUniquePerSite() {
		jdbc.sql("INSERT INTO bins (name, site, grain_type) VALUES ('Bin 1', 'North Yard', 'canola')").update();
		jdbc.sql("INSERT INTO bins (name, site, grain_type) VALUES ('Bin 1', 'South Yard', 'wheat')").update();

		assertThatThrownBy(() -> jdbc
				.sql("INSERT INTO bins (name, site, grain_type) VALUES ('Bin 1', 'North Yard', 'oats')")
				.update())
				.isInstanceOf(DuplicateKeyException.class);
	}

	// -----------------------------------------------------------------------
	// readings: partitioning
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("partition bounds are UTC month boundaries regardless of server timezone")
	void partitionBoundsAreUtc() {
		jdbc.sql("SELECT create_readings_partition(DATE '2027-03-14')").query(String.class).single();

		// Render the stored bound in UTC (transaction-local) so the assertion
		// does not depend on the container's timezone.
		jdbc.sql("SELECT set_config('TimeZone', 'UTC', true)").query(String.class).single();

		String bounds = jdbc.sql("""
				SELECT pg_get_expr(c.relpartbound, c.oid)
				FROM pg_class c
				JOIN pg_inherits i ON i.inhrelid = c.oid
				WHERE c.relname = 'readings_2027_03'
				""").query(String.class).single();

		assertThat(bounds)
				.contains("'2027-03-01 00:00:00+00'")
				.contains("'2027-04-01 00:00:00+00'");
	}

	@Test
	@DisplayName("a row at a month boundary instant routes to the later month")
	void boundaryInstantRoutesToLaterMonth() {
		long binId = insertBin();
		long deviceId = insertDevice(binId);
		jdbc.sql("SELECT create_readings_partition(DATE '2027-05-01')").query(String.class).single();

		// Upper bounds are exclusive, so midnight UTC on the 1st belongs to May,
		// not April.
		long seq = 5001;
		insertReading(deviceId, binId, seq, OffsetDateTime.of(2027, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));

		assertThat(partitionHolding(seq)).isEqualTo("readings_2027_05");
	}

	@Test
	@DisplayName("create_readings_partition is idempotent and safe to call repeatedly")
	void partitionCreationIsIdempotent() {
		String first = jdbc.sql("SELECT create_readings_partition(DATE '2027-08-15')")
				.query(String.class).single();
		String again = jdbc.sql("SELECT create_readings_partition(DATE '2027-08-01')")
				.query(String.class).single();

		assertThat(first).isEqualTo("readings_2027_08");
		assertThat(again).isEqualTo(first);
	}

	// -----------------------------------------------------------------------
	// readings: idempotency
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a replayed reading is discarded and reported as zero rows written")
	void duplicateReadingIsIgnored() {
		long binId = insertBin();
		long deviceId = insertDevice(binId);
		var recordedAt = OffsetDateTime.of(2026, 9, 15, 14, 0, 0, 0, ZoneOffset.UTC);
		jdbc.sql("SELECT create_readings_partition(DATE '2026-09-01')").query(String.class).single();

		assertThat(insertReading(deviceId, binId, 1042, recordedAt))
				.as("first write is new")
				.isEqualTo(1);

		// This is what makes the ingest endpoint's accepted/duplicates counts
		// possible without a pre-check SELECT: the update count IS the answer.
		assertThat(insertReading(deviceId, binId, 1042, recordedAt))
				.as("replay writes nothing")
				.isZero();
	}

	// -----------------------------------------------------------------------
	// alerts: de-duplication and lifecycle
	// -----------------------------------------------------------------------

	private int openSensorAlert(long binId) {
		return jdbc.sql("""
				INSERT INTO alerts (bin_id, type, cable_index, depth_index, trigger_value, threshold_value)
				VALUES (?, 'HIGH_TEMPERATURE', 0, 0, 23.1, 20.0)
				""").param(binId).update();
	}

	@Test
	@DisplayName("only one non-resolved alert may exist per (bin, type, cable, depth)")
	void oneActiveAlertPerCondition() {
		long binId = insertBin();
		openSensorAlert(binId);

		assertThatThrownBy(() -> openSensorAlert(binId))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("resolving an alert frees the condition to occur again as a new row")
	void resolvedAlertsDoNotBlockRecurrence() {
		long binId = insertBin();
		openSensorAlert(binId);

		jdbc.sql("UPDATE alerts SET status = 'RESOLVED', resolved_at = now() WHERE bin_id = ?")
				.param(binId).update();

		assertThat(openSensorAlert(binId)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE bin_id = ?")
				.param(binId).query(Integer.class).single())
				.as("history is kept, not overwritten")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("DEVICE_OFFLINE de-duplicates even though its sensor position is NULL")
	void deviceOfflineDedupesAcrossNulls() {
		long binId = insertBin();
		long deviceId = insertDevice(binId);

		jdbc.sql("INSERT INTO alerts (bin_id, device_id, type) VALUES (?, ?, 'DEVICE_OFFLINE')")
				.param(binId).param(deviceId).update();

		// Without NULLS NOT DISTINCT on the partial unique index, PostgreSQL
		// would treat every NULL sensor position as distinct and allow this --
		// silently disabling de-duplication for the one alert type whose
		// position is always NULL.
		assertThatThrownBy(() -> jdbc
				.sql("INSERT INTO alerts (bin_id, device_id, type) VALUES (?, ?, 'DEVICE_OFFLINE')")
				.param(binId).param(deviceId).update())
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	@DisplayName("an alert cannot claim to be resolved without a resolution time")
	void resolvedRequiresTimestamp() {
		long binId = insertBin();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO alerts (bin_id, type, cable_index, depth_index, status)
				VALUES (?, 'HIGH_MOISTURE', 1, 1, 'RESOLVED')
				""").param(binId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("alerts_resolved_has_timestamp");
	}

	@Test
	@DisplayName("a sensor position must be given in full or not at all")
	void sensorPositionIsAllOrNothing() {
		long binId = insertBin();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO alerts (bin_id, type, cable_index)
				VALUES (?, 'HIGH_TEMPERATURE', 2)
				""").param(binId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("alerts_sensor_position_consistent");
	}

	@Test
	@DisplayName("a DEVICE_OFFLINE alert must name the device it is about")
	void deviceOfflineRequiresDevice() {
		long binId = insertBin();

		assertThatThrownBy(() -> jdbc.sql("INSERT INTO alerts (bin_id, type) VALUES (?, 'DEVICE_OFFLINE')")
				.param(binId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("alerts_device_offline_has_device");
	}
}
