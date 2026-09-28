package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.support.WebIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/alerts} and {@code POST /api/v1/alerts/{id}/acknowledge},
 * over HTTP.
 *
 * <p>Alerts are set up through {@link AlertLifecycle}, the same path the
 * evaluators use, so the rows are exactly what the engine writes. Each test
 * makes its own bin and filters by it, since the alerts table is shared with
 * every other web test.
 */
class AlertApiIntegrationTest extends WebIntegrationTest {

	private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS);

	@Autowired
	private AlertLifecycle lifecycle;

	@Autowired
	private MeterRegistry meters;

	private long binId;
	private String binName;

	@BeforeEach
	void createBin() {
		this.binName = "Alerts " + System.nanoTime();
		this.binId = newBin(this.binName);
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private long newBin(String name) {
		return bodyOf(postAsAdmin("/api/v1/bins", """
				{"name": "%s", "site": "Alert API Yard", "grainType": "canola"}
				""".formatted(name))).get("id").asLong();
	}

	private long newDevice(long bin) {
		return bodyOf(postAsAdmin("/api/v1/bins/" + bin + "/devices", "{}")).get("id").asLong();
	}

	/** A HIGH_TEMPERATURE alert on (0, depth), detected {@code minutesAfterT0} after T0. */
	private long hot(long bin, int depth, long minutesAfterT0) {
		return lifecycle.sensorConditionDetected(bin, AlertType.HIGH_TEMPERATURE, 0, depth,
				new BigDecimal("21.5"), new BigDecimal("20.0"), T0.plus(minutesAfterT0, ChronoUnit.MINUTES)).alertId();
	}

	private void resolve(long alertId) {
		for (int i = 1; i <= AlertRepository.CLEAR_EVALUATIONS_TO_RESOLVE; i++) {
			lifecycle.conditionClear(alertId, T0.plus(i, ChronoUnit.HOURS));
		}
	}

	private JsonNode list(String query) {
		EntityExchangeResult<String> result = getAsAdmin("/api/v1/alerts?" + query);
		assertThat(result.getStatus()).as(result.getResponseBody()).isEqualTo(HttpStatus.OK);
		return bodyOf(result);
	}

	private List<Long> ids(JsonNode alerts) {
		List<Long> ids = new ArrayList<>();
		alerts.forEach(alert -> ids.add(alert.get("id").asLong()));
		return ids;
	}

	private EntityExchangeResult<String> acknowledge(long alertId) {
		return postAsAdminWithoutBody("/api/v1/alerts/" + alertId + "/acknowledge");
	}

	// -----------------------------------------------------------------------
	// listing
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("GET /alerts")
	class Listing {

		@Test
		@DisplayName("returns a sensor alert with its bin name, position and values")
		void sensorAlertShape() {
			hot(binId, 3, 0);

			JsonNode alert = list("binId=" + binId).get(0);

			assertThat(alert.get("binId").asLong()).isEqualTo(binId);
			assertThat(alert.get("binName").asString()).isEqualTo(binName);
			assertThat(alert.get("type").asString()).isEqualTo("HIGH_TEMPERATURE");
			assertThat(alert.get("status").asString()).isEqualTo("OPEN");
			assertThat(alert.get("cableIndex").asInt()).isZero();
			assertThat(alert.get("depthIndex").asInt()).isEqualTo(3);
			assertThat(alert.get("triggerValue").decimalValue()).isEqualByComparingTo("21.5");
			assertThat(alert.get("thresholdValue").decimalValue()).isEqualByComparingTo("20.0");
			assertThat(alert.get("deviceId").isNull()).isTrue();
			assertThat(Instant.parse(alert.get("firstDetectedAt").asString())).isEqualTo(T0);
		}

		@Test
		@DisplayName("returns an offline alert with its device and when it was last heard from")
		void offlineAlertShape() {
			long device = newDevice(binId);
			lifecycle.deviceOfflineDetected(binId, device, T0);

			JsonNode alert = list("binId=" + binId).get(0);

			assertThat(alert.get("type").asString()).isEqualTo("DEVICE_OFFLINE");
			assertThat(alert.get("deviceId").asLong()).isEqualTo(device);
			// Registered through the API and never reported.
			assertThat(alert.get("deviceLastSeenAt").isNull()).isTrue();
			assertThat(alert.get("cableIndex").isNull()).isTrue();
			assertThat(alert.get("triggerValue").isNull()).isTrue();
		}

		@Test
		@DisplayName("lists newest detection first")
		void newestFirst() {
			long older = hot(binId, 0, 0);
			long newer = hot(binId, 1, 10);
			long middle = hot(binId, 2, 5);

			assertThat(ids(list("binId=" + binId))).containsExactly(newer, middle, older);
		}

		@Test
		@DisplayName("filters by bin")
		void filtersByBin() {
			long mine = hot(binId, 0, 0);
			hot(newBin("Other " + System.nanoTime()), 0, 0);

			assertThat(ids(list("binId=" + binId))).containsExactly(mine);
		}

		@Test
		@DisplayName("an unknown bin is an empty list, not a 404")
		void unknownBinIsEmpty() {
			assertThat(list("binId=999999999")).isEmpty();
		}

		@Test
		@DisplayName("filters by one status, several comma-separated, or none at all")
		void filtersByStatus() {
			long open = hot(binId, 0, 0);
			long acknowledged = hot(binId, 1, 1);
			lifecycle.acknowledge(acknowledged, T0.plusSeconds(90));
			long resolved = hot(binId, 2, 2);
			resolve(resolved);

			assertThat(ids(list("binId=" + binId + "&status=open"))).containsExactly(open);
			assertThat(ids(list("binId=" + binId + "&status=resolved"))).containsExactly(resolved);
			assertThat(ids(list("binId=" + binId + "&status=open,acknowledged")))
					.containsExactlyInAnyOrder(open, acknowledged);
			assertThat(ids(list("binId=" + binId + "&status=OPEN&status=acknowledged")))
					.containsExactlyInAnyOrder(open, acknowledged);
			assertThat(ids(list("binId=" + binId))).containsExactlyInAnyOrder(open, acknowledged, resolved);
		}

		@Test
		@DisplayName("limit caps the list, keeping the newest")
		void limitKeepsNewest() {
			hot(binId, 0, 0);
			long second = hot(binId, 1, 1);
			long third = hot(binId, 2, 2);

			assertThat(ids(list("binId=" + binId + "&limit=2"))).containsExactly(third, second);
		}

		@Test
		@DisplayName("rejects an unknown status or an out-of-range limit with a 400 naming the parameter")
		void rejectsBadParameters() {
			for (String query : List.of("status=pending", "limit=0", "limit=" + (AlertController.MAX_LIMIT + 1))) {
				EntityExchangeResult<String> result = getAsAdmin("/api/v1/alerts?" + query);

				assertThat(result.getStatus()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(result.getResponseHeaders().getContentType())
						.isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
				assertThat(bodyOf(result).get("errors").get(0).get("field").asString())
						.isEqualTo(query.substring(0, query.indexOf('=')));
			}
		}
	}

	// -----------------------------------------------------------------------
	// acknowledging
	// -----------------------------------------------------------------------

	@Nested
	@DisplayName("POST /alerts/{id}/acknowledge")
	class Acknowledging {

		private double acknowledgedTransitions() {
			return meters.get(AlertTransitions.METRIC_NAME)
					.tag("type", AlertType.HIGH_TEMPERATURE.name())
					.tag("to_state", AlertStatus.ACKNOWLEDGED.name())
					.counter().count();
		}

		@Test
		@DisplayName("acknowledges an open alert and counts the transition")
		void acknowledgesOpenAlert() {
			long alert = hot(binId, 0, 0);
			double before = acknowledgedTransitions();

			EntityExchangeResult<String> result = acknowledge(alert);

			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
			JsonNode body = bodyOf(result);
			assertThat(body.get("status").asString()).isEqualTo("ACKNOWLEDGED");
			assertThat(body.get("acknowledgedAt").isNull()).isFalse();
			assertThat(acknowledgedTransitions()).isEqualTo(before + 1);
		}

		@Test
		@DisplayName("acknowledging twice is harmless: same answer, same timestamp, one transition")
		void acknowledgingTwiceIsIdempotent() {
			long alert = hot(binId, 0, 0);
			double before = acknowledgedTransitions();

			JsonNode first = bodyOf(acknowledge(alert));
			EntityExchangeResult<String> second = acknowledge(alert);

			assertThat(second.getStatus()).isEqualTo(HttpStatus.OK);
			assertThat(bodyOf(second).get("acknowledgedAt")).isEqualTo(first.get("acknowledgedAt"));
			assertThat(acknowledgedTransitions()).isEqualTo(before + 1);
		}

		@Test
		@DisplayName("a resolved alert cannot be acknowledged: 409")
		void resolvedIsConflict() {
			long alert = hot(binId, 0, 0);
			resolve(alert);

			EntityExchangeResult<String> result = acknowledge(alert);

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
			assertThat(bodyOf(result).get("detail").asString()).contains("already resolved");
		}

		@Test
		@DisplayName("an unknown alert is a 404")
		void unknownIsNotFound() {
			assertThat(acknowledge(999_999_999L).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@DisplayName("an acknowledged alert still resolves when its condition clears")
		void acknowledgedStillResolves() {
			long alert = hot(binId, 0, 0);
			acknowledge(alert);

			resolve(alert);

			assertThat(ids(list("binId=" + binId + "&status=resolved"))).containsExactly(alert);
		}
	}

	// -----------------------------------------------------------------------
	// authentication
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("both endpoints need the admin token")
	void requiresAdminToken() {
		long alert = hot(binId, 0, 0);

		assertThat(client.get().uri("/api/v1/alerts").exchange().returnResult(String.class).getStatus())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(client.post().uri("/api/v1/alerts/" + alert + "/acknowledge").exchange()
				.returnResult(String.class).getStatus())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(list("binId=" + binId).get(0).get("status").asString()).isEqualTo("OPEN");
	}
}
