package com.grainbin.telemetry.devices;

import com.grainbin.telemetry.bins.BinRepository;
import com.grainbin.telemetry.common.NotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Registers a device against a bin.
 *
 * <p>Lives in {@code devices} rather than {@code bins} even though the path is
 * nested under a bin: the URL expresses that a device belongs to a bin, while
 * the behaviour -- generating a key, hashing it, deciding what is returned --
 * is entirely a device concern.
 */
@RestController
public class DeviceRegistrationController {

	private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationController.class);

	private final JdbcClient jdbc;
	private final BinRepository bins;

	public DeviceRegistrationController(JdbcClient jdbc, BinRepository bins) {
		this.jdbc = jdbc;
		this.bins = bins;
	}

	/**
	 * Creates a device and returns its API key once.
	 *
	 * <p>The bin is checked first so that a bad bin id is a 404 naming the
	 * bin, rather than a foreign key violation surfacing as a 409 about
	 * something the caller did not mention.
	 *
	 * <p>The generated key exists in memory for the length of this method and
	 * in the response body. It is never logged -- the log line below records
	 * the device id deliberately, and nothing else.
	 */
	@PostMapping("/api/v1/bins/{binId}/devices")
	ResponseEntity<RegisteredDeviceResponse> register(@PathVariable long binId,
			@Valid @RequestBody(required = false) RegisterDeviceRequest request,
			UriComponentsBuilder uriBuilder) {

		if (!this.bins.exists(binId)) {
			throw new NotFoundException("Bin", binId);
		}

		// An omitted body is equivalent to an empty one; both mean "defaults".
		RegisterDeviceRequest effective = (request == null) ? new RegisterDeviceRequest(null) : request;
		int interval = effective.intervalOrDefault();

		String apiKey = DeviceApiKey.generate();

		long deviceId = this.jdbc.sql("""
				INSERT INTO devices (bin_id, api_key_hash, expected_interval_seconds)
				VALUES (?, ?, ?)
				RETURNING id
				""")
				.param(binId)
				.param(DeviceApiKey.hash(apiKey))
				.param(interval)
				.query(Long.class)
				.single();

		log.info("Registered device {} on bin {} reporting every {}s", deviceId, binId, interval);

		URI location = uriBuilder.path("/api/v1/bins/{binId}/devices/{id}")
				.buildAndExpand(binId, deviceId)
				.toUri();

		return ResponseEntity.created(location)
				.body(new RegisteredDeviceResponse(deviceId, binId, interval, apiKey));
	}
}
