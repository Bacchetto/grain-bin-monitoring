package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code HIGH_TEMPERATURE} and {@code HIGH_MOISTURE}, evaluated on ingest.
 *
 * <p>Driven through {@code POST /api/v1/readings}, not by calling the
 * evaluator directly, because the rules about what counts as an evaluation
 * depend on what ingest passes it: accepted rows only, never duplicates or
 * rejected samples. Calling the evaluator directly would skip exactly the
 * part most likely to be wrong.
 *
 * <p>Each test registers its own bin, which keeps the README default
 * thresholds: 20.0 degrees and 14.5 percent.
 */
class ThresholdAlertIntegrationTest extends WebIntegrationTest {

	private long binId;
	private String deviceKey;

	/** Sample times advance from here, one minute per sample. */
	private Instant base;
	private long nextSeq;

	@BeforeEach
	void registerDevice() {
		JsonNode bin = bodyOf(postAsAdmin("/api/v1/bins", """
				{"name": "Threshold %d", "site": "Threshold Yard", "grainType": "canola"}
				""".formatted(System.nanoTime())));
		this.binId = bin.get("id").asLong();
		this.deviceKey = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}")).get("apiKey").asString();
		this.base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
		this.nextSeq = 1;
	}

	// -----------------------------------------------------------------------
	// building batches
	// -----------------------------------------------------------------------

	/** One sensor's value in a sample. Moisture may be null. */
	private record Sensor(int cable, int depth, String temperature, String moisture) {

		String json() {
			return """
					{"cable": %d, "depth": %d, "temperatureC": %s%s}"""
					.formatted(cable, depth, temperature, moisture == null ? "" : ", \"moisturePct\": " + moisture);
		}
	}

	private record Sample(long seq, Instant recordedAt, List<Sensor> sensors) {

		String json() {
			return """
					{"seq": %d, "recordedAt": "%s", "sensors": [%s]}"""
					.formatted(seq, recordedAt, sensors.stream().map(Sensor::json).collect(Collectors.joining(", ")));
		}
	}

	private static Sensor temp(int cable, int depth, String temperature) {
		return new Sensor(cable, depth, temperature, null);
	}

	private static Sensor wet(int cable, int depth, String moisture) {
		return new Sensor(cable, depth, "12.0", moisture);
	}

	/** The next sample in time, one minute after the previous one. */
	private Sample next(Sensor... sensors) {
		long seq = this.nextSeq++;
		return new Sample(seq, this.base.plus(seq, ChronoUnit.MINUTES), List.of(sensors));
	}

	private JsonNode send(Sample... samples) {
		String body = "{\"samples\": [" + Stream.of(samples).map(Sample::json).collect(Collectors.joining(", ")) + "]}";
		EntityExchangeResult<String> result = postReadings(this.deviceKey, body);
		assertThat(result.getStatus()).as(result.getResponseBody()).isEqualTo(HttpStatus.ACCEPTED);
		return bodyOf(result);
	}

	// -----------------------------------------------------------------------
	// reading alerts back
	// -----------------------------------------------------------------------

	private List<Map<String, Object>> alerts() {
		return this.jdbc.sql("SELECT * FROM alerts WHERE bin_id = ? ORDER BY id").param(this.binId).query().listOfRows();
	}

	private Map<String, Object> onlyAlert() {
		List<Map<String, Object>> alerts = alerts();
		assertThat(alerts).hasSize(1);
		return alerts.getFirst();
	}

	// -----------------------------------------------------------------------
	// detection
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("detection")
	class Detection {

		@Test
		@DisplayName("a temperature above the bin's threshold opens HIGH_TEMPERATURE for that sensor")
		void highTemperatureOpens() {
			send(next(temp(1, 3, "21.4"), temp(1, 4, "12.0")));

			var alert = onlyAlert();
			assertThat(alert.get("type")).isEqualTo("HIGH_TEMPERATURE");
			assertThat(alert.get("status")).isEqualTo("OPEN");
			assertThat(alert.get("cable_index")).isEqualTo(1);
			assertThat(alert.get("depth_index")).isEqualTo(3);
			assertThat((BigDecimal) alert.get("trigger_value")).isEqualByComparingTo("21.4");
			assertThat((BigDecimal) alert.get("threshold_value")).isEqualByComparingTo("20.0");
		}

		@Test
		@DisplayName("moisture above the threshold opens HIGH_MOISTURE")
		void highMoistureOpens() {
			send(next(wet(0, 5, "15.1")));

			var alert = onlyAlert();
			assertThat(alert.get("type")).isEqualTo("HIGH_MOISTURE");
			assertThat((BigDecimal) alert.get("trigger_value")).isEqualByComparingTo("15.1");
		}

		@Test
		@DisplayName("a value exactly at the threshold is not above it")
		void atThresholdIsNotABreach() {
			send(next(new Sensor(0, 0, "20.0", "14.5")));

			assertThat(alerts()).isEmpty();
		}

		@Test
		@DisplayName("a repeat detection refreshes the alert rather than adding one")
		void repeatDetectionRefreshes() {
			send(next(temp(0, 0, "21.0")));
			send(next(temp(0, 0, "23.5")));

			var alert = onlyAlert();
			assertThat((BigDecimal) alert.get("trigger_value")).isEqualByComparingTo("23.5");
		}

		@Test
		@DisplayName("the bin's own threshold is used, not the default")
		void usesBinThreshold() {
			patchAsAdmin("/api/v1/bins/" + binId + "/thresholds", "{\"maxTemperatureC\": 25.0}");

			send(next(temp(0, 0, "22.0")));
			assertThat(alerts()).isEmpty();

			send(next(temp(0, 0, "25.1")));
			assertThat((BigDecimal) onlyAlert().get("threshold_value")).isEqualByComparingTo("25.0");
		}

		@Test
		@DisplayName("a sample rejected for its timestamp is not evaluated")
		void rejectedSamplesAreNotEvaluated() {
			var future = new Sample(nextSeq++, Instant.now().plus(1, ChronoUnit.HOURS), List.of(temp(0, 0, "30.0")));

			JsonNode response = send(future);

			assertThat(response.get("rejected").asInt()).isEqualTo(1);
			assertThat(alerts()).isEmpty();
		}

		@Test
		@DisplayName("a reading with no moisture value says nothing about moisture")
		void missingMoistureIsNoEvaluation() {
			send(next(wet(0, 5, "15.1")));
			for (int i = 0; i < 3; i++) {
				send(next(temp(0, 5, "12.0")));
			}

			assertThat(onlyAlert().get("status")).isEqualTo("OPEN");
			assertThat(onlyAlert().get("clear_streak")).isEqualTo(0);
		}
	}

	// -----------------------------------------------------------------------
	// resolution, and what counts as an evaluation
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("what counts as an evaluation")
	class Evaluations {

		@Test
		@DisplayName("three clear batches resolve the alert; two do not")
		void threeClearBatchesResolve() {
			send(next(temp(0, 0, "21.0")));
			send(next(temp(0, 0, "18.0")));
			send(next(temp(0, 0, "18.0")));
			assertThat(onlyAlert().get("status")).isEqualTo("OPEN");

			send(next(temp(0, 0, "18.0")));
			assertThat(onlyAlert().get("status")).isEqualTo("RESOLVED");
		}

		@Test
		@DisplayName("a resent batch of duplicates does not advance the clear streak")
		void duplicatesDoNotCount() {
			send(next(temp(0, 0, "21.0")));
			Sample clear = next(temp(0, 0, "18.0"));
			send(clear);

			JsonNode resend = send(clear);
			send(clear);

			assertThat(resend.get("duplicates").asInt()).isEqualTo(1);
			assertThat(onlyAlert().get("clear_streak")).isEqualTo(1);
			assertThat(onlyAlert().get("status")).isEqualTo("OPEN");
		}

		@Test
		@DisplayName("a backlog of several clear samples is one evaluation, not several")
		void backlogIsOneEvaluation() {
			send(next(temp(0, 0, "21.0")));

			send(next(temp(0, 0, "18.0")), next(temp(0, 0, "18.1")), next(temp(0, 0, "18.2")));

			assertThat(onlyAlert().get("clear_streak")).isEqualTo(1);
			assertThat(onlyAlert().get("status")).isEqualTo("OPEN");
		}

		@Test
		@DisplayName("within a batch, the newest reading decides, whatever order the samples arrive in")
		void newestInBatchDecides() {
			Sample older = next(temp(0, 0, "25.0"));
			Sample newer = next(temp(0, 0, "18.0"));

			send(newer, older);

			assertThat(alerts()).isEmpty();
		}

		@Test
		@DisplayName("a batch that arrives after newer data is not evaluated")
		void lateBatchIsNotEvaluated() {
			Sample older = next(temp(0, 0, "25.0"));
			Sample newer = next(temp(0, 0, "18.0"));

			send(newer);
			send(older);

			assertThat(alerts()).isEmpty();
		}

		@Test
		@DisplayName("a late clear reading does not count towards resolving")
		void lateClearDoesNotCount() {
			Sample olderClear = next(temp(0, 0, "18.0"));
			Sample newerHot = next(temp(0, 0, "21.0"));

			send(newerHot);
			send(olderClear);

			assertThat(onlyAlert().get("clear_streak")).isEqualTo(0);
		}

		@Test
		@DisplayName("sensors are judged independently within one batch")
		void sensorsAreIndependent() {
			send(next(temp(0, 0, "21.0"), temp(0, 1, "21.0")));

			send(next(temp(0, 0, "18.0"), temp(0, 1, "22.0")));

			var byDepth = alerts().stream().collect(Collectors.toMap(a -> a.get("depth_index"), a -> a));
			assertThat(byDepth.get(0).get("clear_streak")).isEqualTo(1);
			assertThat(byDepth.get(1).get("clear_streak")).isEqualTo(0);
		}
	}

	// -----------------------------------------------------------------------
	// probe faults
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("probe fault values")
	class ProbeFaults {

		@Test
		@DisplayName("the 85.0 power-on value does not open an alert")
		void powerOnValueIgnored() {
			send(next(temp(0, 0, "85.0")));

			assertThat(alerts()).isEmpty();
		}

		@Test
		@DisplayName("a fault value neither opens an alert nor counts as clear")
		void faultIsNeitherDetectionNorClear() {
			send(next(temp(0, 0, "21.0")));
			for (int i = 0; i < 3; i++) {
				send(next(temp(0, 0, "-127.0")));
			}

			assertThat(onlyAlert().get("status")).isEqualTo("OPEN");
			assertThat(onlyAlert().get("clear_streak")).isEqualTo(0);
		}

		@Test
		@DisplayName("a genuinely hot 85.1 still alerts")
		void hotButNotFaultAlerts() {
			send(next(temp(0, 0, "85.1")));

			assertThat(onlyAlert().get("type")).isEqualTo("HIGH_TEMPERATURE");
		}

		@Test
		@DisplayName("moisture is still judged when the temperature is a fault value")
		void moistureJudgedDespiteTemperatureFault() {
			send(next(new Sensor(0, 5, "-127.0", "15.3")));

			assertThat(onlyAlert().get("type")).isEqualTo("HIGH_MOISTURE");
		}
	}
}
