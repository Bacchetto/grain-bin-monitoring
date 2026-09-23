package com.grainbin.telemetry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Supplies the application's source of "now".
 *
 * <p>Time-dependent behaviour is injected rather than read from
 * {@code Instant.now()} so that it can be driven deterministically in tests.
 * Several rules in this service turn on the current instant and cannot be
 * tested reliably against the real clock:
 *
 * <ul>
 *   <li>rejecting samples recorded more than five minutes in the future</li>
 *   <li>keeping readings partitions created ahead of time</li>
 *   <li>the trailing window for {@code RATE_OF_RISE} (Milestone 2)</li>
 *   <li>declaring a device offline after 3x its expected interval
 *       (Milestone 2)</li>
 * </ul>
 *
 * <p>Fixed to UTC deliberately. The database stores {@code TIMESTAMPTZ} and
 * the readings partitions have UTC month boundaries, so any local-time
 * reasoning here would be a bug waiting to happen when the server timezone
 * differs from the one a developer's machine happens to use.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * The zone all internal time arithmetic uses. Exposed as a constant so
	 * that callers converting an {@link java.time.Instant} to a month or a day
	 * cannot quietly pick up the system default.
	 */
	public static final ZoneOffset APPLICATION_ZONE = ZoneOffset.UTC;
}
