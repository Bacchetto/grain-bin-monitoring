package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The alert lifecycle against the real schema: de-duplication through the two
 * partial unique indexes, the clear streak and auto-resolve, and the
 * after-commit reporting of transitions.
 *
 * <p>Not {@code @Transactional}: transitions are reported after commit, so a
 * test wrapped in a rolled-back transaction would never see them. Each test
 * creates its own bin instead, so tests cannot see each other's alerts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AlertLifecycleIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-09-01T12:00:00Z");
	private static final BigDecimal THRESHOLD = new BigDecimal("20.0");

	@Autowired
	private AlertLifecycle lifecycle;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MeterRegistry meters;

	@Autowired
	private PlatformTransactionManager transactionManager;

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private long newBin() {
		return jdbc.sql("""
				INSERT INTO bins (name, site, grain_type)
				VALUES (?, 'Alert Test Yard', 'canola')
				RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();
	}

	private long newDevice(long binId) {
		return jdbc.sql("INSERT INTO devices (bin_id, api_key_hash) VALUES (?, ?) RETURNING id")
				.param(binId).param("hash-" + System.nanoTime()).query(Long.class).single();
	}

	private AlertRepository.Detection hot(long binId, int cable, int depth, String value, Instant at) {
		return lifecycle.sensorConditionDetected(binId, AlertType.HIGH_TEMPERATURE, cable, depth,
				new BigDecimal(value), THRESHOLD, at);
	}

	private Map<String, Object> alertRow(long alertId) {
		return jdbc.sql("SELECT * FROM alerts WHERE id = ?").param(alertId).query().singleRow();
	}

	private long countAlerts(long binId) {
		return jdbc.sql("SELECT count(*) FROM alerts WHERE bin_id = ?").param(binId).query(Long.class).single();
	}

	private Instant lastDetectedAt(long alertId) {
		return jdbc.sql("SELECT last_detected_at FROM alerts WHERE id = ?")
				.param(alertId).query(OffsetDateTime.class).single().toInstant();
	}

	private void acknowledge(long alertId) {
		jdbc.sql("UPDATE alerts SET status = 'ACKNOWLEDGED', acknowledged_at = now() WHERE id = ?")
				.param(alertId).update();
	}

	private double transitions(AlertType type, AlertStatus toState) {
		return meters.get(AlertTransitions.METRIC_NAME)
				.tag("type", type.name())
				.tag("to_state", toState.name())
				.counter()
				.count();
	}

	// -----------------------------------------------------------------------
	// de-duplication
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("de-duplication")
	class Deduplication {

		@Test
		@DisplayName("a repeat detection refreshes the open alert instead of adding a row")
		void repeatDetectionRefreshes() {
			long bin = newBin();

			var first = hot(bin, 1, 3, "21.0", T0);
			var second = hot(bin, 1, 3, "22.5", T0.plusSeconds(300));

			assertThat(first.opened()).isTrue();
			assertThat(second.opened()).isFalse();
			assertThat(second.alertId()).isEqualTo(first.alertId());
			assertThat(countAlerts(bin)).isOne();

			var row = alertRow(first.alertId());
			assertThat((BigDecimal) row.get("trigger_value")).isEqualByComparingTo("22.5");
			assertThat(lastDetectedAt(first.alertId())).isEqualTo(T0.plusSeconds(300));
		}

		@Test
		@DisplayName("each sensor position and each type is its own condition")
		void positionsAndTypesAreDistinct() {
			long bin = newBin();

			hot(bin, 1, 3, "21.0", T0);
			hot(bin, 1, 4, "21.0", T0);
			hot(bin, 2, 3, "21.0", T0);
			lifecycle.sensorConditionDetected(bin, AlertType.HIGH_MOISTURE, 1, 3,
					new BigDecimal("15.0"), new BigDecimal("14.5"), T0);

			assertThat(countAlerts(bin)).isEqualTo(4);
		}

		@Test
		@DisplayName("two offline devices on one bin raise two alerts (ADR 0002)")
		void offlineIsPerDevice() {
			long bin = newBin();
			long deviceA = newDevice(bin);
			long deviceB = newDevice(bin);

			var a = lifecycle.deviceOfflineDetected(bin, deviceA, T0);
			var b = lifecycle.deviceOfflineDetected(bin, deviceB, T0);
			var aAgain = lifecycle.deviceOfflineDetected(bin, deviceA, T0.plusSeconds(60));

			assertThat(a.opened()).isTrue();
			assertThat(b.opened()).isTrue();
			assertThat(b.alertId()).isNotEqualTo(a.alertId());
			assertThat(aAgain.opened()).isFalse();
			assertThat(aAgain.alertId()).isEqualTo(a.alertId());
			assertThat(countAlerts(bin)).isEqualTo(2);
		}

		@Test
		@DisplayName("an offline alert stores no trigger or threshold value")
		void offlineHasNoValues() {
			// The columns are NUMERIC(6,2); an outage in seconds would overflow
			// them within three hours. See AlertRepository.
			long bin = newBin();
			var alert = lifecycle.deviceOfflineDetected(bin, newDevice(bin), T0);

			var row = alertRow(alert.alertId());
			assertThat(row.get("trigger_value")).isNull();
			assertThat(row.get("threshold_value")).isNull();
			assertThat(row.get("cable_index")).isNull();
		}

		@Test
		@DisplayName("last_detected_at never moves backwards")
		void lastDetectedIsMonotonic() {
			// Two evaluations can commit in either order. Without GREATEST, the
			// later commit carrying the earlier time would also violate the
			// CHECK that last_detected_at >= first_detected_at.
			long bin = newBin();
			var alert = hot(bin, 0, 0, "21.0", T0.plusSeconds(600));
			hot(bin, 0, 0, "21.0", T0);

			assertThat(lastDetectedAt(alert.alertId())).isEqualTo(T0.plusSeconds(600));
		}

		@Test
		@DisplayName("the sensor upsert refuses DEVICE_OFFLINE")
		void sensorUpsertRejectsDeviceType() {
			long bin = newBin();
			assertThatThrownBy(() -> lifecycle.sensorConditionDetected(bin, AlertType.DEVICE_OFFLINE,
					0, 0, BigDecimal.ONE, BigDecimal.ONE, T0))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// -----------------------------------------------------------------------
	// auto-resolve
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("auto-resolve")
	class AutoResolve {

		@Test
		@DisplayName("resolves on the third consecutive clear evaluation, not before")
		void resolvesOnThirdClear() {
			long bin = newBin();
			long alert = hot(bin, 0, 0, "21.0", T0).alertId();

			var first = lifecycle.conditionClear(alert, T0.plusSeconds(60)).orElseThrow();
			var second = lifecycle.conditionClear(alert, T0.plusSeconds(120)).orElseThrow();
			assertThat(first.resolved()).isFalse();
			assertThat(second.resolved()).isFalse();
			assertThat(alertRow(alert).get("status")).isEqualTo("OPEN");

			var third = lifecycle.conditionClear(alert, T0.plusSeconds(180)).orElseThrow();
			assertThat(third.resolved()).isTrue();

			var row = alertRow(alert);
			assertThat(row.get("status")).isEqualTo("RESOLVED");
			assertThat(((java.sql.Timestamp) row.get("resolved_at")).toInstant())
					.isEqualTo(T0.plusSeconds(180));
		}

		@Test
		@DisplayName("a detection part-way through resets the streak")
		void detectionResetsStreak() {
			long bin = newBin();
			long alert = hot(bin, 0, 0, "21.0", T0).alertId();

			lifecycle.conditionClear(alert, T0.plusSeconds(60));
			lifecycle.conditionClear(alert, T0.plusSeconds(120));
			hot(bin, 0, 0, "20.5", T0.plusSeconds(180));
			lifecycle.conditionClear(alert, T0.plusSeconds(240));
			var clear = lifecycle.conditionClear(alert, T0.plusSeconds(300)).orElseThrow();

			assertThat(clear.resolved()).isFalse();
			assertThat(alertRow(alert).get("clear_streak")).isEqualTo(2);
		}

		@Test
		@DisplayName("an acknowledged alert still auto-resolves, and a detection leaves it acknowledged")
		void acknowledgedAlertsResolve() {
			long bin = newBin();
			long alert = hot(bin, 0, 0, "21.0", T0).alertId();
			acknowledge(alert);

			hot(bin, 0, 0, "22.0", T0.plusSeconds(60));
			assertThat(alertRow(alert).get("status")).isEqualTo("ACKNOWLEDGED");

			lifecycle.conditionClear(alert, T0.plusSeconds(120));
			lifecycle.conditionClear(alert, T0.plusSeconds(180));
			var third = lifecycle.conditionClear(alert, T0.plusSeconds(240)).orElseThrow();

			assertThat(third.resolved()).isTrue();
			assertThat(alertRow(alert).get("status")).isEqualTo("RESOLVED");
		}

		@Test
		@DisplayName("a condition that returns after resolving opens a new alert; history is kept")
		void recurrenceOpensNewRow() {
			long bin = newBin();
			long first = hot(bin, 0, 0, "21.0", T0).alertId();
			for (int i = 1; i <= 3; i++) {
				lifecycle.conditionClear(first, T0.plusSeconds(60L * i));
			}

			var second = hot(bin, 0, 0, "21.0", T0.plusSeconds(600));

			assertThat(second.opened()).isTrue();
			assertThat(second.alertId()).isNotEqualTo(first);
			assertThat(countAlerts(bin)).isEqualTo(2);
		}

		@Test
		@DisplayName("clearing an already resolved alert does nothing")
		void clearingResolvedAlertIsNoOp() {
			long bin = newBin();
			long alert = hot(bin, 0, 0, "21.0", T0).alertId();
			for (int i = 1; i <= 3; i++) {
				lifecycle.conditionClear(alert, T0.plusSeconds(60L * i));
			}

			assertThat(lifecycle.conditionClear(alert, T0.plusSeconds(600))).isEmpty();
			assertThat(alertRow(alert).get("clear_streak")).isEqualTo(3);
		}

		@Test
		@DisplayName("one device recovering does not resolve another device's outage")
		void offlineResolvesPerDevice() {
			long bin = newBin();
			long alertA = lifecycle.deviceOfflineDetected(bin, newDevice(bin), T0).alertId();
			long alertB = lifecycle.deviceOfflineDetected(bin, newDevice(bin), T0).alertId();

			for (int i = 1; i <= 3; i++) {
				lifecycle.conditionClear(alertA, T0.plusSeconds(60L * i));
			}

			assertThat(alertRow(alertA).get("status")).isEqualTo("RESOLVED");
			assertThat(alertRow(alertB).get("status")).isEqualTo("OPEN");
		}
	}

	// -----------------------------------------------------------------------
	// reporting
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("transition reporting")
	class Reporting {

		@Test
		@DisplayName("counts an alert opening and resolving, but not a repeat detection or a partial clear")
		void countsOnlyRealTransitions() {
			long bin = newBin();
			double openedBefore = transitions(AlertType.HIGH_MOISTURE, AlertStatus.OPEN);
			double resolvedBefore = transitions(AlertType.HIGH_MOISTURE, AlertStatus.RESOLVED);

			long alert = lifecycle.sensorConditionDetected(bin, AlertType.HIGH_MOISTURE, 0, 5,
					new BigDecimal("15.0"), new BigDecimal("14.5"), T0).alertId();
			lifecycle.sensorConditionDetected(bin, AlertType.HIGH_MOISTURE, 0, 5,
					new BigDecimal("15.2"), new BigDecimal("14.5"), T0.plusSeconds(60));
			lifecycle.conditionClear(alert, T0.plusSeconds(120));
			lifecycle.conditionClear(alert, T0.plusSeconds(180));

			assertThat(transitions(AlertType.HIGH_MOISTURE, AlertStatus.OPEN)).isEqualTo(openedBefore + 1);
			assertThat(transitions(AlertType.HIGH_MOISTURE, AlertStatus.RESOLVED)).isEqualTo(resolvedBefore);

			lifecycle.conditionClear(alert, T0.plusSeconds(240));
			assertThat(transitions(AlertType.HIGH_MOISTURE, AlertStatus.RESOLVED)).isEqualTo(resolvedBefore + 1);
		}

		@Test
		@DisplayName("a transition inside a rolled-back transaction is never counted")
		void rollbackIsNotCounted() {
			long bin = newBin();
			double before = transitions(AlertType.RATE_OF_RISE, AlertStatus.OPEN);

			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				lifecycle.sensorConditionDetected(bin, AlertType.RATE_OF_RISE, 2, 2,
						new BigDecimal("2.4"), new BigDecimal("2.0"), T0);
				status.setRollbackOnly();
			});

			assertThat(countAlerts(bin)).isZero();
			assertThat(transitions(AlertType.RATE_OF_RISE, AlertStatus.OPEN)).isEqualTo(before);
		}

		@Test
		@DisplayName("every type and state has a counter before anything happens")
		void countersArePreRegistered() {
			// Otherwise Prometheus has no series until the first increment, and
			// Grafana shows "no data" instead of zero.
			for (AlertType type : AlertType.values()) {
				for (AlertStatus state : AlertStatus.values()) {
					assertThat(meters.find(AlertTransitions.METRIC_NAME)
							.tag("type", type.name()).tag("to_state", state.name()).counter())
							.as("%s -> %s", type, state)
							.isNotNull();
				}
			}
		}

		@Test
		@DisplayName("logs each transition as JSON with its fields as separate keys")
		void logsStructuredJson(CapturedOutput output) {
			long bin = newBin();
			long device = newDevice(bin);
			long alert = lifecycle.deviceOfflineDetected(bin, device, T0).alertId();

			String line = output.getOut().lines()
					.filter(l -> l.contains("\"alertId\":" + alert))
					.findFirst()
					.orElseThrow(() -> new AssertionError("no structured log line for alert " + alert));

			assertThat(line)
					.startsWith("{")
					.contains("\"alertType\":\"DEVICE_OFFLINE\"")
					.contains("\"toState\":\"OPEN\"")
					.contains("\"binId\":" + bin)
					.contains("\"deviceId\":" + device);
		}
	}

	// -----------------------------------------------------------------------
	// plausibility, SQL form
	// -----------------------------------------------------------------------

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"-127.0", "-60.1", "-60.0", "-52.3", "-12.3", "0.0", "20.0", "65.0",
			"84.9", "85.0", "85.1", "100.0", "100.1"})
	@DisplayName("the SQL plausibility predicate agrees with the Java one")
	void sqlPredicateMatchesJava(String value) {
		BigDecimal temperature = new BigDecimal(value);
		boolean sql = jdbc.sql("SELECT " + SensorPlausibility.PLAUSIBLE_TEMPERATURE_SQL
						+ " FROM (SELECT CAST(? AS NUMERIC(4,1)) AS temperature_c) reading")
				.param(temperature)
				.query(Boolean.class)
				.single();

		assertThat(sql).isEqualTo(SensorPlausibility.isPlausibleTemperature(temperature));
	}
}
