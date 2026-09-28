package com.grainbin.telemetry.alerts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One alert, as the API returns it.
 *
 * <p>Nullable fields, by alert type:
 *
 * <ul>
 *   <li>Sensor alerts carry {@code cableIndex}, {@code depthIndex}, and the
 *       trigger and threshold values. {@code deviceId} and
 *       {@code deviceLastSeenAt} are null.</li>
 *   <li>{@code DEVICE_OFFLINE} carries {@code deviceId} and
 *       {@code deviceLastSeenAt} -- null if the device has never reported --
 *       and nothing else. How long it has been silent is
 *       {@code now - deviceLastSeenAt}, always current, rather than a number
 *       frozen at detection time.</li>
 *   <li>{@code acknowledgedAt} and {@code resolvedAt} are null until the alert
 *       reaches that state.</li>
 * </ul>
 */
public record AlertResponse(
		long id,
		long binId,
		String binName,
		AlertType type,
		AlertStatus status,
		Integer cableIndex,
		Integer depthIndex,
		Long deviceId,
		Instant deviceLastSeenAt,
		BigDecimal triggerValue,
		BigDecimal thresholdValue,
		Instant firstDetectedAt,
		Instant lastDetectedAt,
		Instant acknowledgedAt,
		Instant resolvedAt) {
}
