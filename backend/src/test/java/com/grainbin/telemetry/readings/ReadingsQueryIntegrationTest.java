package com.grainbin.telemetry.readings;

import com.grainbin.telemetry.ingest.IngestRequest;
import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /bins/{id}/latest} and {@code GET /bins/{id}/readings}, over HTTP
 * against real PostgreSQL.
 *
 * <p>Readings are loaded through the real ingest endpoint rather than inserted
 * directly, so every test also exercises the path real data takes -- including
 * on-demand partition creation for the older months some tests use.
 */
class ReadingsQueryIntegrationTest extends WebIntegrationTest {

	@Autowired
	private ReadingQueryRepository repository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private long binId;
	private String deviceKey;

	@BeforeEach
	void createBinWithDevice() {
		this.binId = newBin();
		this.deviceKey = newDevice(this.binId);
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	private long newBin() {
		return bodyOf(postAsAdmin("/api/v1/bins", """
				{"name": "Query %d", "site": "Query Yard", "grainType": "wheat"}
				""".formatted(System.nanoTime()))).get("id").asLong();
	}

	private String newDevice(long bin) {
		return bodyOf(postAsAdmin("/api/v1/bins/" + bin + "/devices", "{}")).get("apiKey").asString();
	}

	private static long seq = 1;

	/** Posts one reading and asserts it was stored. */
	private void record(String key, int cable, int depth, Instant at, String temperature, String moisture) {
		var sensor = new IngestRequest.Sensor(cable, depth, new BigDecimal(temperature),
				(moisture == null) ? null : new BigDecimal(moisture));
		String body = this.json.writeValueAsString(
				new IngestRequest(List.of(new IngestRequest.Sample(seq++, at, List.of(sensor)))));

		EntityExchangeResult<String> result = postReadings(key, body);
		assertThat(result.getStatus()).as(result.getResponseBody()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(bodyOf(result).get("accepted").asInt()).isEqualTo(1);
	}

	private void record(int cable, int depth, Instant at, String temperature) {
		record(this.deviceKey, cable, depth, at, temperature, null);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	private EntityExchangeResult<String> history(String query) {
		return getAsAdmin("/api/v1/bins/" + this.binId + "/readings?" + query);
	}

	private static String range(Instant from, Instant to, String bucket) {
		return "from=" + from + "&to=" + to + "&bucket=" + bucket;
	}

	private static String errorField(JsonNode body) {
		return body.get("errors").get(0).get("field").asString();
	}

	// -----------------------------------------------------------------------
	// latest
	// -----------------------------------------------------------------------

	@Nested
	class Latest {

		private JsonNode latest() {
			EntityExchangeResult<String> result = getAsAdmin("/api/v1/bins/" + binId + "/latest");
			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
			return bodyOf(result);
		}

		@Test
		@DisplayName("returns the newest value per sensor, whatever order they arrived in")
		void newestValuePerSensor() {
			Instant t = now();
			record(0, 0, t.minus(Duration.ofMinutes(5)), "11.0");
			record(0, 0, t.minus(Duration.ofMinutes(30)), "10.0");   // older, sent later
			record(0, 1, t.minus(Duration.ofMinutes(10)), "12.0");

			JsonNode sensors = latest().get("sensors");

			assertThat(sensors.size()).isEqualTo(2);
			assertThat(sensors.get(0).get("cable").asInt()).isZero();
			assertThat(sensors.get(0).get("depth").asInt()).isZero();
			assertThat(sensors.get(0).get("temperatureC").asDouble()).isEqualTo(11.0);
			assertThat(Instant.parse(sensors.get(0).get("recordedAt").asString()))
					.isEqualTo(t.minus(Duration.ofMinutes(5)));
			assertThat(sensors.get(1).get("depth").asInt()).isEqualTo(1);
			assertThat(sensors.get(1).get("temperatureC").asDouble()).isEqualTo(12.0);
		}

		@Test
		@DisplayName("a sensor silent for longer than the lookback is left out, not shown stale")
		void staleSensorIsOmitted() {
			record(0, 0, now().minus(Duration.ofMinutes(5)), "11.0");
			record(1, 0, now().minus(Duration.ofDays(8)), "9.0");   // dead for 8 days

			JsonNode body = latest();

			assertThat(body.get("sensors").size()).isEqualTo(1);
			assertThat(body.get("sensors").get(0).get("cable").asInt()).isZero();

			// The response says where the window starts, so a client can tell
			// "no recent data" apart from "no such sensor".
			Instant since = Instant.parse(body.get("since").asString());
			assertThat(since).isBetween(now().minus(Duration.ofDays(7)).minusSeconds(5),
					now().minus(Duration.ofDays(7)).plusSeconds(5));
		}

		@Test
		@DisplayName("with two devices on a bin, each position shows the newest value from either")
		void newestAcrossDevices() {
			String secondDevice = newDevice(binId);
			Instant t = now();
			record(deviceKey, 0, 0, t.minus(Duration.ofMinutes(20)), "10.0", null);
			record(secondDevice, 0, 0, t.minus(Duration.ofMinutes(2)), "14.0", null);
			record(deviceKey, 2, 0, t.minus(Duration.ofMinutes(3)), "12.0", null);

			JsonNode sensors = latest().get("sensors");

			assertThat(sensors.size()).isEqualTo(2);
			assertThat(sensors.get(0).get("temperatureC").asDouble()).isEqualTo(14.0);
			assertThat(sensors.get(1).get("cable").asInt()).isEqualTo(2);
		}

		@Test
		@DisplayName("moisture is passed through, and null where a sensor has none")
		void moistureIsPassedThrough() {
			Instant t = now().minus(Duration.ofMinutes(1));
			record(deviceKey, 0, 0, t, "11.0", "13.7");
			record(deviceKey, 0, 1, t, "11.5", null);

			JsonNode sensors = latest().get("sensors");

			assertThat(sensors.get(0).get("moisturePct").asDouble()).isEqualTo(13.7);
			assertThat(sensors.get(1).get("moisturePct").isNull()).isTrue();
		}

		@Test
		@DisplayName("readings from other bins never appear")
		void otherBinsAreExcluded() {
			long otherBin = newBin();
			record(newDevice(otherBin), 5, 5, now().minus(Duration.ofMinutes(1)), "30.0", null);

			assertThat(latest().get("sensors").isEmpty()).isTrue();
		}

		@Test
		@DisplayName("a bin with no readings returns an empty list, not an error")
		void emptyBin() {
			JsonNode body = latest();

			assertThat(body.get("binId").asLong()).isEqualTo(binId);
			assertThat(body.get("sensors").isEmpty()).isTrue();
		}

		@Test
		@DisplayName("an unknown bin is a 404")
		void unknownBin() {
			assertThat(getAsAdmin("/api/v1/bins/999999/latest").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	// -----------------------------------------------------------------------
	// history
	// -----------------------------------------------------------------------

	@Nested
	class History {

		@Test
		@DisplayName("hourly buckets carry average, minimum, maximum and count per sensor")
		void hourlyAggregates() {
			Instant hour = now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
			record(deviceKey, 0, 0, hour.plus(Duration.ofMinutes(5)), "10.0", "13.0");
			record(deviceKey, 0, 0, hour.plus(Duration.ofMinutes(35)), "12.0", "14.0");
			record(deviceKey, 0, 0, hour.plus(Duration.ofMinutes(70)), "20.0", null);

			JsonNode body = bodyOf(history(range(hour, hour.plus(Duration.ofHours(2)), "hour")));
			JsonNode points = body.get("series").get(0).get("points");

			assertThat(body.get("bucket").asString()).isEqualTo("hour");
			assertThat(points.size()).isEqualTo(2);

			JsonNode first = points.get(0);
			assertThat(Instant.parse(first.get("bucketStart").asString())).isEqualTo(hour);
			assertThat(first.get("readings").asInt()).isEqualTo(2);
			assertThat(first.get("temperatureC").get("avg").asDouble()).isEqualTo(11.0);
			assertThat(first.get("temperatureC").get("min").asDouble()).isEqualTo(10.0);
			assertThat(first.get("temperatureC").get("max").asDouble()).isEqualTo(12.0);
			assertThat(first.get("moisturePct").get("avg").asDouble()).isEqualTo(13.5);

			JsonNode second = points.get(1);
			assertThat(Instant.parse(second.get("bucketStart").asString())).isEqualTo(hour.plus(Duration.ofHours(1)));
			assertThat(second.get("readings").asInt()).isEqualTo(1);
			// No reading in this bucket carried moisture: null, not three nulls.
			assertThat(second.get("moisturePct").isNull()).isTrue();
		}

		@Test
		@DisplayName("averages are rounded to two decimal places")
		void averagesAreRounded() {
			Instant hour = now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
			record(0, 0, hour.plusSeconds(60), "10.0");
			record(0, 0, hour.plusSeconds(120), "10.0");
			record(0, 0, hour.plusSeconds(180), "10.1");

			JsonNode avg = bodyOf(history(range(hour, hour.plus(Duration.ofHours(1)), "hour")))
					.get("series").get(0).get("points").get(0).get("temperatureC").get("avg");

			// 30.1 / 3 = 10.0333...; unrounded it would carry sixteen digits.
			assertThat(avg.asDouble()).isEqualTo(10.03);
		}

		@Test
		@DisplayName("one series per sensor, in cable then depth order")
		void seriesPerSensor() {
			Instant hour = now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
			record(1, 0, hour.plusSeconds(60), "12.0");
			record(0, 2, hour.plusSeconds(60), "11.0");
			record(0, 0, hour.plusSeconds(60), "10.0");

			JsonNode series = bodyOf(history(range(hour, hour.plus(Duration.ofHours(1)), "hour"))).get("series");

			assertThat(series.size()).isEqualTo(3);
			assertThat(List.of(
					series.get(0).get("cable").asInt() + ":" + series.get(0).get("depth").asInt(),
					series.get(1).get("cable").asInt() + ":" + series.get(1).get("depth").asInt(),
					series.get(2).get("cable").asInt() + ":" + series.get(2).get("depth").asInt()))
					.containsExactly("0:0", "0:2", "1:0");
		}

		@Test
		@DisplayName("the range is half-open: from is included, to is not")
		void rangeIsHalfOpen() {
			Instant from = now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
			Instant to = from.plus(Duration.ofHours(1));
			record(0, 0, from, "10.0");
			record(0, 0, to, "99.0");

			JsonNode points = bodyOf(history(range(from, to, "hour")))
					.get("series").get(0).get("points");

			// The reading at `to` would fall in the NEXT hour's bucket, so an
			// inclusive range would show up as a second point, not as a changed
			// first one. Asserting a single point is what actually pins the
			// exclusive upper bound. Adjacent requests for [a, b) and [b, c)
			// must never count one reading twice.
			assertThat(points.size()).isEqualTo(1);
			assertThat(points.get(0).get("readings").asInt()).isEqualTo(1);
			assertThat(points.get(0).get("temperatureC").get("max").asDouble()).isEqualTo(10.0);
		}

		@Test
		@DisplayName("daily buckets start at midnight UTC")
		void dailyBuckets() {
			Instant day = now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.DAYS);
			record(0, 0, day.plus(Duration.ofHours(1)), "10.0");
			record(0, 0, day.plus(Duration.ofHours(23)), "12.0");
			record(0, 0, day.plus(Duration.ofHours(25)), "14.0");

			JsonNode points = bodyOf(history(range(day, day.plus(Duration.ofDays(2)), "day")))
					.get("series").get(0).get("points");

			assertThat(points.size()).isEqualTo(2);
			assertThat(Instant.parse(points.get(0).get("bucketStart").asString())).isEqualTo(day);
			assertThat(points.get(0).get("readings").asInt()).isEqualTo(2);
			assertThat(Instant.parse(points.get(1).get("bucketStart").asString())).isEqualTo(day.plus(Duration.ofDays(1)));
		}

		@Test
		@DisplayName("daily buckets stay aligned to UTC even when the database session is not")
		void dailyBucketsIgnoreTheSessionTimeZone() {
			Instant day = now().minus(Duration.ofDays(12)).truncatedTo(ChronoUnit.DAYS);
			// 01:00 and 23:00 UTC on the same UTC day. In Edmonton, 01:00 UTC is
			// still the previous evening, so a local-time truncation would split
			// these into two days.
			record(0, 0, day.plus(Duration.ofHours(1)), "10.0");
			record(0, 0, day.plus(Duration.ofHours(23)), "12.0");

			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				// Same connection for everything below, with a non-UTC session.
				jdbc.sql("SET LOCAL TIME ZONE 'America/Edmonton'").update();
				assertThat(jdbc.sql("SHOW TIME ZONE").query(String.class).single()).isEqualTo("America/Edmonton");

				// Control: the naive two-argument form really does split the day
				// in this session. Without this, the assertion below could pass
				// simply because the session was UTC after all.
				int naiveDays = jdbc.sql("""
						SELECT count(DISTINCT date_trunc('day', recorded_at))
						FROM readings WHERE bin_id = ?
						""").param(binId).query(Integer.class).single();
				assertThat(naiveDays).as("the trap is real").isEqualTo(2);

				// The repository's query is not fooled.
				List<ReadingHistoryResponse.Point> points = repository
						.history(binId, day, day.plus(Duration.ofDays(1)), Bucket.DAY)
						.getFirst().points();
				assertThat(points).hasSize(1);
				assertThat(points.getFirst().bucketStart()).isEqualTo(day);
				assertThat(points.getFirst().readings()).isEqualTo(2);
			});
		}

		@Test
		@DisplayName("bucket defaults to hour and is case-insensitive")
		void bucketParsing() {
			Instant from = now().minus(Duration.ofDays(1));
			Instant to = now();

			assertThat(bodyOf(history("from=" + from + "&to=" + to)).get("bucket").asString()).isEqualTo("hour");
			assertThat(bodyOf(history(range(from, to, "DAY"))).get("bucket").asString()).isEqualTo("day");
		}

		@Test
		@DisplayName("a bin with no readings in range returns no series")
		void emptyRange() {
			JsonNode body = bodyOf(history(range(now().minus(Duration.ofDays(1)), now(), "hour")));

			assertThat(body.get("series").isEmpty()).isTrue();
		}
	}

	// -----------------------------------------------------------------------
	// history: rejected requests
	// -----------------------------------------------------------------------

	@Nested
	class HistoryValidation {

		@Test
		@DisplayName("from must be earlier than to")
		void fromMustPrecedeTo() {
			Instant t = now();
			EntityExchangeResult<String> result = history(range(t, t, "hour"));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(errorField(bodyOf(result))).isEqualTo("from");
		}

		@Test
		@DisplayName("an unknown bucket is rejected and the allowed values are named")
		void unknownBucket() {
			EntityExchangeResult<String> result = history(range(now().minus(Duration.ofDays(1)), now(), "week"));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			JsonNode error = bodyOf(result).get("errors").get(0);
			assertThat(error.get("field").asString()).isEqualTo("bucket");
			assertThat(error.get("message").asString()).contains("hour").contains("day");
		}

		@Test
		@DisplayName("too many buckets is rejected, with advice")
		void tooManyBuckets() {
			// 60 days of hourly buckets is 1,440 per sensor, over the cap of 1,000.
			EntityExchangeResult<String> result = history(range(now().minus(Duration.ofDays(60)), now(), "hour"));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(bodyOf(result).get("errors").get(0).get("message").asString()).contains("wider bucket");

			// The same range at daily resolution is fine.
			assertThat(history(range(now().minus(Duration.ofDays(60)), now(), "day")).getStatus())
					.isEqualTo(HttpStatus.OK);
		}

		@Test
		@DisplayName("a missing from is a 400")
		void missingFrom() {
			assertThat(history("to=" + now()).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("an unparseable timestamp is a 400")
		void unparseableTimestamp() {
			assertThat(history("from=yesterday&to=" + now()).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("an unknown bin is a 404")
		void unknownBin() {
			assertThat(getAsAdmin("/api/v1/bins/999999/readings?" + range(now().minus(Duration.ofDays(1)), now(), "hour"))
					.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}
}
