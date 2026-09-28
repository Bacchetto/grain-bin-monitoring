package com.grainbin.telemetry.ingest;

import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics Grafana's dashboard is built on, read the way Prometheus reads
 * them: by scraping {@code /actuator/prometheus} as text.
 *
 * <p>Asserted as differences, before and after, because the counters are
 * shared with every other web test in the same context.
 */
class IngestMetricsIntegrationTest extends WebIntegrationTest {

	private String deviceKey;

	@BeforeEach
	void registerDevice() {
		long binId = bodyOf(postAsAdmin("/api/v1/bins", """
				{"name": "Metrics %d", "site": "Metrics Yard", "grainType": "canola"}
				""".formatted(System.nanoTime()))).get("id").asLong();
		this.deviceKey = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}")).get("apiKey").asString();
	}

	private String scrape() {
		var result = client.get().uri("/actuator/prometheus").exchange().returnResult(String.class);
		assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
		return result.getResponseBody();
	}

	/** The value of {@code ingest_readings_total{outcome="..."}} in a scrape. */
	private static double readings(String scrape, String outcome) {
		Matcher m = Pattern.compile("(?m)^ingest_readings_total\\{[^}]*outcome=\"" + outcome + "\"[^}]*} (\\S+)$")
				.matcher(scrape);
		assertThat(m.find()).as("ingest_readings_total{outcome=%s} in the scrape", outcome).isTrue();
		return Double.parseDouble(m.group(1));
	}

	private void send(long seq, Instant at) {
		String body = """
				{"samples": [{"seq": %d, "recordedAt": "%s", "sensors": [
				  {"cable": 0, "depth": 0, "temperatureC": 11.0},
				  {"cable": 0, "depth": 1, "temperatureC": 11.5}]}]}
				""".formatted(seq, at);
		assertThat(postReadings(this.deviceKey, body).getStatus()).isEqualTo(HttpStatus.ACCEPTED);
	}

	@Test
	@DisplayName("counts readings by outcome: accepted, duplicate, rejected")
	void countsReadingsByOutcome() {
		String before = scrape();
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		send(1, now.minusSeconds(60));                  // 2 readings stored
		send(1, now.minusSeconds(60));                  // the same 2 again: duplicates
		send(2, now.plus(1, ChronoUnit.HOURS));         // 2 from the future: rejected

		String after = scrape();
		assertThat(readings(after, "accepted") - readings(before, "accepted")).isEqualTo(2);
		assertThat(readings(after, "duplicate") - readings(before, "duplicate")).isEqualTo(2);
		assertThat(readings(after, "rejected") - readings(before, "rejected")).isEqualTo(2);
	}

	@Test
	@DisplayName("records ingest latency as histogram buckets, so Prometheus can compute percentiles")
	void latencyIsAHistogram() {
		send(1, Instant.now().minusSeconds(60));

		String scrape = scrape();

		// One line per bucket boundary ("le" = less than or equal), which is what
		// histogram_quantile() needs. Without the histogram setting there is only
		// a count, a sum and a max.
		assertThat(scrape).containsPattern(
				"(?m)^http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/v1/readings\"[^}]*le=\"[0-9.]+\"[^}]*} \\S+$");
		assertThat(scrape).containsPattern(
				"(?m)^http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/v1/readings\"[^}]*le=\"\\+Inf\"[^}]*} \\S+$");
	}

	@Test
	@DisplayName("every alert transition series exists before any alert has happened")
	void alertTransitionSeriesArePresent() {
		String scrape = scrape();

		for (String type : new String[] {"HIGH_TEMPERATURE", "HIGH_MOISTURE", "RATE_OF_RISE", "DEVICE_OFFLINE"}) {
			assertThat(scrape).containsPattern(
					"(?m)^alerts_transitions_total\\{[^}]*type=\"" + type + "\"[^}]*} \\S+$");
		}
	}
}
