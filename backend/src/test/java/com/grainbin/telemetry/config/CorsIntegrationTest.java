package com.grainbin.telemetry.config;

import com.grainbin.telemetry.support.WebIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS as a browser experiences it, against the real filter chain.
 *
 * <p>The web tests allow {@link #DASHBOARD_ORIGIN}. The headers that make a
 * request "cross-origin" -- {@code Origin}, and on a preflight
 * {@code Access-Control-Request-Method} and {@code -Headers} -- are set by
 * hand here, the way a browser would set them.
 */
class CorsIntegrationTest extends WebIntegrationTest {

	private static final String OTHER_ORIGIN = "https://evil.example";

	private EntityExchangeResult<String> preflight(String origin, String method) {
		return client.options().uri("/api/v1/bins")
				.header(HttpHeaders.ORIGIN, origin)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type")
				.exchange()
				.returnResult(String.class);
	}

	private static String allowedOrigin(EntityExchangeResult<String> result) {
		return result.getResponseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

	@Test
	@DisplayName("a preflight from the dashboard is answered without a token, not with 401")
	void preflightNeedsNoToken() {
		// The whole reason CORS runs before AdminAuthFilter: the preflight
		// never carries the Authorization header, and a 401 here would stop
		// the browser from ever sending the real request.
		EntityExchangeResult<String> result = preflight(DASHBOARD_ORIGIN, "POST");

		assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(allowedOrigin(result)).isEqualTo(DASHBOARD_ORIGIN);
		assertThat(result.getResponseHeaders().getAccessControlAllowMethods())
				.extracting(Object::toString).contains("GET", "POST", "PATCH");
		assertThat(result.getResponseHeaders().getAccessControlAllowHeaders())
				.map(String::toLowerCase).contains("authorization", "content-type");
		assertThat(result.getResponseHeaders().getAccessControlMaxAge()).isEqualTo(3600);
	}

	@Test
	@DisplayName("a preflight from any other origin is refused")
	void preflightFromOtherOriginRefused() {
		EntityExchangeResult<String> result = preflight(OTHER_ORIGIN, "GET");

		assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(allowedOrigin(result)).isNull();
	}

	@Test
	@DisplayName("a real request from the dashboard carries the CORS header")
	void realRequestAllowed() {
		EntityExchangeResult<String> result = client.get().uri("/api/v1/bins")
				.header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.exchange()
				.returnResult(String.class);

		assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(allowedOrigin(result)).isEqualTo(DASHBOARD_ORIGIN);
	}

	@Test
	@DisplayName("a 401 to the dashboard carries the CORS header, so the page can read it")
	void unauthorizedIsReadable() {
		// Without the header the browser hides the 401 from the page, and the
		// dashboard could not tell an expired token from a network failure.
		EntityExchangeResult<String> result = client.get().uri("/api/v1/bins")
				.header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
				.exchange()
				.returnResult(String.class);

		assertThat(result.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(allowedOrigin(result)).isEqualTo(DASHBOARD_ORIGIN);
	}

	@Test
	@DisplayName("a request with no Origin -- a device, curl -- is unaffected")
	void nonBrowserUnaffected() {
		EntityExchangeResult<String> result = getAsAdmin("/api/v1/bins");

		assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
		assertThat(allowedOrigin(result)).isNull();
	}

	@Test
	@DisplayName("the actuator endpoints are outside CORS")
	void actuatorNotCovered() {
		EntityExchangeResult<String> result = client.get().uri("/actuator/health")
				.header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
				.exchange()
				.returnResult(String.class);

		assertThat(allowedOrigin(result)).isNull();
	}
}
