package com.grainbin.telemetry.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * Lets the dashboard, served from another origin, call the API from a browser.
 *
 * <h2>What CORS is for</h2>
 *
 * <p>A browser will not let a page on one origin (scheme, host and port) read
 * responses from another unless that other server says it may. The dashboard
 * is served by Vite on {@code localhost:5173} locally and from CloudFront in
 * AWS, while the API is on {@code :8080} or behind the ALB -- different
 * origins in both cases. CORS is how the API says "that origin may read my
 * responses". It restricts browsers only; curl and devices ignore it, which is
 * why it is not a security boundary, and the admin token still is.
 *
 * <h2>Why a servlet filter, ordered before authentication</h2>
 *
 * <p>For a request carrying an {@code Authorization} header, the browser first
 * sends a <em>preflight</em>: an {@code OPTIONS} request asking whether the
 * real request is allowed. The preflight never carries the token -- that is
 * the point of asking first. Spring MVC's own CORS support
 * ({@code addCorsMappings}) runs inside the {@code DispatcherServlet}, after
 * every servlet filter, so {@code AdminAuthFilter} would answer the preflight
 * with 401 and the browser would never send the real request.
 *
 * <p>This filter runs first. It answers a preflight itself, without passing it
 * on, and it adds the CORS headers to every other response <em>before</em>
 * the auth filters run. That second part matters too: a 401 without those
 * headers is hidden from the page by the browser, so the dashboard could not
 * tell an expired token from a network failure.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

	/**
	 * Before both authentication filters, which are registered at
	 * {@code HIGHEST_PRECEDENCE + 100} and {@code + 101} in
	 * {@link ApiSecurityConfig}. A lower number runs earlier.
	 */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

	/**
	 * How long a browser may cache a preflight answer, so the dashboard does
	 * not send an OPTIONS before every single request.
	 */
	private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

	@Bean
	FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsProperties properties) {
		List<String> origins = properties.allowedOrigins();
		if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
			throw new IllegalStateException("CORS_ALLOWED_ORIGINS must list exact origins, such as "
					+ "http://localhost:5173; wildcards are not accepted: " + origins);
		}

		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(origins);
		cors.setAllowedMethods(List.of("GET", "POST", "PATCH"));
		cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
		// POST /bins answers 201 with a Location header; without this the page
		// could not read it.
		cors.setExposedHeaders(List.of(HttpHeaders.LOCATION));
		// The token travels in a header the page sets itself, not in a cookie,
		// so credentialed CORS is not needed -- and leaving it off means a
		// browser never attaches cookies to these requests.
		cors.setAllowCredentials(false);
		cors.setMaxAge(PREFLIGHT_CACHE);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/v1/**", cors);

		// Constructed here, not declared as a bean, for the same reason as
		// the auth filters: Boot would otherwise also register it on every path.
		var registration = new FilterRegistrationBean<>(new CorsFilter(source));
		registration.addUrlPatterns("/api/v1/*");
		registration.setOrder(ORDER);
		registration.setName("corsFilter");
		return registration;
	}
}
