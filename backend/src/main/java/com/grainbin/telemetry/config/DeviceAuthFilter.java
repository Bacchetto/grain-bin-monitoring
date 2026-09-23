package com.grainbin.telemetry.config;

import com.grainbin.telemetry.common.ProblemResponses;
import com.grainbin.telemetry.devices.AuthenticatedDevice;
import com.grainbin.telemetry.devices.DeviceAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates the ingest endpoint from the {@code X-Device-Key} header.
 *
 * <p>On success the resolved {@link AuthenticatedDevice} is left on the
 * request for the controller. This is the mechanism behind the rule that a
 * device never sends its own id: the ingest handler reads {@code deviceId} and
 * {@code binId} from here, not from the request body, so a device physically
 * cannot write readings attributed to another bin.
 */
class DeviceAuthFilter extends OncePerRequestFilter {

	static final String DEVICE_KEY_HEADER = "X-Device-Key";

	/**
	 * Sent on a 401 so the response is a well-formed challenge. The scheme
	 * name is the header itself, since this is not one of the registered HTTP
	 * authentication schemes.
	 */
	private static final String CHALLENGE = DEVICE_KEY_HEADER;

	private final DeviceAuthenticator authenticator;
	private final ProblemResponses problems;

	DeviceAuthFilter(DeviceAuthenticator authenticator, ProblemResponses problems) {
		this.authenticator = authenticator;
		this.problems = problems;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		String presentedKey = request.getHeader(DEVICE_KEY_HEADER);
		if (presentedKey == null || presentedKey.isBlank()) {
			problems.unauthorized(request, response, CHALLENGE,
					"Missing " + DEVICE_KEY_HEADER + " header.");
			return;
		}

		Optional<AuthenticatedDevice> device = authenticator.authenticate(presentedKey);
		if (device.isEmpty()) {
			// Says only that the key was not accepted. Reporting anything
			// about why -- wrong length, wrong prefix, once existed but was
			// revoked -- would help an attacker sort guesses into better and
			// worse ones.
			problems.unauthorized(request, response, CHALLENGE,
					"The supplied " + DEVICE_KEY_HEADER + " is not valid.");
			return;
		}

		request.setAttribute(AuthenticatedDevice.REQUEST_ATTRIBUTE, device.get());
		chain.doFilter(request, response);
	}
}
