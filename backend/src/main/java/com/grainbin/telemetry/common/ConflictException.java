package com.grainbin.telemetry.common;

/**
 * The request is valid, but the resource's current state does not allow it --
 * acknowledging an alert that has already resolved, for example.
 *
 * <p>Rendered as 409. Unlike a 400, the request was well-formed; unlike a
 * 404, the resource exists. Repeating the request unchanged will not help.
 */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}
