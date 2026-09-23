package com.grainbin.telemetry.readings;

import com.grainbin.telemetry.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that an insert never fails for a missing partition.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}. The service keeps an
 * in-memory cache of months it has created, and that cache is not rolled back
 * with the transaction. Rolling back the DDL while leaving the cache populated
 * would make the service believe a partition exists when it does not -- which
 * is exactly the stale-cache failure the service's own javadoc warns about.
 * Each test therefore uses its own distinct far-future months, well beyond the
 * window the startup job creates.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PartitionMaintenanceServiceIntegrationTest {

	@Autowired
	private PartitionMaintenanceService partitions;

	@Autowired
	private JdbcClient jdbc;

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private boolean partitionExists(String name) {
		return jdbc.sql("SELECT to_regclass(?) IS NOT NULL")
				.param("public." + name)
				.query(Boolean.class)
				.single();
	}

	private long newBin() {
		return jdbc.sql("""
				INSERT INTO bins (name, site, grain_type)
				VALUES (?, 'Partition Test Yard', 'canola')
				RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();
	}

	private long newDevice(long binId) {
		return jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash) VALUES (?, ?) RETURNING id
				""").param(binId).param("hash-" + System.nanoTime()).query(Long.class).single();
	}

	private void insertReading(long deviceId, long binId, Instant recordedAt) {
		// The PostgreSQL JDBC driver cannot bind a java.time.Instant: it throws
		// "Can't infer the SQL type to use for an instance of java.time.Instant".
		// TIMESTAMPTZ parameters have to be passed as OffsetDateTime. The ingest
		// path will need the same conversion when it writes batches.
		jdbc.sql("""
				INSERT INTO readings
					(device_id, bin_id, seq, cable_index, depth_index, recorded_at, temperature_c)
				VALUES (?, ?, 1, 0, 0, ?, 11.4)
				""")
				.param(deviceId)
				.param(binId)
				.param(recordedAt.atOffset(ZoneOffset.UTC))
				.update();
	}

	// -----------------------------------------------------------------------
	// the problem this service exists to solve
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("without maintenance, a reading in an uncovered month is rejected outright")
	void insertFailsWhenPartitionIsMissing() {
		long binId = newBin();
		long deviceId = newDevice(binId);

		// There is no default partition, by design, so PostgreSQL has nowhere
		// to route this row. This test documents why the rest of the class
		// matters: without the service, a device reporting outside the seeded
		// window simply loses data.
		assertThatThrownBy(() -> insertReading(deviceId, binId, Instant.parse("2031-01-15T12:00:00Z")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("no partition of relation");
	}

	@Test
	@DisplayName("ensureFor creates the missing partition, and the same insert then succeeds")
	void ensureForMakesTheInsertSucceed() {
		long binId = newBin();
		long deviceId = newDevice(binId);
		Instant recordedAt = Instant.parse("2031-02-15T12:00:00Z");

		assertThat(partitionExists("readings_2031_02")).isFalse();

		partitions.ensureFor(recordedAt);

		assertThat(partitionExists("readings_2031_02")).isTrue();
		assertThatCode(() -> insertReading(deviceId, binId, recordedAt)).doesNotThrowAnyException();
	}

	// -----------------------------------------------------------------------
	// the ingest path's call
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a batch spanning several months gets a partition for each of them")
	void batchSpanningMonthsCoversEveryMonth() {
		partitions.ensureForRecordedAt(List.of(
				Instant.parse("2031-04-30T23:59:59Z"),
				Instant.parse("2031-05-01T00:00:00Z"),
				Instant.parse("2031-06-10T08:00:00Z")));

		assertThat(partitionExists("readings_2031_04")).isTrue();
		assertThat(partitionExists("readings_2031_05")).isTrue();
		assertThat(partitionExists("readings_2031_06")).isTrue();
	}

	@Test
	@DisplayName("a month is only resolved against the database once")
	void repeatedCallsAreServedFromCache() {
		YearMonth month = YearMonth.of(2031, 8);
		Instant instant = Instant.parse("2031-08-05T00:00:00Z");

		assertThat(partitions.knownMonths()).doesNotContain(month);

		partitions.ensureFor(instant);
		assertThat(partitions.knownMonths()).contains(month);

		// The point of the cache: the common case, a batch recorded in a month
		// already seen, must not cost a database round trip. Repeating the
		// call must stay harmless.
		assertThatCode(() -> partitions.ensureFor(instant)).doesNotThrowAnyException();
		assertThat(partitionExists("readings_2031_08")).isTrue();
	}

	// -----------------------------------------------------------------------
	// the scheduled job
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("maintenance creates the given month and the configured months ahead")
	void maintenanceWorksAhead() {
		int created = partitions.createFrom(YearMonth.of(2032, 1), PartitionMaintenanceService.MONTHS_AHEAD);

		assertThat(created).isEqualTo(PartitionMaintenanceService.MONTHS_AHEAD + 1);
		assertThat(partitionExists("readings_2032_01")).isTrue();
		assertThat(partitionExists("readings_2032_02")).isTrue();
		assertThat(partitionExists("readings_2032_03")).isTrue();
		assertThat(partitionExists("readings_2032_04")).isTrue();
		assertThat(partitionExists("readings_2032_05")).as("one past the window").isFalse();
	}

	@Test
	@DisplayName("maintenance is idempotent across runs")
	void maintenanceIsIdempotent() {
		partitions.createFrom(YearMonth.of(2033, 6), 1);

		assertThat(partitions.createFrom(YearMonth.of(2033, 6), 1))
				.as("second run finds both months already known")
				.isZero();
	}

	@Test
	@DisplayName("the startup run leaves the current month covered")
	void startupRunCoversTheCurrentMonth() {
		// maintainOnStartup already fired when the context came up, so a
		// reading recorded right now must have somewhere to go without any
		// on-demand help.
		long binId = newBin();
		long deviceId = newDevice(binId);

		assertThatCode(() -> insertReading(deviceId, binId, Instant.now()))
				.doesNotThrowAnyException();
	}

	// -----------------------------------------------------------------------
	// timezone correctness
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("the month is derived in UTC, not in the JVM's local zone")
	void monthIsDerivedInUtc() {
		// 00:30 UTC on 1 July is still 30 June in any negative-offset zone,
		// including the America/Edmonton this project is developed in. If the
		// service resolved the month with the system default zone it would
		// create June's partition and the insert would land nowhere.
		Instant justAfterUtcMonthStart = Instant.parse("2032-07-01T00:30:00Z");

		partitions.ensureFor(justAfterUtcMonthStart);

		assertThat(partitions.knownMonths()).contains(YearMonth.of(2032, 7));
		assertThat(partitionExists("readings_2032_07")).isTrue();

		long binId = newBin();
		long deviceId = newDevice(binId);
		assertThatCode(() -> insertReading(deviceId, binId, justAfterUtcMonthStart))
				.doesNotThrowAnyException();

		assertThat(jdbc.sql("SELECT tableoid::regclass::text FROM readings WHERE device_id = ?")
				.param(deviceId).query(String.class).single())
				.isEqualTo("readings_2032_07");
	}
}
