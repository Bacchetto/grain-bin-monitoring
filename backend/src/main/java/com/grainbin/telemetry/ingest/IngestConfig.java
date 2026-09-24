package com.grainbin.telemetry.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link IngestProperties}. */
@Configuration
@EnableConfigurationProperties(IngestProperties.class)
class IngestConfig {
}
