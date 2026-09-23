package com.grainbin.telemetry.devices;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves a presented API key to the device that owns it.
 *
 * <p>The key is hashed and the digest looked up directly by its unique index,
 * so this is one indexed probe per ingest request rather than a scan and
 * compare.
 */
@Component
public class DeviceAuthenticator {

	private final JdbcClient jdbc;

	public DeviceAuthenticator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param presentedKey the raw {@code X-Device-Key} header value
	 * @return the device, or empty if no device holds that key
	 */
	public Optional<AuthenticatedDevice> authenticate(String presentedKey) {
		// There is no timing-safe comparison here and none is needed: the
		// lookup is by digest, and an attacker learning that a given 256-bit
		// random key does not exist has learned nothing they could not have
		// assumed. Timing safety matters for the admin token, which is a
		// single long-lived secret compared against a presented value.
		String digest = DeviceApiKey.hash(presentedKey);

		// Deliberately not cached. It is one indexed lookup, and a cache would
		// need invalidating when a device is deleted or re-keyed -- which
		// would mean a revoked key kept working until the entry expired. If
		// load testing shows this matters, cache with a short TTL and accept
		// that revocation is delayed by it, as an explicit decision.
		return jdbc.sql("""
				SELECT id, bin_id, expected_interval_seconds
				FROM devices
				WHERE api_key_hash = ?
				""")
				.param(digest)
				.query((rs, rowNum) -> new AuthenticatedDevice(
						rs.getLong("id"),
						rs.getLong("bin_id"),
						rs.getInt("expected_interval_seconds")))
				.optional();
	}
}
