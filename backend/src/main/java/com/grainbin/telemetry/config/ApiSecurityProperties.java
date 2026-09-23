package com.grainbin.telemetry.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication settings for {@code /api/v1}.
 *
 * @param adminToken the bearer token accepted on the admin and dashboard
 *                   endpoints
 */
@ConfigurationProperties("app.security")
@Validated
public record ApiSecurityProperties(

		/*
		 * Required, with no default and no fallback. An application that
		 * starts with a blank admin token is an application serving every
		 * admin endpoint to anyone who asks, and it would do so silently.
		 * Refusing to start is the only safe behaviour, so the failure is at
		 * deploy time rather than the first time someone notices.
		 */
		@NotBlank(message = """
				must be set. The admin API has no other protection, so the \
				application will not start without it. Set the ADMIN_TOKEN \
				environment variable -- see .env.example. Generate one with: \
				python -c "import secrets; print(secrets.token_urlsafe(32))"\
				""")
		String adminToken) {
}
