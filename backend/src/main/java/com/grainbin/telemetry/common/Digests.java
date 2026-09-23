package com.grainbin.telemetry.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256, in the two shapes this service needs it.
 *
 * <p>Both credentials here are high-entropy machine secrets rather than
 * passwords, which is why a plain digest is appropriate and a deliberately
 * slow KDF is not. That reasoning is recorded in
 * {@code docs/decisions/0004-sha-256-for-device-api-keys.md} and applies only
 * while the secrets stay randomly generated.
 */
public final class Digests {

	private Digests() {
	}

	/**
	 * Raw digest. Used where the result is compared rather than stored,
	 * because two digests are always the same length and can therefore be
	 * compared in constant time.
	 */
	public static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (NoSuchAlgorithmException ex) {
			// Every conformant JRE provides SHA-256, so this cannot happen on
			// a working JVM and there is nothing sensible to recover to.
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	/** Convenience for UTF-8 text. */
	public static byte[] sha256(String input) {
		return sha256(input.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Lowercase hex digest. Used where the result is stored and looked up by
	 * index, which needs a stable textual form.
	 *
	 * @return 64 lowercase hex characters
	 */
	public static String sha256Hex(String input) {
		return HexFormat.of().formatHex(sha256(input));
	}
}
