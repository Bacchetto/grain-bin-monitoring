package com.grainbin.telemetry.support;

import com.grainbin.telemetry.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for tests that drive the API over HTTP against a running server.
 *
 * <p>A real servlet container rather than MockMvc, because the servlet
 * registration is itself under test: which URL patterns each authentication
 * filter covers, and that no path is accidentally left unprotected. MockMvc
 * approximates the filter chain.
 *
 * <p><strong>Every subclass shares one Spring context, and therefore one
 * PostgreSQL container.</strong> Spring caches contexts by their merged
 * configuration, so the annotations on this class have to be identical for
 * every web test -- which is the reason they live here rather than being
 * repeated. Adding {@code @Import} or {@code @TestPropertySource} to a
 * subclass forks a second context and starts a second container.
 *
 * <p>Tests here are <em>not</em> transactional. They exercise the real request
 * path, and a request handled by the server runs in its own transaction, so a
 * rollback around the test method would not undo it anyway. Each test creates
 * its own bins with unique names instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, IngestStubEndpoints.class })
public abstract class WebIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected ObjectMapper json;

	/** The token the admin filter is configured with, supplied by surefire. */
	@Value("${app.security.admin-token}")
	protected String adminToken;

	protected RestTestClient client;

	@BeforeEach
	void bindClient() {
		this.client = RestTestClient.bindToServer()
				.baseUrl("http://localhost:" + this.port)
				.build();
	}

	// -----------------------------------------------------------------------
	// requests
	// -----------------------------------------------------------------------

	protected EntityExchangeResult<String> getAsAdmin(String path) {
		return this.client.get().uri(path)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.exchange()
				.returnResult(String.class);
	}

	protected EntityExchangeResult<String> postAsAdmin(String path, String jsonBody) {
		return withBody(this.client.post().uri(path), jsonBody);
	}

	protected EntityExchangeResult<String> patchAsAdmin(String path, String jsonBody) {
		return withBody(this.client.patch().uri(path), jsonBody);
	}

	/** POST with no body at all, to check that an omitted body is accepted. */
	protected EntityExchangeResult<String> postAsAdminWithoutBody(String path) {
		return this.client.post().uri(path)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.exchange()
				.returnResult(String.class);
	}

	private EntityExchangeResult<String> withBody(RestTestClient.RequestBodySpec spec, String jsonBody) {
		return spec.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.body(jsonBody)
				.exchange()
				.returnResult(String.class);
	}

	private String bearer() {
		return "Bearer " + this.adminToken;
	}

	// -----------------------------------------------------------------------
	// responses
	// -----------------------------------------------------------------------

	protected JsonNode bodyOf(EntityExchangeResult<String> result) {
		return this.json.readTree(result.getResponseBody());
	}
}
