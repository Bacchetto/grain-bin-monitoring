package com.grainbin.telemetry.ingest;

import com.grainbin.telemetry.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shipped default for the ingest age limit.
 *
 * <p>The web tests widen the limit to a year so the on-demand partition path is
 * reachable. This runs in the non-web context, which loads
 * {@code application.properties} untouched, so it sees what production sees.
 * Same annotations as the other non-web integration tests, so it shares their
 * context rather than starting another container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IngestPropertiesTest {

	@Autowired
	private IngestProperties properties;

	@Test
	@DisplayName("samples older than 30 days are rejected by default")
	void defaultMaxSampleAgeIsThirtyDays() {
		assertThat(this.properties.maxSampleAge()).isEqualTo(Duration.ofDays(30));
	}
}
