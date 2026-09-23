package com.grainbin.telemetry.config;

import com.grainbin.telemetry.TestcontainersConfiguration;
import com.grainbin.telemetry.devices.AuthenticatedDevice;
import com.grainbin.telemetry.devices.DeviceApiKey;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the authentication filters against a running server.
 *
 * <p>Uses a real servlet container rather than MockMvc on purpose. Half of
 * what is under test here <em>is</em> the servlet registration: which URL
 * patterns each filter is mapped to, that the admin filter stands aside for
 * the ingest path, and that {@code /actuator} is outside both. MockMvc
 * approximates the filter chain, and an approximation is not what you want
 * when the question is "is any path accidentally unprotected".
 *
 * <p>Driven with {@code RestTestClient} rather than {@code TestRestTemplate}.
 * Spring Boot 4 moved {@code RestTemplate} support into its own module which
 * is not on the classpath by default, and {@code RestTestClient} is both
 * already available and the current idiom.
 *
 * <p>The endpoints below are stubs. The real controllers arrive in Phases 6
 * and 7; these exist so the filters have something to let a request through
 * to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, AuthenticationIntegrationTest.StubEndpoints.class })
class AuthenticationIntegrationTest {

	/**
	 * Both a test configuration and the controller itself. A nested
	 * {@code @RestController} would be picked up as a bean by virtue of being
	 * a nested component <em>and</em> by an explicit {@code @Bean} method,
	 * registering the same request mappings twice and failing the context with
	 * "Ambiguous mapping".
	 */
	@TestConfiguration(proxyBeanMethods = false)
	@RestController
	static class StubEndpoints {

		/** Echoes what the filter resolved, so the test can check it. */
		@PostMapping("/api/v1/readings")
		Map<String, Object> ingest(HttpServletRequest request) {
			AuthenticatedDevice device = AuthenticatedDevice.require(request);
			return Map.of("deviceId", device.deviceId(), "binId", device.binId());
		}

		@GetMapping("/api/v1/bins")
		Map<String, String> bins() {
			return Map.of("status", "ok");
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ObjectMapper json;

	@Value("${app.security.admin-token}")
	private String adminToken;

	private RestTestClient client;
	private String deviceKey;
	private long deviceId;
	private long binId;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

		binId = jdbc.sql("""
				INSERT INTO bins (name, site, grain_type)
				VALUES (?, 'Auth Test Yard', 'canola') RETURNING id
				""").param("Bin " + System.nanoTime()).query(Long.class).single();

		deviceKey = DeviceApiKey.generate();
		deviceId = jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash) VALUES (?, ?) RETURNING id
				""").param(binId).param(DeviceApiKey.hash(deviceKey)).query(Long.class).single();
	}

	// -----------------------------------------------------------------------
	// helpers
	// -----------------------------------------------------------------------

	/** POST with no credential. */
	private EntityExchangeResult<String> post(String path) {
		return client.post().uri(path).exchange().returnResult(String.class);
	}

	private EntityExchangeResult<String> post(String path, String header, String value) {
		return client.post().uri(path).header(header, value).exchange().returnResult(String.class);
	}

	/** GET with no credential. */
	private EntityExchangeResult<String> get(String path) {
		return client.get().uri(path).exchange().returnResult(String.class);
	}

	private EntityExchangeResult<String> get(String path, String header, String value) {
		return client.get().uri(path).header(header, value).exchange().returnResult(String.class);
	}

	private String detailOf(EntityExchangeResult<String> result) {
		return json.readTree(result.getResponseBody()).get("detail").asString();
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

		JsonNode body = json.readTree(result.getResponseBody());
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
			JsonNode body = json.readTree(result.getResponseBody());
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
			EntityExchangeResult<String> result =
					get("/api/v1/bins", HttpHeaders.AUTHORIZATION, "Bearer " + adminToken);

			assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
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
			// short-circuit on a length difference. This is the case that would
			// pass anyway with a naive equals() -- it is here to pin the
			// behaviour, not because it is likely to regress on its own.
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
