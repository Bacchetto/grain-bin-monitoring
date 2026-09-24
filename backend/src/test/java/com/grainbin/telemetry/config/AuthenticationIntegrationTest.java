package com.grainbin.telemetry.config;

import com.grainbin.telemetry.devices.DeviceApiKey;
import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the authentication filters against a running server.
 *
 * <p>The admin cases run against the real {@code GET /api/v1/bins}. Until
 * Phase 6 that path was a stub in the test tree; when the real controller
 * arrived the two mappings collided and failed the context outright, which is
 * the desired way for a stub to expire.
 *
 * <p>The ingest path is still stubbed -- see {@code IngestStubEndpoints} --
 * because the real ingest controller is Phase 7.
 */
class AuthenticationIntegrationTest extends WebIntegrationTest {

	private String deviceKey;
	private long deviceId;
	private long binId;

	@BeforeEach
	void registerADevice() {
		this.binId = this.jdbc.sql("""
				INSERT INTO bins (name, site, grain_type)
				VALUES (?, 'Auth Test Yard', 'canola') RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();

		this.deviceKey = DeviceApiKey.generate();
		this.deviceId = this.jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash) VALUES (?, ?) RETURNING id
				""").param(this.binId).param(DeviceApiKey.hash(this.deviceKey)).query(Long.class).single();
	}

	// -----------------------------------------------------------------------
	// helpers -- these send deliberately wrong or absent credentials, so they
	// cannot use the authenticated helpers on the base class
	// -----------------------------------------------------------------------

	private EntityExchangeResult<String> post(String path) {
		return this.client.post().uri(path).exchange().returnResult(String.class);
	}

	private EntityExchangeResult<String> post(String path, String header, String value) {
		return this.client.post().uri(path).header(header, value).exchange().returnResult(String.class);
	}

	private EntityExchangeResult<String> get(String path) {
		return this.client.get().uri(path).exchange().returnResult(String.class);
	}

	private EntityExchangeResult<String> get(String path, String header, String value) {
		return this.client.get().uri(path).header(header, value).exchange().returnResult(String.class);
	}

	private String detailOf(EntityExchangeResult<String> result) {
		return bodyOf(result).get("detail").asString();
	}

	/** Asserts the response is a well-formed RFC 9457 401. */
	private void assertIsProblemJsonUnauthorized(EntityExchangeResult<String> result, String expectedChallenge) {
		assertThat(result.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

		assertThat(result.getResponseHeaders().getContentType())
				.as("RFC 9457 error shape, not a bare status code")
				.isNotNull()
				.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());

		assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
				.as("RFC 9110 requires a challenge on a 401")
				.isEqualTo(expectedChallenge);

		JsonNode body = bodyOf(result);
		assertThat(body.get("status").asInt()).isEqualTo(401);
		assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
		assertThat(body.get("detail").asString()).isNotBlank();
		assertThat(body.get("instance").asString()).startsWith("/api/v1/");
	}

	// -----------------------------------------------------------------------
	// device key, on the ingest path
	// -----------------------------------------------------------------------

	@Nested
	class IngestEndpoint {

		@Test
		@DisplayName("a valid key resolves the device and leaves it on the request")
		void validKeyIsAccepted() {
			EntityExchangeResult<String> result = post("/api/v1/readings", "X-Device-Key", deviceKey);

			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);

			// The controller never saw the key -- it read the device the filter
			// resolved. This is what stops a device writing to another bin.
			JsonNode body = bodyOf(result);
			assertThat(body.get("deviceId").asLong()).isEqualTo(deviceId);
			assertThat(body.get("binId").asLong()).isEqualTo(binId);
		}

		@Test
		@DisplayName("no key is rejected")
		void missingKeyIsRejected() {
			assertIsProblemJsonUnauthorized(post("/api/v1/readings"), "X-Device-Key");
		}

		@Test
		@DisplayName("a blank key is rejected")
		void blankKeyIsRejected() {
			assertIsProblemJsonUnauthorized(post("/api/v1/readings", "X-Device-Key", "   "), "X-Device-Key");
		}

		@Test
		@DisplayName("a well-formed but unregistered key is rejected")
		void unknownKeyIsRejected() {
			assertIsProblemJsonUnauthorized(
					post("/api/v1/readings", "X-Device-Key", DeviceApiKey.generate()), "X-Device-Key");
		}

		@Test
		@DisplayName("the rejection says nothing about why the key failed")
		void rejectionDoesNotHelpAnAttacker() {
			String unknown = detailOf(post("/api/v1/readings", "X-Device-Key", DeviceApiKey.generate()));

			// Saying "no such key" versus "revoked" versus "wrong length" would
			// let an attacker sort guesses into better and worse ones.
			assertThat(unknown)
					.doesNotContainIgnoringCase("revoked")
					.doesNotContainIgnoringCase("unknown")
					.doesNotContainIgnoringCase("expired");
		}

		@Test
		@DisplayName("the admin token does not work on the ingest path")
		void adminTokenIsNotADeviceKey() {
			assertIsProblemJsonUnauthorized(
					post("/api/v1/readings", HttpHeaders.AUTHORIZATION, "Bearer " + adminToken), "X-Device-Key");
		}
	}

	// -----------------------------------------------------------------------
	// admin token, on everything else
	// -----------------------------------------------------------------------

	@Nested
	class AdminEndpoints {

		@Test
		@DisplayName("the configured bearer token is accepted")
		void validTokenIsAccepted() {
			assertThat(getAsAdmin("/api/v1/bins").getStatus()).isEqualTo(HttpStatus.OK);
		}

		@Test
		@DisplayName("no Authorization header is rejected")
		void missingHeaderIsRejected() {
			assertIsProblemJsonUnauthorized(get("/api/v1/bins"), "Bearer");
		}

		@Test
		@DisplayName("a wrong token is rejected")
		void wrongTokenIsRejected() {
			assertIsProblemJsonUnauthorized(
					get("/api/v1/bins", HttpHeaders.AUTHORIZATION, "Bearer not-the-token"), "Bearer");
		}

		@Test
		@DisplayName("a token of the right length but wrong content is rejected")
		void sameLengthTokenIsRejected() {
			// The comparison hashes both sides before comparing, so it cannot
			// short-circuit on a length difference. This pins that behaviour.
			String sameLength = "x".repeat(adminToken.length());

			assertIsProblemJsonUnauthorized(
					get("/api/v1/bins", HttpHeaders.AUTHORIZATION, "Bearer " + sameLength), "Bearer");
		}

		@Test
		@DisplayName("the token without the Bearer scheme is rejected")
		void rawTokenWithoutSchemeIsRejected() {
			assertIsProblemJsonUnauthorized(get("/api/v1/bins", HttpHeaders.AUTHORIZATION, adminToken), "Bearer");
		}

		@Test
		@DisplayName("a device key does not work on the admin endpoints")
		void deviceKeyIsNotAnAdminToken() {
			assertIsProblemJsonUnauthorized(get("/api/v1/bins", "X-Device-Key", deviceKey), "Bearer");
		}

		@Test
		@DisplayName("an unmapped path under /api/v1 is challenged, not 404'd")
		void unknownApiPathsAreStillProtected() {
			// Answering 404 before authenticating would let an unauthenticated
			// caller map which endpoints exist.
			assertThat(get("/api/v1/does-not-exist").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
	}

	// -----------------------------------------------------------------------
	// what must stay open
	// -----------------------------------------------------------------------

	@Test
	@DisplayName("actuator health is reachable without credentials")
	void healthIsUnauthenticated() {
		// The load balancer and the container HEALTHCHECK have no credential to
		// present. If this ever starts returning 401 the service looks
		// permanently unhealthy and gets pulled from the target group.
		assertThat(get("/actuator/health").getStatus()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("the prometheus endpoint is reachable without credentials")
	void prometheusIsUnauthenticated() {
		assertThat(get("/actuator/prometheus").getStatus()).isEqualTo(HttpStatus.OK);
	}
}
