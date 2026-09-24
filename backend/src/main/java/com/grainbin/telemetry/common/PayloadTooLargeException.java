package com.grainbin.telemetry.common;

/**
 * The request is larger than the endpoint accepts. Rendered as 413.
 *
 * <p>413 rather than 400 because nothing about the request is malformed -- it
 * is simply too big -- and a client can fix it by splitting the same data into
 * smaller requests, which is exactly what 413 tells it to do.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final int limit;

    public PayloadTooLargeException(String detail, int limit) {
        super(detail);
        this.limit = limit;
    }

    public int limit() {
        return this.limit;
    }
}
