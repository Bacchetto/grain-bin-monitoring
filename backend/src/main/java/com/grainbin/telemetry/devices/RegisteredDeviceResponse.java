package com.grainbin.telemetry.devices;

/**
 * Response to registering a device.
 *
 * <p><strong>This is the only time the API ever returns the plaintext key.</strong>
 * Only a SHA-256 digest is stored, so the key cannot be recovered afterwards
 * and a lost key means registering a replacement device.
 *
 * <p>The field is named {@code apiKey} and appears in exactly one response
 * body. Nothing else in the API returns it, and it is never logged.
 *
 * @param apiKey the plaintext key, shown once and not recoverable
 */
public record RegisteredDeviceResponse(
        long id,
        long binId,
        int expectedIntervalSeconds,
        String apiKey) {
}
