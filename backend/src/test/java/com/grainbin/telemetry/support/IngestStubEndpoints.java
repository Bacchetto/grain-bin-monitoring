package com.grainbin.telemetry.support;

import com.grainbin.telemetry.devices.AuthenticatedDevice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A stand-in for the ingest endpoint until Phase 7 builds the real one.
 *
 * <p>It exists so the device authentication filter has something to let a
 * request through to, and it echoes the {@link AuthenticatedDevice} the filter
 * resolved so tests can assert the filter put the right one there.
 *
 * <p><strong>Delete this when the real ingest controller lands.</strong> Two
 * handlers mapped to the same path fail the context with "Ambiguous mapping",
 * so the clash is loud rather than silent -- which is exactly what happened to
 * the {@code /api/v1/bins} stub when the real controller arrived in Phase 6.
 *
 * <p>It is both the {@code @TestConfiguration} and the {@code @RestController}
 * on purpose. A nested controller class would be registered twice -- once as a
 * nested component and once by any {@code @Bean} method -- which also fails
 * with "Ambiguous mapping".
 */
@TestConfiguration(proxyBeanMethods = false)
@RestController
public class IngestStubEndpoints {

	@PostMapping("/api/v1/readings")
	Map<String, Object> ingest(HttpServletRequest request) {
		AuthenticatedDevice device = AuthenticatedDevice.require(request);
		return Map.of("deviceId", device.deviceId(), "binId", device.binId());
	}
}
