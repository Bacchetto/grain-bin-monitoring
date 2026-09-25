package com.grainbin.telemetry.alerts;

/**
 * An alert's place in its lifecycle: {@code OPEN -> ACKNOWLEDGED -> RESOLVED}.
 *
 * <p>Acknowledging is optional. An open alert whose condition clears goes
 * straight to {@code RESOLVED}, and an acknowledged one still resolves on its
 * own: acknowledging a hot spot means someone has seen it, not that it has
 * cooled.
 */
public enum AlertStatus {
	OPEN,
	ACKNOWLEDGED,
	RESOLVED
}
