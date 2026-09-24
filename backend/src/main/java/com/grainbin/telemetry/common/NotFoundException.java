package com.grainbin.telemetry.common;

/**
 * A resource named in the request path does not exist.
 *
 * <p>Carries the resource type and identifier separately so the handler can
 * build a consistent message without every call site inventing its own
 * wording.
 */
public class NotFoundException extends RuntimeException {

	private final String resource;
	private final Object id;

	public NotFoundException(String resource, Object id) {
		super(resource + " " + id + " does not exist");
		this.resource = resource;
		this.id = id;
	}

	public String resource() {
		return this.resource;
	}

	public Object id() {
		return this.id;
	}
}
