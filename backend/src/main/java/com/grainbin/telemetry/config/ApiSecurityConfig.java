package com.grainbin.telemetry.config;

import com.grainbin.telemetry.common.ProblemResponses;
import com.grainbin.telemetry.devices.DeviceAuthenticator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the two authentication filters over {@code /api/v1}.
 *
 * <p>The API has two kinds of caller with nothing in common, so it has two
 * independent filters rather than one with a branch in it:
 *
 * <table border="1">
 *   <caption>Authentication by path</caption>
 *   <tr><th>Paths</th><th>Credential</th><th>Filter</th></tr>
 *   <tr>
 *     <td>{@code /api/v1/readings}</td>
 *     <td>{@code X-Device-Key} header</td>
 *     <td>{@link DeviceAuthFilter}</td>
 *   </tr>
 *   <tr>
 *     <td>everything else under {@code /api/v1}</td>
 *     <td>{@code Authorization: Bearer}</td>
 *     <td>{@link AdminAuthFilter}</td>
 *   </tr>
 * </table>
 *
 * <p>{@code /actuator/**} is deliberately outside both. The load balancer's
 * health check and the Prometheus scrape have no credential to present, and
 * only {@code health} and {@code prometheus} are exposed.
 *
 * <h2>Why not Spring Security</h2>
 *
 * <p>Spring Security is the right answer for sessions, OAuth2, method
 * security, or anything with users and roles. This API has a fixed bearer
 * token and a key-hash lookup, which is about sixty lines of behaviour. Adding
 * the framework would mean explaining its filter chain, its authentication
 * manager and its context propagation in order to justify sixty lines, and the
 * project's working agreement is explicit that every part has to be
 * explainable. Recorded in
 * {@code docs/decisions/0003-filter-based-auth.md}.
 *
 * <p>The cost is that it has to be got right by hand -- constant-time
 * comparison, correct 401 shape, no path left uncovered by accident -- which
 * is why those are the things the tests target.
 */
@Configuration
@EnableConfigurationProperties(ApiSecurityProperties.class)
public class ApiSecurityConfig {

	private static final String INGEST_PATH = "/api/v1/readings";

	/**
	 * Prefix mapping. In servlet terms this matches {@code /api/v1} and
	 * everything beneath it, including the ingest path -- which is why
	 * {@link AdminAuthFilter} stands aside for that one path rather than
	 * relying on the pattern to exclude it. Servlet URL patterns cannot
	 * express exclusions.
	 */
	private static final String API_PATHS = "/api/v1/*";

	/*
	 * NOTE: the filters are constructed here rather than declared as @Bean or
	 * annotated @Component. Spring Boot auto-registers any Filter bean it
	 * finds against every request path. That would silently apply both filters
	 * to /actuator/** and to each other's paths, on top of the registrations
	 * below. Keeping them out of the bean factory makes these registrations
	 * the only way they are applied.
	 */

	@Bean
	FilterRegistrationBean<DeviceAuthFilter> deviceAuthFilterRegistration(
			DeviceAuthenticator authenticator, ProblemResponses problems) {

		var registration = new FilterRegistrationBean<>(new DeviceAuthFilter(authenticator, problems));
		registration.addUrlPatterns(INGEST_PATH);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
		registration.setName("deviceAuthFilter");
		return registration;
	}

	@Bean
	FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(
			ApiSecurityProperties properties, ProblemResponses problems) {

		var registration = new FilterRegistrationBean<>(
				new AdminAuthFilter(properties.adminToken(), problems));
		registration.addUrlPatterns(API_PATHS);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 101);
		registration.setName("adminAuthFilter");
		return registration;
	}
}
