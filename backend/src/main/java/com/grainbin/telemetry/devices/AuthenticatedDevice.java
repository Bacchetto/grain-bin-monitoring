package com.grainbin.telemetry.devices;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * The device behind an authenticated ingest request.
 *
 * <p>This is the whole reason a device never sends its own id. The device
 * authenticates with its key, the server resolves that key to this record, and
 * the ingest path takes {@code deviceId} and {@code binId} from here. A caller
 * cannot name a device, so it cannot name someone else's.
 *
 * @param deviceId                 the authenticated device
 * @param binId                    the bin it is attached to, denormalised onto
 *                                 every reading it writes
 * @param expectedIntervalSeconds  how often it should report; the
 *                                 DEVICE_OFFLINE alert fires at 3x this
 */
public record AuthenticatedDevice(long deviceId, long binId, int expectedIntervalSeconds) {

	/**
	 * Where the authentication filter leaves this for the controller. Keyed by
	 * class name so it cannot collide with anything else on the request.
	 */
	public static final String REQUEST_ATTRIBUTE = AuthenticatedDevice.class.getName();

	public static Optional<AuthenticatedDevice> from(HttpServletRequest request) {
		return Optional.ofNullable((AuthenticatedDevice) request.getAttribute(REQUEST_ATTRIBUTE));
	}

	/**
	 * @throws IllegalStateException if the request was not authenticated. That
	 *         is a wiring bug, not a client error: reaching a handler that
	 *         needs a device without one means the filter is not registered on
	 *         this path, and failing loudly is safer than treating an
	 *         unauthenticated request as anonymous.
	 */
	public static AuthenticatedDevice require(HttpServletRequest request) {
		return from(request).orElseThrow(() -> new IllegalStateException(
				"No authenticated device on the request. DeviceAuthFilter is not "
						+ "registered for " + request.getRequestURI()));
	}
}
