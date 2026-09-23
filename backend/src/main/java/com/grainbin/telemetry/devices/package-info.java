/**
 * Monitoring controllers attached to bins.
 *
 * <p>Owns device registration, API key generation and hashing, and the
 * {@code last_seen_at} timestamp that the DEVICE_OFFLINE alert depends on.
 * A device authenticates with its own key and never sends its own id.
 */
package com.grainbin.telemetry.devices;
