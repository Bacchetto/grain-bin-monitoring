package com.grainbin.telemetry.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns exceptions into RFC 9457 Problem Details responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own
 * exceptions -- unreadable body, wrong content type, missing parameter,
 * unsupported method -- already come back as {@code application/problem+json}
 * without being listed here. Only the cases needing more than the default are
 * overridden.
 *
 * <p>The authentication filters produce the same shape by hand, because they
 * run before the {@code DispatcherServlet} and never reach this class. See
 * {@link ProblemResponses}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/**
	 * A resource named in the path does not exist.
	 *
	 * <p>Returned for a bin id that is absent, and equally for one the caller
	 * simply invented. With a single admin token there is no ownership to
	 * distinguish, so there is nothing here to leak.
	 */
	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Not Found");
		problem.setProperty("resource", ex.resource());
		problem.setProperty("id", ex.id());
		return problem;
	}

	/**
	 * A unique constraint rejected the write.
	 *
	 * <p>409 rather than 400: the request was well-formed, it conflicts with
	 * state that already exists. Retrying it unchanged will not help, which is
	 * exactly what 409 tells the client.
	 *
	 * <p>The constraint name is deliberately not echoed. It names internal
	 * schema objects and would tie the API's error contract to the schema.
	 */
	@ExceptionHandler(DuplicateKeyException.class)
	ProblemDetail handleDuplicate(DuplicateKeyException ex) {
		log.debug("Rejected a conflicting write", ex);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"That resource already exists. A bin name must be unique within its site.");
		problem.setTitle("Conflict");
		return problem;
	}

	/**
	 * Bean Validation rejected the request body.
	 *
	 * <p>The default response says only that validation failed. This adds an
	 * {@code errors} member naming each field and what was wrong with it,
	 * because a client that cannot tell <em>which</em> field to fix has to
	 * guess.
	 *
	 * <p>{@code errors} is an extension member, which RFC 9457 explicitly
	 * allows alongside the standard ones.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
				.map(error -> {
					Map<String, String> entry = new LinkedHashMap<>();
					// Object-level constraints (such as "at least one
					// threshold must be provided") have no field, so they are
					// reported against the body as a whole.
					entry.put("field", (error instanceof org.springframework.validation.FieldError fieldError)
							? fieldError.getField() : "");
					entry.put("message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
					return entry;
				})
				.sorted(Comparator.comparing(entry -> entry.get("field")))
				.toList();

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"The request body failed validation.");
		problem.setTitle("Bad Request");
		problem.setProperty("errors", errors);

		return ResponseEntity.of(problem).build();
	}
}
