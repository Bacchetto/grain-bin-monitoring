package com.grainbin.telemetry.devices;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for key generation and hashing. No Spring, no database. */
class DeviceApiKeyTest {

	@Test
	@DisplayName("a generated key carries 32 bytes of entropy behind a recognisable prefix")
	void generatedKeyShape() {
		String key = DeviceApiKey.generate();

		assertThat(key).startsWith("gbk_");

		byte[] material = Base64.getUrlDecoder().decode(key.substring("gbk_".length()));
		assertThat(material)
				.as("256 bits, so guessing is not a threat model")
				.hasSize(32);
	}

	@Test
	@DisplayName("a generated key survives headers, URLs and shells without escaping")
	void generatedKeyIsUrlSafe() {
		// Base64url, unpadded. If this ever emits '+', '/' or '=' then keys
		// start breaking in query strings and curl commands.
		assertThat(DeviceApiKey.generate()).matches("^gbk_[A-Za-z0-9_-]+$");
	}

	@Test
	@DisplayName("keys do not repeat")
	void generatedKeysAreUnique() {
		Set<String> keys = new HashSet<>();
		IntStream.range(0, 1_000).forEach(i -> keys.add(DeviceApiKey.generate()));

		assertThat(keys).hasSize(1_000);
	}

	@Test
	@DisplayName("hashing is deterministic, so a key can be looked up by index")
	void hashIsStable() {
		String key = DeviceApiKey.generate();

		assertThat(DeviceApiKey.hash(key)).isEqualTo(DeviceApiKey.hash(key));
	}

	@Test
	@DisplayName("a hash is 64 lowercase hex characters")
	void hashShape() {
		assertThat(DeviceApiKey.hash(DeviceApiKey.generate())).matches("^[0-9a-f]{64}$");
	}

	@Test
	@DisplayName("different keys hash differently")
	void hashDistinguishesKeys() {
		assertThat(DeviceApiKey.hash(DeviceApiKey.generate()))
				.isNotEqualTo(DeviceApiKey.hash(DeviceApiKey.generate()));
	}

	@Test
	@DisplayName("the hash does not contain the key")
	void hashDoesNotLeakTheKey() {
		String key = DeviceApiKey.generate();

		// Stating the obvious, but this is the property the whole scheme rests
		// on: the database holds something from which the key cannot be read.
		assertThat(DeviceApiKey.hash(key)).doesNotContain(key.substring("gbk_".length()));
	}
}
