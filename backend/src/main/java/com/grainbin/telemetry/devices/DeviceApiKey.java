package com.grainbin.telemetry.devices;

import com.grainbin.telemetry.common.Digests;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates device API keys and reduces them to the digest stored in
 * {@code devices.api_key_hash}.
 *
 * <p>A key is shown to the operator exactly once, when the device is
 * registered, and is not recoverable afterwards. Only the digest is stored.
 *
 * <h2>Why SHA-256 and not bcrypt</h2>
 *
 * <p>This is the opposite of a password. A password is short, low-entropy and
 * chosen by a human, so it must be slow to verify: a deliberately expensive
 * KDF is what makes an offline attack on a stolen hash impractical.
 *
 * <p>An API key here is 32 bytes from {@link SecureRandom} -- 256 bits of
 * entropy, never chosen, never reused, never typed from memory. Brute-forcing
 * it is not a question of how fast the hash is. Meanwhile it is verified on
 * every single ingest request, which is the one path in this service expected
 * to sustain load, and a KDF tuned to take 100ms would cap throughput at ten
 * requests per second per core.
 *
 * <p>Recorded in {@code docs/decisions/0004-sha-256-for-device-api-keys.md}.
 *
 * <p>This reasoning holds <strong>only</strong> because the key is
 * full-entropy random. If keys ever become operator-chosen, or shorter, this
 * choice becomes wrong and must be revisited.
 */
public final class DeviceApiKey {

	/**
	 * Marks a string as a grain-bin device key. Constant, so it adds no
	 * entropy; its value is that a leaked key is recognisable for what it is
	 * in a log or a commit, which is what secret-scanning tools match on.
	 */
	static final String PREFIX = "gbk_";

	/** 256 bits. Well beyond brute force, and the natural SHA-256 input size. */
	private static final int KEY_BYTES = 32;

	/**
	 * Seeded by the OS and safe for concurrent use. Held statically so that
	 * key generation does not re-seed per call.
	 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * URL-safe and unpadded, so a key survives being put in a header, a query
	 * string or a shell command without escaping.
	 */
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private DeviceApiKey() {
	}

	/**
	 * @return a new plaintext key. The caller is responsible for returning it
	 *         to the operator once and then discarding it.
	 */
	public static String generate() {
		byte[] material = new byte[KEY_BYTES];
		RANDOM.nextBytes(material);
		return PREFIX + ENCODER.encodeToString(material);
	}

	/**
	 * Reduces a presented key to the lowercase hex digest stored in the
	 * database. Deterministic, so the digest can be looked up directly by
	 * index rather than compared row by row.
	 *
	 * @param key the plaintext key as presented by a device
	 * @return 64 lowercase hex characters
	 */
	public static String hash(String key) {
		return Digests.sha256Hex(key);
	}
}
