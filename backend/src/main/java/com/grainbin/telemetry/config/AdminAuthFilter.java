package com.grainbin.telemetry.config;

import com.grainbin.telemetry.common.Digests;
import com.grainbin.telemetry.common.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;


/**
 * Authenticates the admin and dashboard endpoints with a single bearer token.
 *
 * <p>This is deliberately not production-grade auth. There is one token for
 * everyone, it never expires, it carries no identity, and revoking it means
 * redeploying. It exists so the project can demonstrate the parts it is
 * actually about -- ingest, storage and alerting -- without also building user
 * management. Recorded in
 * {@code docs/decisions/0003-filter-based-auth.md}.
 */
class AdminAuthFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	/**
	 * Authenticated by {@link DeviceAuthFilter} instead. This filter is
	 * registered on the whole of {@code /api/v1/*}, which necessarily includes
	 * the ingest path, so it has to stand aside for it.
	 */
	private static final String INGEST_PATH = "/api/v1/readings";

	/**
	 * The configured token, hashed once at construction.
	 *
	 * <p>Hashing both sides before comparing is what makes the comparison
	 * genuinely constant-time. {@link MessageDigest#isEqual} is time-constant
	 * for equal-length inputs, but it returns early when the lengths differ,
	 * so comparing raw tokens would leak the secret's length. Two SHA-256
	 * digests are always 32 bytes.
	 */
	private final byte[] expectedTokenDigest;

	private final ProblemResponses problems;

	AdminAuthFilter(String adminToken, ProblemResponses problems) {
		this.expectedTokenDigest = Digests.sha256(adminToken);
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return INGEST_PATH.equals(pathWithinApplication(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER)) {
			problems.unauthorized(request, response, "Bearer",
					"Missing or malformed Authorization header. Expected: Authorization: Bearer <token>");
			return;
		}

		if (!matchesConfiguredToken(header.substring(BEARER.length()).trim())) {
			problems.unauthorized(request, response, "Bearer", "The supplied bearer token is not valid.");
			return;
		}

		chain.doFilter(request, response);
	}

	private boolean matchesConfiguredToken(String presented) {
		byte[] presentedDigest = Digests.sha256(presented);
		return MessageDigest.isEqual(presentedDigest, expectedTokenDigest);
	}

	/**
	 * The request path with any deployment context path removed, so the
	 * comparison above does not silently stop matching if the application is
	 * ever served from a sub-path.
	 */
	private static String pathWithinApplication(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		return (contextPath == null || contextPath.isEmpty()) ? uri : uri.substring(contextPath.length());
	}
}
