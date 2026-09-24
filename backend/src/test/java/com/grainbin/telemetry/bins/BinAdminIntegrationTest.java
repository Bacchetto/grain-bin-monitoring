package com.grainbin.telemetry.bins;

import com.grainbin.telemetry.devices.DeviceApiKey;
import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin endpoints for bins and device registration, driven over HTTP. */
class BinAdminIntegrationTest extends WebIntegrationTest {

	/** Unique per test so tests can run against a shared database. */
	private String uniqueName() {
		return "Bin " + System.nanoTime();
	}

	private long createBin(String name, String site) {
		EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
				{"name": "%s", "site": "%s", "grainType": "canola", "capacityBushels": 5000}
				""".formatted(name, site));
		assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
		return bodyOf(result).get("id").asLong();
	}

	private JsonNode findInList(JsonNode list, long id) {
		return StreamSupport.stream(list.spliterator(), false)
				.filter(node -> node.get("id").asLong() == id)
				.findFirst()
				.orElseThrow(() -> new AssertionError("bin " + id + " missing from the list"));
	}

	// -----------------------------------------------------------------------
	// creating bins
	// -----------------------------------------------------------------------

	@Nested
	class CreateBin {

		@Test
		@DisplayName("a new bin is created with the documented default thresholds")
		void createsWithDefaults() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "North Yard", "grainType": "wheat", "capacityBushels": 12000}
					""".formatted(uniqueName()));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);

			JsonNode body = bodyOf(result);
			assertThat(body.get("grainType").asString()).isEqualTo("wheat");
			assertThat(body.get("capacityBushels").asInt()).isEqualTo(12000);

			// The README's defaults. A bin is usable the moment it exists.
			JsonNode thresholds = body.get("thresholds");
			assertThat(thresholds.get("maxTemperatureC").asDouble()).isEqualTo(20.0);
			assertThat(thresholds.get("maxMoisturePct").asDouble()).isEqualTo(14.5);
			assertThat(thresholds.get("riseThresholdC").asDouble()).isEqualTo(2.0);
			assertThat(thresholds.get("riseWindowHours").asInt()).isEqualTo(72);
		}

		@Test
		@DisplayName("the Location header points at the new bin")
		void setsLocationHeader() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "North Yard", "grainType": "wheat"}
					""".formatted(uniqueName()));

			long id = bodyOf(result).get("id").asLong();
			assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION))
					.endsWith("/api/v1/bins/" + id);
		}

		@Test
		@DisplayName("capacity is optional")
		void capacityIsOptional() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "North Yard", "grainType": "oats"}
					""".formatted(uniqueName()));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
			assertThat(bodyOf(result).get("capacityBushels").isNull()).isTrue();
		}

		@Test
		@DisplayName("the same name in two different sites is allowed")
		void nameIsUniquePerSiteNotGlobally() {
			String name = uniqueName();
			createBin(name, "North Yard");

			assertThat(postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "South Yard", "grainType": "canola"}
					""".formatted(name)).getStatus())
					.isEqualTo(HttpStatus.CREATED);
		}

		@Test
		@DisplayName("the same name twice in one site is a 409, not a 500")
		void duplicateNameInOneSiteConflicts() {
			String name = uniqueName();
			createBin(name, "North Yard");

			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "North Yard", "grainType": "canola"}
					""".formatted(name));

			// 409, because the request was well-formed and retrying it
			// unchanged will not help.
			assertThat(result.getStatus()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(result.getResponseHeaders().getContentType())
					.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());

			// The internal constraint name must not leak into the API contract.
			assertThat(bodyOf(result).get("detail").asString()).doesNotContain("bins_site_name_key");
		}

		@Test
		@DisplayName("a blank name is rejected and the response names the field")
		void validationNamesTheOffendingField() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "   ", "site": "North Yard", "grainType": "canola"}
					""");

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

			JsonNode errors = bodyOf(result).get("errors");
			assertThat(errors.isArray()).isTrue();
			assertThat(errors.get(0).get("field").asString()).isEqualTo("name");
		}

		@Test
		@DisplayName("a negative capacity is rejected")
		void rejectsNegativeCapacity() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins", """
					{"name": "%s", "site": "North Yard", "grainType": "canola", "capacityBushels": -1}
					""".formatted(uniqueName()));

			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(bodyOf(result).get("errors").get(0).get("field").asString()).isEqualTo("capacityBushels");
		}
	}

	// -----------------------------------------------------------------------
	// reading bins
	// -----------------------------------------------------------------------

	@Nested
	class ReadBin {

		@Test
		@DisplayName("an unknown bin is a 404 problem document")
		void unknownBinIsNotFound() {
			EntityExchangeResult<String> result = getAsAdmin("/api/v1/bins/999999");

			assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
			JsonNode body = bodyOf(result);
			assertThat(body.get("title").asString()).isEqualTo("Not Found");
			assertThat(body.get("resource").asString()).isEqualTo("Bin");
		}

		@Test
		@DisplayName("a bin with no devices reports no last reading and no alerts")
		void freshBinHasNoStatus() {
			long id = createBin(uniqueName(), "Status Yard");

			JsonNode row = findInList(bodyOf(getAsAdmin("/api/v1/bins")), id);

			assertThat(row.get("lastReadingAt").isNull()).isTrue();
			assertThat(row.get("worstOpenAlert").isNull()).isTrue();
			assertThat(row.get("openAlertCount").asInt()).isZero();
		}

		@Test
		@DisplayName("last reading time comes from the device, across all of a bin's devices")
		void lastReadingIsTheLatestAcrossDevices() {
			long id = createBin(uniqueName(), "Status Yard");
			Instant older = Instant.parse("2026-09-01T10:00:00Z");
			Instant newer = Instant.parse("2026-09-20T18:30:00Z");

			registerDeviceWithLastSeen(id, older);
			registerDeviceWithLastSeen(id, newer);

			JsonNode row = findInList(bodyOf(getAsAdmin("/api/v1/bins")), id);

			assertThat(Instant.parse(row.get("lastReadingAt").asString())).isEqualTo(newer);
		}

		@Test
		@DisplayName("the worst open alert wins, and acknowledged still counts as open")
		void worstOpenAlertIsRanked() {
			long id = createBin(uniqueName(), "Status Yard");

			// Deliberately inserted worst-last so a naive "first row" would
			// pick the wrong one.
			insertAlert(id, "HIGH_MOISTURE", "OPEN", 0, 0);
			insertAlert(id, "HIGH_TEMPERATURE", "ACKNOWLEDGED", 1, 1);

			JsonNode row = findInList(bodyOf(getAsAdmin("/api/v1/bins")), id);

			// Acknowledging a hot spot does not cool it, so it still counts.
			assertThat(row.get("worstOpenAlert").asString()).isEqualTo("HIGH_TEMPERATURE");
			assertThat(row.get("openAlertCount").asInt()).isEqualTo(2);
		}

		@Test
		@DisplayName("resolved alerts are not counted as open")
		void resolvedAlertsAreExcluded() {
			long id = createBin(uniqueName(), "Status Yard");
			insertAlert(id, "HIGH_TEMPERATURE", "RESOLVED", 0, 0);

			JsonNode row = findInList(bodyOf(getAsAdmin("/api/v1/bins")), id);

			assertThat(row.get("worstOpenAlert").isNull()).isTrue();
			assertThat(row.get("openAlertCount").asInt()).isZero();
		}

		private void registerDeviceWithLastSeen(long binId, Instant lastSeen) {
			jdbc.sql("""
					INSERT INTO devices (bin_id, api_key_hash, last_seen_at) VALUES (?, ?, ?)
					""")
					.param(binId)
					.param(DeviceApiKey.hash(DeviceApiKey.generate()))
					.param(OffsetDateTime.ofInstant(lastSeen, ZoneOffset.UTC))
					.update();
		}

		private void insertAlert(long binId, String type, String status, int cable, int depth) {
			jdbc.sql("""
					INSERT INTO alerts (bin_id, type, cable_index, depth_index, status,
					                    acknowledged_at, resolved_at)
					VALUES (?, ?, ?, ?, ?,
					        CASE WHEN ? = 'ACKNOWLEDGED' THEN now() END,
					        CASE WHEN ? = 'RESOLVED' THEN now() END)
					""")
					.param(binId).param(type).param(cable).param(depth).param(status)
					.param(status).param(status)
					.update();
		}
	}

	// -----------------------------------------------------------------------
	// thresholds
	// -----------------------------------------------------------------------

	@Nested
	class UpdateThresholds {

		@Test
		@DisplayName("a partial patch changes only the fields it names")
		void patchIsPartial() {
			long id = createBin(uniqueName(), "Threshold Yard");

			JsonNode thresholds = bodyOf(patchAsAdmin("/api/v1/bins/" + id + "/thresholds",
					"""
					{"maxTemperatureC": 18.5}
					""")).get("thresholds");

			assertThat(thresholds.get("maxTemperatureC").asDouble()).isEqualTo(18.5);
			// Untouched fields keep their values rather than reverting to null
			// or to defaults, which is the whole point of PATCH.
			assertThat(thresholds.get("maxMoisturePct").asDouble()).isEqualTo(14.5);
			assertThat(thresholds.get("riseThresholdC").asDouble()).isEqualTo(2.0);
			assertThat(thresholds.get("riseWindowHours").asInt()).isEqualTo(72);
		}

		@Test
		@DisplayName("every threshold can be set at once")
		void patchCanSetAll() {
			long id = createBin(uniqueName(), "Threshold Yard");

			JsonNode thresholds = bodyOf(patchAsAdmin("/api/v1/bins/" + id + "/thresholds", """
					{"maxTemperatureC": 17.0, "maxMoisturePct": 13.0,
					 "riseThresholdC": 1.5, "riseWindowHours": 48}
					""")).get("thresholds");

			assertThat(thresholds.get("maxTemperatureC").asDouble()).isEqualTo(17.0);
			assertThat(thresholds.get("maxMoisturePct").asDouble()).isEqualTo(13.0);
			assertThat(thresholds.get("riseThresholdC").asDouble()).isEqualTo(1.5);
			assertThat(thresholds.get("riseWindowHours").asInt()).isEqualTo(48);
		}

		@Test
		@DisplayName("an empty patch is rejected rather than silently doing nothing")
		void emptyPatchIsRejected() {
			long id = createBin(uniqueName(), "Threshold Yard");

			// An accepted no-op would hide a client sending the wrong field
			// names: it would look like the update worked.
			assertThat(patchAsAdmin("/api/v1/bins/" + id + "/thresholds", "{}").getStatus())
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("an out-of-range moisture is a 400 naming the field, not a database error")
		void outOfRangeIsRejectedBeforeTheDatabase() {
			long id = createBin(uniqueName(), "Threshold Yard");

			EntityExchangeResult<String> result =
					patchAsAdmin("/api/v1/bins/" + id + "/thresholds", """
							{"maxMoisturePct": 150.0}
							""");

			// The schema would also reject this, but as a constraint violation
			// naming an internal object. Validating first gives the caller a
			// field name.
			assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(bodyOf(result).get("errors").get(0).get("field").asString()).isEqualTo("maxMoisturePct");
		}

		@Test
		@DisplayName("patching an unknown bin is a 404")
		void patchUnknownBin() {
			assertThat(patchAsAdmin("/api/v1/bins/999999/thresholds", """
					{"maxTemperatureC": 18.0}
					""").getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@DisplayName("an update is visible on the next read")
		void updatePersists() {
			long id = createBin(uniqueName(), "Threshold Yard");
			patchAsAdmin("/api/v1/bins/" + id + "/thresholds", """
					{"riseWindowHours": 24}
					""");

			assertThat(bodyOf(getAsAdmin("/api/v1/bins/" + id))
					.get("thresholds").get("riseWindowHours").asInt())
					.isEqualTo(24);
		}
	}

	// -----------------------------------------------------------------------
	// device registration
	// -----------------------------------------------------------------------

	@Nested
	class RegisterDevice {

		@Test
		@DisplayName("registration returns a usable key exactly once")
		void returnsAKeyThatWorks() {
			long binId = createBin(uniqueName(), "Device Yard");

			EntityExchangeResult<String> result =
					postAsAdmin("/api/v1/bins/" + binId + "/devices", """
							{"expectedIntervalSeconds": 600}
							""");

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
			JsonNode body = bodyOf(result);
			String apiKey = body.get("apiKey").asString();

			assertThat(apiKey).startsWith("gbk_");
			assertThat(body.get("binId").asLong()).isEqualTo(binId);
			assertThat(body.get("expectedIntervalSeconds").asInt()).isEqualTo(600);

			// The key is only useful if it actually authenticates. Posting a real
			// batch with it proves the digest stored at registration is the
			// digest the filter computes, end to end.
			EntityExchangeResult<String> ingest = postReadings(apiKey, """
					{"samples": [{"seq": 1, "recordedAt": "%s",
					  "sensors": [{"cable": 0, "depth": 0, "temperatureC": 12.0}]}]}
					""".formatted(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
			assertThat(ingest.getStatus()).isEqualTo(HttpStatus.ACCEPTED);
			assertThat(jdbc.sql("SELECT bin_id FROM readings WHERE device_id = ?")
					.param(body.get("id").asLong()).query(Long.class).single())
					.isEqualTo(binId);
		}

		@Test
		@DisplayName("only the hash is stored, never the key")
		void storesOnlyTheHash() {
			long binId = createBin(uniqueName(), "Device Yard");
			String apiKey = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}"))
					.get("apiKey").asString();

			String stored = jdbc.sql("SELECT api_key_hash FROM devices WHERE bin_id = ?")
					.param(binId).query(String.class).single();

			assertThat(stored).isEqualTo(DeviceApiKey.hash(apiKey)).isNotEqualTo(apiKey);
			assertThat(stored).doesNotContain(apiKey.substring("gbk_".length()));
		}

		@Test
		@DisplayName("two devices on one bin get different keys")
		void keysAreNotReused() {
			long binId = createBin(uniqueName(), "Device Yard");

			String first = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}")).get("apiKey").asString();
			String second = bodyOf(postAsAdmin("/api/v1/bins/" + binId + "/devices", "{}")).get("apiKey").asString();

			assertThat(first).isNotEqualTo(second);
		}

		@Test
		@DisplayName("the body may be omitted entirely and the interval defaults")
		void bodyIsOptional() {
			long binId = createBin(uniqueName(), "Device Yard");

			EntityExchangeResult<String> result = postAsAdminWithoutBody("/api/v1/bins/" + binId + "/devices");

			assertThat(result.getStatus()).isEqualTo(HttpStatus.CREATED);
			assertThat(bodyOf(result).get("expectedIntervalSeconds").asInt()).isEqualTo(300);
		}

		@Test
		@DisplayName("registering against an unknown bin is a 404 about the bin")
		void unknownBinIsNotFound() {
			EntityExchangeResult<String> result = postAsAdmin("/api/v1/bins/999999/devices", "{}");

			// Not a 409 from a foreign key violation, which would name
			// something the caller never mentioned.
			assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(bodyOf(result).get("resource").asString()).isEqualTo("Bin");
		}

		@Test
		@DisplayName("an implausibly short reporting interval is rejected")
		void rejectsTooShortAnInterval() {
			long binId = createBin(uniqueName(), "Device Yard");

			// DEVICE_OFFLINE fires at 3x the interval, so a device claiming to
			// report every second would be declared offline three seconds
			// after any hiccup.
			assertThat(postAsAdmin("/api/v1/bins/" + binId + "/devices", """
					{"expectedIntervalSeconds": 1}
					""").getStatus())
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}
}
