package com.grainbin.telemetry.common;

import jakarta.validation.ConstraintViolation;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A request failed validation that was run by hand rather than by
 * {@code @Valid} -- a body checked in a particular order, or query parameters
 * that must be checked against each other.
 *
 * <p>Exists for endpoints that must check something <em>before</em> field
 * validation runs. The ingest endpoint rejects an oversized batch with 413
 * first, because validating every one of 100,000 samples only to reject the
 * whole request for its size would be wasted work. {@code @Valid} runs before
 * the handler body, so it cannot express that ordering.
 *
 * <p>Rendered with the same {@code errors} shape as {@code @Valid} failures,
 * so a client sees one error format whichever path produced it.
 */
public class RequestValidationException extends RuntimeException {

    /** One failed constraint: where it was, and what was wrong. */
    public record FieldError(String field, String message) {
    }

    private final List<FieldError> errors;

    public RequestValidationException(Set<? extends ConstraintViolation<?>> violations) {
        super("The request body failed validation.");
        this.errors = violations.stream()
                // Property paths are indexed -- samples[3].sensors[0].temperatureC
                // -- which tells a device exactly which reading to fix.
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldError::field))
                .toList();
    }

    /**
     * A single failed check that Bean Validation does not express, such as a
     * query parameter that must be earlier than another.
     */
    public RequestValidationException(String field, String message) {
        super("The request failed validation.");
        this.errors = List.of(new FieldError(field, message));
    }

    public List<FieldError> errors() {
        return this.errors;
    }
}
