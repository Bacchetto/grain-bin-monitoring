package com.grainbin.telemetry.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Writes RFC 9457 Problem Details responses from inside a servlet filter.
 *
 * <p>Controllers get this for free: an exception thrown from a handler is
 * turned into {@code application/problem+json} by Spring MVC. Filters run
 * <em>before</em> the {@code DispatcherServlet}, so a filter that rejects a
 * request has to serialize the body itself or the client gets a bare status
 * code with an empty body.
 *
 * <p>Using the same {@link ProblemDetail} type the controllers use keeps the
 * error shape identical whether a request was rejected at the door or deeper
 * in, which is the point: a client should not need to know where in the stack
 * something failed in order to parse the response.
 */
@Component
public class ProblemResponses {

	private final ObjectMapper json;

	public ProblemResponses(ObjectMapper json) {
		this.json = json;
	}

	/**
	 * Rejects a request as unauthenticated.
	 *
	 * @param challenge value for the {@code WWW-Authenticate} header, which
	 *                  RFC 9110 requires on a 401 so the client knows which
	 *                  scheme to use
	 * @param detail    a human-readable explanation. Must describe what the
	 *                  client failed to supply, never whether a supplied
	 *                  credential happened to match something.
	 */
	public void unauthorized(HttpServletRequest request, HttpServletResponse response,
			String challenge, String detail) throws IOException {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
		problem.setTitle("Unauthorized");
		problem.setInstance(URI.create(request.getRequestURI()));

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(json.writeValueAsString(problem));
		response.getWriter().flush();
	}
}
