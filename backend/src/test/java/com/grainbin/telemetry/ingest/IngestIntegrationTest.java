package com.grainbin.telemetry.ingest;

import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/readings}, driven over HTTP against real PostgreSQL.
 *
 * <p>Covers the three cases the README names explicitly for Milestone 1 --
 * duplicates, out-of-order samples, and a missing partition -- plus the batch
 * limit, future-timestamp rejection, and the {@code last_seen_at} rules the
 * Milestone 2 offline alert will depend on.
 *
 * <p>Every test registers its own device through the admin API, so tests
 * cannot see each other's readings and the registration path is exercised as a
 * device would really encounter it.
 */
class IngestIntegrationTest extends WebIntegrationTest {

	private record Device(long binId, long deviceId, String key) {
	}

	private Device device;

	@BeforeEach
	void registerDevice() {
		EntityExchangeResult<String> bin = postAsAdmin("/api/v1/bins", """
				{"name": "Ingest %d", "site": "Ingest Yard", "grainType": "canola"}
				""".formatted(System.nanoTime()));
		long binId = bodyOf(bin).get("id").asLong();

		JsonNode registered = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}"));
		this.device = new Device(binId, registered.get("id").asLong(), registered.get("apiKey").asString());
	}

	// -----------------------------------------------------------------------
	// building batches
	// -----------------------------------------------------------------------

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	private static IngestRequest.Sensor sensor(int cable, int depth, String temperature) {
		return new IngestRequest.Sensor(cable, depth, new BigDecimal(temperature), null);
	}

	private static IngestRequest.Sensor sensor(int cable, int depth, String temperature, String moisture) {
		return new IngestRequest.Sensor(cable, depth, new BigDecimal(temperature), new BigDecimal(moisture));
	}

	private static IngestRequest.Sample sample(long seq, Instant recordedAt, IngestRequest.Sensor... sensors) {
		return new IngestRequest.Sample(seq, recordedAt, List.of(sensors));
	}

	private String batch(IngestRequest.Sample... samples) {
		return this.json.writeValueAsString(new IngestRequest(List.of(samples)));
	}

	private EntityExchangeResult<String> send(String body) {
		return postReadings(this.device.key(), body);
	}

	private JsonNode sendAccepted(String body) {
		EntityExchangeResult<String> result = send(body);
		assertThat(result.getStatus()).as(result.getResponseBody()).isEqualTo(HttpStatus.ACCEPTED);
		return bodyOf(result);
	}

	private static void assertCounts(JsonNode response, int accepted, int duplicates, int rejected) {
		assertThat(response.get("accepted").asInt()).as("accepted").isEqualTo(accepted);
		assertThat(response.get("duplicates").asInt()).as("duplicates").isEqualTo(duplicates);
		assertThat(response.get("rejected").asInt()).as("rejected").isEqualTo(rejected);
	}

	// -----------------------------------------------------------------------
	// reading back
	// -----------------------------------------------------------------------

	private int storedReadings() {
		return this.jdbc.sql("SELECT count(*) FROM readings WHERE device_id = ?")
				.param(this.device.deviceId()).query(Integer.class).single();
	}

	/** Null for a device never seen -- which single() would refuse, so optional(). */
	private Instant lastSeen() {
		return this.jdbc.sql("SELECT last_seen_at FROM devices WHERE id = ?")
				.param(this.device.deviceId())
				.query(OffsetDateTime.class)
				.optional()
				.map(OffsetDateTime::toInstant)
				.orElse(null);
	}

	private boolean partitionExists(String name) {
		return this.jdbc.sql("SELECT to_regclass(?) IS NOT NULL")
				.param("public." + name).query(Boolean.class).single();
	}

	// -----------------------------------------------------------------------
	// the contract
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("the README's example payload is accepted verbatim, with the README's response")
	void readmeExampleIsAccepted() {
		// Literal JSON, not built from the DTOs. If a record component were ever
		// renamed, every other test here would keep passing -- they serialise
		// through the same record -- while every real device broke. This one
		// would not.
		EntityExchangeResult<String> result = send("""
				{
				  "samples": [
				    {
				      "seq": 1042,
				      "recordedAt": "%s",
				      "sensors": [
				        { "cable": 0, "depth": 0, "temperatureC": 11.4, "moisturePct": 13.9 },
				        { "cable": 0, "depth": 1, "temperatureC": 12.1, "moisturePct": 14.2 }
				      ]
				    }
				  ]
				}
				""".formatted(now()));

		assertThat(result.getStatus()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(result.getResponseHeaders().getContentType())
				.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
		assertCounts(bodyOf(result), 2, 0, 0);
	}

	@Test
	@DisplayName("values are stored as sent, attributed to the authenticated device and its bin")
	void storesWhatWasSent() {
		Instant recordedAt = now().minus(Duration.ofMinutes(2));
		sendAccepted(batch(sample(7, recordedAt, sensor(2, 3, "15.6", "13.2"))));

		var row = this.jdbc.sql("""
				SELECT device_id, bin_id, seq, cable_index, depth_index,
				       recorded_at, temperature_c, moisture_pct
				FROM readings WHERE device_id = ?
				""").param(this.device.deviceId()).query().singleRow();

		assertThat(row.get("device_id")).isEqualTo(this.device.deviceId());
		assertThat(row.get("bin_id")).isEqualTo(this.device.binId());
		assertThat(row.get("seq")).isEqualTo(7L);
		assertThat(((Number) row.get("cable_index")).intValue()).isEqualTo(2);
		assertThat(((Number) row.get("depth_index")).intValue()).isEqualTo(3);
		// A generic row map returns TIMESTAMPTZ as java.sql.Timestamp, not
		// OffsetDateTime; toInstant() is still the exact stored instant.
		assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant()).isEqualTo(recordedAt);
		assertThat(row.get("temperature_c")).hasToString("15.6");
		assertThat(row.get("moisture_pct")).hasToString("13.2");
	}

	@Test
	@DisplayName("moisture is optional and stored as NULL when absent")
	void moistureIsOptional() {
		assertCounts(sendAccepted(batch(sample(1, now(), sensor(0, 0, "11.0")))), 1, 0, 0);

		assertThat(this.jdbc.sql("SELECT moisture_pct IS NULL FROM readings WHERE device_id = ?")
				.param(this.device.deviceId()).query(Boolean.class).single())
				.isTrue();
	}

	@Test
	@DisplayName("ids in the body are ignored; the key alone decides where readings go")
	void bodyCannotChooseTheDeviceOrBin() {
		// A device that tries to name some other device or bin is ignored, not
		// obeyed. Unknown properties are dropped during deserialisation, and the
		// ids used for the insert come only from the authenticated key.
		sendAccepted("""
				{"deviceId": 1, "binId": 1,
				 "samples": [{"seq": 1, "recordedAt": "%s", "deviceId": 1, "binId": 1,
				   "sensors": [{"cable": 0, "depth": 0, "temperatureC": 11.0}]}]}
				""".formatted(now()));

		var row = this.jdbc.sql("SELECT device_id, bin_id FROM readings WHERE device_id = ?")
				.param(this.device.deviceId()).query().singleRow();
		assertThat(row.get("device_id")).isEqualTo(this.device.deviceId());
		assertThat(row.get("bin_id")).isEqualTo(this.device.binId());
	}

	// -----------------------------------------------------------------------
	// idempotency
	// -----------------------------------------------------------------------

	@Nested
	class Duplicates {

		@Test
		@DisplayName("a retried batch is reported as duplicates and stored once")
		void retriedBatchIsIdempotent() {
			String body = batch(sample(1, now(), sensor(0, 0, "11.0"), sensor(0, 1, "11.5")));

			assertCounts(sendAccepted(body), 2, 0, 0);
			assertCounts(sendAccepted(body), 0, 2, 0);

			assertThat(storedReadings()).isEqualTo(2);
		}

		@Test
		@DisplayName("a batch overlapping an earlier one stores only what is new")
		void overlappingBatchStoresOnlyNewReadings() {
			Instant t = now().minus(Duration.ofMinutes(10));
			sendAccepted(batch(sample(1, t, sensor(0, 0, "11.0")), sample(2, t.plusSeconds(60), sensor(0, 0, "11.1"))));

			// Sample 2 is a resend, sample 3 is new -- the typical shape of a
			// device retrying after a timeout that had in fact succeeded.
			JsonNode second = sendAccepted(batch(
					sample(2, t.plusSeconds(60), sensor(0, 0, "11.1")),
					sample(3, t.plusSeconds(120), sensor(0, 0, "11.2"))));

			assertCounts(second, 1, 1, 0);
			assertThat(storedReadings()).isEqualTo(3);
		}

		@Test
		@DisplayName("the same reading twice within one batch is stored once")
		void duplicateWithinOneBatch() {
			Instant t = now();
			assertCounts(sendAccepted(batch(sample(1, t, sensor(0, 0, "11.0")), sample(1, t, sensor(0, 0, "11.0")))),
					1, 1, 0);
			assertThat(storedReadings()).isEqualTo(1);
		}
	}

	// -----------------------------------------------------------------------
	// ordering and partitions
	// -----------------------------------------------------------------------

	@Nested
	class OrderingAndPartitions {

		@Test
		@DisplayName("samples arriving out of order are all stored, and ordered by recordedAt")
		void outOfOrderSamplesAreStored() {
			Instant t = now().minus(Duration.ofMinutes(30));

			// Sent 3, 1, 2. Nothing about arrival order is meaningful.
			assertCounts(sendAccepted(batch(
					sample(3, t.plusSeconds(120), sensor(0, 0, "13.0")),
					sample(1, t, sensor(0, 0, "11.0")),
					sample(2, t.plusSeconds(60), sensor(0, 0, "12.0")))), 3, 0, 0);

			List<Long> byRecordedAt = jdbc.sql("""
					SELECT seq FROM readings WHERE device_id = ? ORDER BY recorded_at
					""").param(device.deviceId()).query(Long.class).list();
			assertThat(byRecordedAt).containsExactly(1L, 2L, 3L);
		}

		@Test
		@DisplayName("a reading in a month with no partition creates one instead of failing")
		void missingPartitionIsCreatedOnDemand() {
			// March 2019 is years outside the window the migration and the
			// scheduled job create. Without the on-demand call this insert would
			// fail outright -- there is no default partition to fall back on.
			assertThat(partitionExists("readings_2019_03")).isFalse();

			assertCounts(sendAccepted(batch(sample(1, Instant.parse("2019-03-15T12:00:00Z"), sensor(0, 0, "8.0")))),
					1, 0, 0);

			assertThat(partitionExists("readings_2019_03")).isTrue();
			assertThat(jdbc.sql("SELECT tableoid::regclass::text FROM readings WHERE device_id = ?")
					.param(device.deviceId()).query(String.class).single())
					.isEqualTo("readings_2019_03");
		}

		@Test
		@DisplayName("one batch spanning a month boundary lands in both partitions")
		void batchAcrossMonthBoundary() {
			assertCounts(sendAccepted(batch(
					sample(1, Instant.parse("2019-05-31T23:59:59Z"), sensor(0, 0, "9.0")),
					sample(2, Instant.parse("2019-06-01T00:00:00Z"), sensor(0, 0, "9.1")))), 2, 0, 0);

			assertThat(jdbc.sql("""
					SELECT tableoid::regclass::text FROM readings WHERE device_id = ? ORDER BY recorded_at
					""").param(device.deviceId()).query(String.class).list())
					.containsExactly("readings_2019_05", "readings_2019_06");
		}
	}

	// -----------------------------------------------------------------------
	// future timestamps
	// -----------------------------------------------------------------------

	@Nested
	class FutureTimestamps {

		@Test
		@DisplayName("samples more than five minutes ahead are rejected; the rest of the batch is kept")
		void futureSamplesAreRejectedIndividually() {
			Instant t = now();

			JsonNode response = sendAccepted(batch(
					sample(1, t.plus(Duration.ofMinutes(10)), sensor(0, 0, "11.0"), sensor(0, 1, "11.1")),
					sample(2, t.plus(Duration.ofMinutes(4)), sensor(0, 0, "11.2")),
					sample(3, t, sensor(0, 0, "11.3"))));

			// Two sensors in the rejected sample, so two rejected readings.
			assertCounts(response, 2, 0, 2);
			assertThat(storedReadings()).isEqualTo(2);
		}

		@Test
		@DisplayName("the counts always add up to the number of readings sent")
		void countsAreConserved() {
			Instant t = now();
			String first = batch(sample(1, t, sensor(0, 0, "11.0")));
			sendAccepted(first);

			// 1 duplicate + 2 new + 3 future = 6 readings sent.
			JsonNode response = sendAccepted(batch(
					sample(1, t, sensor(0, 0, "11.0")),
					sample(2, t.minusSeconds(60), sensor(0, 0, "11.0"), sensor(0, 1, "11.0")),
					sample(3, t.plus(Duration.ofHours(1)), sensor(0, 0, "11.0"), sensor(0, 1, "11.0"),
							sensor(0, 2, "11.0"))));

			int total = response.get("accepted").asInt() + response.get("duplicates").asInt()
					+ response.get("rejected").asInt();
			assertThat(total).isEqualTo(6);
			assertCounts(response, 2, 1, 3);
		}
	}

	// -----------------------------------------------------------------------
	// last_seen_at -- what the Milestone 2 offline alert will read
	// -----------------------------------------------------------------------

	@Nested
	class LastSeen {

		@Test
		@DisplayName("a newly registered device has never been seen")
		void freshDeviceHasNotBeenSeen() {
			assertThat(lastSeen()).isNull();
		}

		@Test
		@DisplayName("storing a reading advances last_seen_at to the server's receive time")
		void acceptedReadingAdvancesLastSeen() {
			Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
			sendAccepted(batch(sample(1, now(), sensor(0, 0, "11.0"))));
			Instant after = Instant.now().plusSeconds(1);

			assertThat(lastSeen()).isBetween(before, after);
		}

		@Test
		@DisplayName("last_seen_at is the server's clock, not the device's")
		void lastSeenIgnoresTheDeviceClock() {
			// A reading recorded three days ago but received now. If
			// last_seen_at followed recorded_at, a device back-filling after an
			// outage -- or one whose clock simply runs slow -- would look
			// offline while it was actively reporting. See ADR 0005.
			Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
			sendAccepted(batch(sample(1, now().minus(Duration.ofDays(3)), sensor(0, 0, "11.0"))));
			Instant after = Instant.now().plusSeconds(1);

			assertThat(lastSeen()).isBetween(before, after);

			// ...while received_at records the same server time for the row.
			OffsetDateTime receivedAt = jdbc.sql("SELECT received_at FROM readings WHERE device_id = ?")
					.param(device.deviceId()).query(OffsetDateTime.class).single();
			assertThat(receivedAt.toInstant()).isBetween(before, after);
		}

		@Test
		@DisplayName("a batch of nothing but duplicates does not count as being seen")
		void duplicatesDoNotAdvanceLastSeen() {
			String body = batch(sample(1, now(), sensor(0, 0, "11.0")));
			sendAccepted(body);

			Instant pinned = Instant.parse("2020-01-01T00:00:00Z");
			jdbc.sql("UPDATE devices SET last_seen_at = ? WHERE id = ?")
					.param(pinned.atOffset(ZoneOffset.UTC)).param(device.deviceId()).update();

			// README: a device that connects but sends only duplicate data is
			// still offline from a data standpoint.
			assertCounts(sendAccepted(body), 0, 1, 0);
			assertThat(lastSeen()).isEqualTo(pinned);
		}

		@Test
		@DisplayName("a batch of nothing but rejected samples does not count as being seen")
		void rejectedDoNotAdvanceLastSeen() {
			assertCounts(sendAccepted(batch(sample(1, now().plus(Duration.ofHours(2)), sensor(0, 0, "11.0")))),
					0, 0, 1);

			assertThat(lastSeen()).isNull();
		}
	}

	// -----------------------------------------------------------------------
	// batch limit and validation
	// -----------------------------------------------------------------------

	@Nested
	class LimitsAndValidation {

		private String samples(int count) {
			Instant t = now().minus(Duration.ofHours(1));
			return batch(IntStream.range(0, count)
					.mapToObj(i -> sample(i, t.plusSeconds(i), sensor(0, 0, "11.0")))
					.toArray(IngestRequest.Sample[]::new));
		}

		@Test
		@DisplayName("exactly 500 samples is accepted")
		void batchAtTheLimitIsAccepted() {
			assertCounts(sendAccepted(samples(500)), 500, 0, 0);
		}

		@Test
		@DisplayName("501 samples is a 413, and nothing is stored")
		void batchOverTheLimitIs413() {
			EntityExchangeResult<String> result = send(samples(501));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
			assertThat(result.getResponseHeaders().getContentType())
					.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
			assertThat(bodyOf(result).get("limit").asInt()).isEqualTo(500);
			assertThat(storedReadings()).isZero();
		}

		@Test
		@DisplayName("an empty batch is rejected")
		void emptyBatchIsRejected() {
			assertThat(send("""
					{"samples": []}
					""").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("one invalid reading fails the whole batch, and the error says exactly where")
		void invalidReadingFailsTheBatch() {
			EntityExchangeResult<String> result = send("""
					{"samples": [
					  {"seq": 1, "recordedAt": "%1$s", "sensors": [{"cable": 0, "depth": 0, "temperatureC": 11.0}]},
					  {"seq": 2, "recordedAt": "%1$s", "sensors": [{"cable": 0, "depth": 0}]}
					]}
					""".formatted(now()));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

			// An indexed path, so a device can find the one bad reading among
			// thousands.
			assertThat(bodyOf(result).get("errors").get(0).get("field").asString())
					.isEqualTo("samples[1].sensors[0].temperatureC");

			// Nothing was stored -- not even the valid first sample.
			assertThat(storedReadings()).isZero();
		}

		@Test
		@DisplayName("a moisture outside 0-100 is a 400, not a database error")
		void moistureOutOfRangeIs400() {
			EntityExchangeResult<String> result = send(batch(sample(1, now(), sensor(0, 0, "11.0", "150.0"))));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(bodyOf(result).get("errors").get(0).get("field").asString())
					.isEqualTo("samples[0].sensors[0].moisturePct");
		}

		@Test
		@DisplayName("a temperature the column cannot hold is a 400, not a database error")
		void temperatureOverflowIs400() {
			// NUMERIC(4,1) tops out at 999.9. Anything larger would otherwise
			// reach PostgreSQL as a numeric overflow and come back as a 500.
			assertThat(send(batch(sample(1, now(), sensor(0, 0, "1000.0")))).getStatus())
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("a missing recordedAt is a 400")
		void missingRecordedAtIs400() {
			assertThat(send("""
					{"samples": [{"seq": 1, "sensors": [{"cable": 0, "depth": 0, "temperatureC": 11.0}]}]}
					""").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("a body that is not JSON is a 400 problem document")
		void unparseableBodyIs400() {
			EntityExchangeResult<String> result = send("this is not json");

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(result.getResponseHeaders().getContentType())
					.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
		}
	}
}
