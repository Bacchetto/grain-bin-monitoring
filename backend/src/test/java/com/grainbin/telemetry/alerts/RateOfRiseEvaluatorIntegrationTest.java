package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code RATE_OF_RISE}, with days of readings generated in SQL and the
 * evaluator called at a chosen {@code now}.
 *
 * <p>The hot spot shape here is the one the simulator's {@code hotspot}
 * scenario produces in Milestone 2 Phase 5: steady, then climbing about
 * 1.5 degrees a day. This is the Java half of "each scenario produces the
 * expected alert": the rule, proven deterministically against real
 * PostgreSQL.
 *
 * <p>The device reports every five minutes, so a day holds 288 readings and a
 * window needs 144 to be judged.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateOfRiseEvaluatorIntegrationTest {

	private static final int INTERVAL_SECONDS = 300;
	private static final int CABLE = 1;
	private static final int DEPTH = 3;

	@Autowired
	private RateOfRiseEvaluator evaluator;

	@Autowired
	private AlertLifecycle lifecycle;

	@Autowired
	private JdbcClient jdbc;

	/** Aligned to the reporting interval, so each window holds a whole number of readings. */
	private Instant now;
	private long binId;
	private long deviceId;

	@BeforeEach
	void setUp() {
		long epoch = Instant.now().getEpochSecond();
		this.now = Instant.ofEpochSecond(epoch - epoch % INTERVAL_SECONDS);
		this.binId = jdbc.sql("""
				INSERT INTO bins (name, site, grain_type) VALUES (?, 'Rise Yard', 'canola') RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();
		this.deviceId = jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash, expected_interval_seconds) VALUES (?, ?, ?) RETURNING id
				""").param(binId).param("hash-" + System.nanoTime()).param(INTERVAL_SECONDS)
				.query(Long.class).single();
	}

	// -----------------------------------------------------------------------
	// generating readings
	// -----------------------------------------------------------------------

	/**
	 * One reading every five minutes over {@code [from, to)} for the test
	 * sensor. {@code temperatureSql} is an SQL expression in {@code e}, the
	 * reading's time in epoch seconds -- written by the test, never user input.
	 */
	private void readings(Instant from, Instant to, String temperatureSql) {
		jdbc.sql("""
				INSERT INTO readings
					(device_id, bin_id, seq, cable_index, depth_index, recorded_at, temperature_c)
				SELECT ?, ?, e::bigint, ?, ?, t, round((%s)::numeric, 1)
				FROM generate_series(?::timestamptz, ?::timestamptz - interval '1 second',
				                     make_interval(secs => ?)) AS t,
				     LATERAL (SELECT extract(epoch FROM t) AS e) epoch
				""".formatted(temperatureSql))
				.param(deviceId).param(binId).param(CABLE).param(DEPTH)
				.param(utc(from)).param(utc(to)).param(INTERVAL_SECONDS)
				.update();
	}

	private Instant hoursAgo(long hours) {
		return now.minus(Duration.ofHours(hours));
	}

	/** Steady at 10.0 until {@code climbFrom}, then warming {@code perDay} degrees a day. */
	private String climbing(Instant climbFrom, double perDay) {
		return "10.0 + greatest(0, e - %d) / 86400.0 * %s".formatted(climbFrom.getEpochSecond(), perDay);
	}

	private List<Map<String, Object>> riseAlerts() {
		return jdbc.sql("SELECT * FROM alerts WHERE bin_id = ? AND type = 'RATE_OF_RISE'")
				.param(binId).query().listOfRows();
	}

	private Map<String, Object> onlyRiseAlert() {
		var alerts = riseAlerts();
		assertThat(alerts).hasSize(1);
		return alerts.getFirst();
	}

	private BigDecimal maxTemperature() {
		return jdbc.sql("SELECT max(temperature_c) FROM readings WHERE bin_id = ?")
				.param(binId).query(BigDecimal.class).single();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	// -----------------------------------------------------------------------
	// detection
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a hot spot warming 1.5 degrees a day raises RATE_OF_RISE while still well below 20")
	void hotspotRaisesRateOfRiseFirst() {
		// Steady for three days, then two days of climbing. Baseline window
		// (96-72 h ago) averages 10.0; the last 24 h average about 12.25.
		readings(hoursAgo(120), now, climbing(hoursAgo(48), 1.5));

		evaluator.evaluate(now);

		var alert = onlyRiseAlert();
		assertThat(alert.get("cable_index")).isEqualTo(CABLE);
		assertThat(alert.get("depth_index")).isEqualTo(DEPTH);
		assertThat((BigDecimal) alert.get("trigger_value")).isBetween(new BigDecimal("2.1"), new BigDecimal("2.4"));
		assertThat((BigDecimal) alert.get("threshold_value")).isEqualByComparingTo("2.0");
		// The README's promise for this scenario: RATE_OF_RISE before
		// HIGH_TEMPERATURE. Nothing here has come near 20 degrees.
		assertThat(maxTemperature()).isLessThan(new BigDecimal("14.0"));
	}

	@Test
	@DisplayName("a normal daily swing raises nothing, although it moves more than the threshold every day")
	void dailySwingIsNotARise() {
		// The top-of-bin cycle from the simulator's normal scenario: +/-1.5
		// degrees around 12, peaking each afternoon.
		readings(hoursAgo(120), now, "12.0 + 1.5 * cos(2 * pi() * e / 86400.0)");

		evaluator.evaluate(now);

		assertThat(riseAlerts()).isEmpty();
		// The point of daily averages: newest-minus-lowest would have fired.
		BigDecimal dailyRange = jdbc.sql("""
				SELECT max(temperature_c) - min(temperature_c) FROM readings
				WHERE bin_id = ? AND recorded_at >= ?
				""").param(binId).param(utc(hoursAgo(24))).query(BigDecimal.class).single();
		assertThat(dailyRange).isGreaterThan(new BigDecimal("2.0"));
	}

	@Test
	@DisplayName("the bin's own rise window and threshold are used")
	void usesBinSettings() {
		// Warming 3 degrees a day for the last 24 h: a 1.5 degree rise in daily
		// averages over one day, but also over three days.
		readings(hoursAgo(120), now, climbing(hoursAgo(24), 3.0));

		evaluator.evaluate(now);
		assertThat(riseAlerts()).as("1.5 is under the default 2.0").isEmpty();

		jdbc.sql("UPDATE bins SET rise_window_hours = 24, rise_threshold_c = 1.0 WHERE id = ?").param(binId).update();
		evaluator.evaluate(now);

		assertThat((BigDecimal) onlyRiseAlert().get("threshold_value")).isEqualByComparingTo("1.0");
	}

	// -----------------------------------------------------------------------
	// coverage and plausibility
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a thinly covered baseline is not judged, however large the rise")
	void thinBaselineIsNotJudged() {
		// Data starts 80 h ago: the baseline window (96-72 h ago) holds 8 hours,
		// 96 readings, under the 144 required.
		readings(hoursAgo(80), now, climbing(hoursAgo(80), 5.0));

		evaluator.evaluate(now);

		assertThat(riseAlerts()).isEmpty();
	}

	@Test
	@DisplayName("probe fault values are left out of both averages")
	void faultValuesExcluded() {
		// Steady at 10, except that one reading in ten is a fault code: -127
		// in the baseline window and 85.0 in the current one. Averaged in,
		// they would make a rise of nearly 20 degrees.
		readings(hoursAgo(120), now, """
				CASE WHEN (e / 300)::bigint %% 10 <> 0 THEN 10.0
				     WHEN e < %d THEN -127.0
				     ELSE 85.0 END""".formatted(hoursAgo(24).getEpochSecond()));

		evaluator.evaluate(now);

		assertThat(riseAlerts()).isEmpty();
	}

	// -----------------------------------------------------------------------
	// resolution
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("a rise that stops resolves after three clear runs")
	void resolvesAfterThreeClearRuns() {
		long alert = lifecycle.sensorConditionDetected(binId, AlertType.RATE_OF_RISE, CABLE, DEPTH,
				new BigDecimal("2.5"), new BigDecimal("2.0"), hoursAgo(1)).alertId();
		readings(hoursAgo(120), now, "10.0");

		evaluator.evaluate(now);
		evaluator.evaluate(now.plusSeconds(1));
		assertThat(onlyRiseAlert().get("status")).isEqualTo("OPEN");

		evaluator.evaluate(now.plusSeconds(2));
		assertThat(onlyRiseAlert().get("status")).isEqualTo("RESOLVED");
		assertThat(((Number) onlyRiseAlert().get("id")).longValue()).isEqualTo(alert);
	}

	@Test
	@DisplayName("a sensor with no recent data does not count as clear")
	void missingCurrentDataIsNotAClear() {
		// Went quiet two days ago -- a dead cable is DEVICE_OFFLINE's business,
		// and it must not quietly resolve a rise that was never re-measured.
		lifecycle.sensorConditionDetected(binId, AlertType.RATE_OF_RISE, CABLE, DEPTH,
				new BigDecimal("2.5"), new BigDecimal("2.0"), hoursAgo(49));
		readings(hoursAgo(120), hoursAgo(48), "10.0");

		for (int i = 0; i < 3; i++) {
			evaluator.evaluate(now.plusSeconds(i));
		}

		assertThat(onlyRiseAlert().get("status")).isEqualTo("OPEN");
		assertThat(onlyRiseAlert().get("clear_streak")).isEqualTo(0);
	}
}
