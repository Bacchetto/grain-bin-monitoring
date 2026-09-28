package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DEVICE_OFFLINE}, driven by calling the evaluator with a chosen
 * {@code now}.
 *
 * <p>Every evaluation judges every device in the database, including those
 * other tests created. So {@code now} is kept close to the real time, and each
 * test's devices are positioned relative to it; other tests' devices, created
 * moments ago with the default five-minute interval, are never offline at
 * that {@code now}. Assertions only ever look at this test's own bin.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeviceOfflineEvaluatorIntegrationTest {

	/** Registered interval for this test's devices: offline after 3 minutes. */
	private static final int INTERVAL_SECONDS = 60;

	@Autowired
	private DeviceOfflineEvaluator evaluator;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	private Instant now;
	private long binId;

	@BeforeEach
	void setUp() {
		this.now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		this.binId = jdbc.sql("""
				INSERT INTO bins (name, site, grain_type) VALUES (?, 'Offline Yard', 'canola') RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	/** A device registered {@code registeredAgo} before now, last seen at {@code lastSeen} (may be null). */
	private long device(Instant registeredAt, Instant lastSeen) {
		return jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash, expected_interval_seconds, created_at, last_seen_at)
				VALUES (?, ?, ?, ?, ?)
				RETURNING id
				""")
				.param(binId)
				.param("hash-" + System.nanoTime())
				.param(INTERVAL_SECONDS)
				.param(utc(registeredAt))
				.param(lastSeen == null ? null : utc(lastSeen))
				.query(Long.class).single();
	}

	private long deviceLastSeen(long secondsAgo) {
		return device(now.minus(1, ChronoUnit.DAYS), now.minusSeconds(secondsAgo));
	}

	private void seen(long deviceId, Instant at) {
		jdbc.sql("UPDATE devices SET last_seen_at = ? WHERE id = ?").param(utc(at)).param(deviceId).update();
	}

	private List<Map<String, Object>> offlineAlerts() {
		return jdbc.sql("SELECT * FROM alerts WHERE bin_id = ? AND type = 'DEVICE_OFFLINE' ORDER BY id")
				.param(binId).query().listOfRows();
	}

	private String statusFor(long deviceId) {
		return offlineAlerts().stream()
				.filter(alert -> ((Number) alert.get("device_id")).longValue() == deviceId)
				.map(alert -> (String) alert.get("status"))
				.findFirst()
				.orElse(null);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	// -----------------------------------------------------------------------
	// detection
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a device silent for more than 3x its interval is offline")
	void silentDeviceIsOffline() {
		long device = deviceLastSeen(3 * INTERVAL_SECONDS + 1);

		evaluator.evaluate(now);

		assertThat(statusFor(device)).isEqualTo("OPEN");
		var alert = offlineAlerts().getFirst();
		assertThat(alert.get("trigger_value")).isNull();
		assertThat(alert.get("cable_index")).isNull();
	}

	@Test
	@DisplayName("exactly 3x its interval is not yet offline")
	void boundaryIsNotOffline() {
		deviceLastSeen(3 * INTERVAL_SECONDS);

		evaluator.evaluate(now);

		assertThat(offlineAlerts()).isEmpty();
	}

	@Test
	@DisplayName("a device reporting normally raises nothing")
	void reportingDeviceIsFine() {
		deviceLastSeen(INTERVAL_SECONDS);

		evaluator.evaluate(now);

		assertThat(offlineAlerts()).isEmpty();
	}

	@Test
	@DisplayName("a device that never reported is offline once 3x its interval has passed since registration")
	void neverReportedIsMeasuredFromRegistration() {
		long stale = device(now.minusSeconds(3 * INTERVAL_SECONDS + 1), null);
		long fresh = device(now.minusSeconds(INTERVAL_SECONDS), null);

		evaluator.evaluate(now);

		assertThat(statusFor(stale)).isEqualTo("OPEN");
		assertThat(statusFor(fresh)).isNull();
	}

	@Test
	@DisplayName("repeated runs keep one alert per device")
	void repeatedRunsDoNotDuplicate() {
		deviceLastSeen(3600);

		evaluator.evaluate(now);
		evaluator.evaluate(now.plusSeconds(60));
		evaluator.evaluate(now.plusSeconds(120));

		assertThat(offlineAlerts()).hasSize(1);
	}

	// -----------------------------------------------------------------------
	// recovery
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a device back online resolves after three clear runs, not two")
	void recoveryResolvesAfterThreeRuns() {
		long device = deviceLastSeen(3600);
		evaluator.evaluate(now);

		seen(device, now.plusSeconds(10));
		evaluator.evaluate(now.plusSeconds(60));
		evaluator.evaluate(now.plusSeconds(90));
		assertThat(statusFor(device)).isEqualTo("OPEN");

		evaluator.evaluate(now.plusSeconds(120));
		assertThat(statusFor(device)).isEqualTo("RESOLVED");
	}

	@Test
	@DisplayName("one device recovering does not resolve another device's outage on the same bin")
	void devicesResolveIndependently() {
		long recovers = deviceLastSeen(3600);
		long staysDown = deviceLastSeen(3600);
		evaluator.evaluate(now);

		seen(recovers, now.plusSeconds(10));
		for (int i = 1; i <= 3; i++) {
			evaluator.evaluate(now.plusSeconds(20L * i));
		}

		assertThat(statusFor(recovers)).isEqualTo("RESOLVED");
		assertThat(statusFor(staysDown)).isEqualTo("OPEN");
	}

	// -----------------------------------------------------------------------
	// single runner
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a run is skipped while another instance holds the job lock")
	void skippedWhileLocked() throws Exception {
		long device = deviceLastSeen(3600);

		// Another "instance": a separate connection holding the same advisory
		// lock. The session-level lock conflicts with the evaluator's
		// transaction-level one, as it would across two ECS tasks.
		try (Connection other = dataSource.getConnection();
				PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
			lock.setLong(1, ScheduledJobLock.Job.DEVICE_OFFLINE.lockKey());
			lock.execute();

			assertThat(evaluator.evaluate(now)).isEmpty();
			assertThat(statusFor(device)).isNull();

			try (PreparedStatement unlock = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				unlock.setLong(1, ScheduledJobLock.Job.DEVICE_OFFLINE.lockKey());
				unlock.execute();
			}
		}

		assertThat(evaluator.evaluate(now)).isPresent();
		assertThat(statusFor(device)).isEqualTo("OPEN");
	}
}
