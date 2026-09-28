package com.grainbin.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which browser origins may call the API.
 *
 * <p>Bound from {@code CORS_ALLOWED_ORIGINS}, a comma-separated list such as
 * {@code http://localhost:5173}. Empty -- the default -- allows no cross-origin
 * browser calls at all, which is the safe starting point: a deployment has to
 * name its front end's origin before a browser on another origin can call the
 * API.
 *
 * <p>Wildcards are refused at startup (see {@link CorsConfig}). With an admin
 * token in play, "any origin" is never what is meant.
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = (allowedOrigins == null) ? List.of() : List.copyOf(allowedOrigins);
	}
}
