package com.grainbin.telemetry.config;

import com.grainbin.telemetry.ingest.IngestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the configuration rules that are enforced at startup rather than at
 * request time.
 *
 * <p>ADR 0003 says the application refuses to start without an admin token,
 * and ADR 0006 that the ingest age limit must be at least a day. Both are
 * claims about what happens when the application is misconfigured -- exactly
 * the situation no other test ever creates, because every other test starts
 * from a working configuration.
 *
 * <p>Uses {@link ApplicationContextRunner}: a minimal context holding only the
 * two properties classes, with no database and no web server. A full context
 * is not needed to prove that binding fails, and would cost a container per
 * case.
 */
class ConfigurationValidationTest {

	@EnableConfigurationProperties({ ApiSecurityProperties.class, IngestProperties.class })
	static class PropertiesOnly {
	}

	private static final String VALID_TOKEN = "app.security.admin-token=a-perfectly-good-token";
	private static final String VALID_AGE = "app.ingest.max-sample-age=30d";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesOnly.class);

	@Test
	@DisplayName("a valid configuration starts")
	void validConfigurationStarts() {
		this.runner.withPropertyValues(VALID_TOKEN, VALID_AGE)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	@DisplayName("a missing admin token stops startup")
	void missingAdminTokenStopsStartup() {
		// Starting anyway would serve every admin endpoint to anyone who asked.
		this.runner.withPropertyValues(VALID_AGE)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("adminToken");
				});
	}

	@Test
	@DisplayName("a blank admin token stops startup")
	void blankAdminTokenStopsStartup() {
		// ADMIN_TOKEN= in a .env file, or an unset variable behind ${ADMIN_TOKEN:},
		// both arrive as an empty string rather than as a missing value.
		this.runner.withPropertyValues("app.security.admin-token=   ", VALID_AGE)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("adminToken");
				});
	}

	@Test
	@DisplayName("a sample age limit shorter than a day stops startup")
	void tooShortAnAgeLimitStopsStartup() {
		// A limit shorter than an ordinary outage would discard real back-filled
		// data the moment a device reconnected.
		this.runner.withPropertyValues(VALID_TOKEN, "app.ingest.max-sample-age=1h")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("maxSampleAge");
				});
	}

	// -----------------------------------------------------------------------
	// CORS
	// -----------------------------------------------------------------------

	private final ApplicationContextRunner corsRunner = new ApplicationContextRunner()
			.withUserConfiguration(CorsConfig.class);

	@Test
	@DisplayName("CORS_ALLOWED_ORIGINS binds a comma-separated list of origins")
	void corsOriginsBindAsList() {
		this.corsRunner.withPropertyValues("app.cors.allowed-origins=http://localhost:5173, https://dash.example")
				.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
						.containsExactly("http://localhost:5173", "https://dash.example"));
	}

	@Test
	@DisplayName("no CORS origins configured means none are allowed, and still starts")
	void corsDefaultsToNone() {
		this.corsRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty();
		});
	}

	@Test
	@DisplayName("a wildcard CORS origin stops startup")
	void corsWildcardStopsStartup() {
		// With an admin token in play, "any origin" is never what is meant.
		this.corsRunner.withPropertyValues("app.cors.allowed-origins=*")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("wildcards are not accepted");
				});
	}
}
