package com.grainbin.telemetry.ingest;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunable ingest settings, bound from {@code app.ingest.*}.
 *
 * @param maxSampleAge how far in the past, by the server's clock, a sample may
 *                     have been recorded and still be stored. Older samples
 *                     are counted as rejected. See ADR 0006.
 */
@ConfigurationProperties("app.ingest")
@Validated
public record IngestProperties(

        /*
         * At least a day. A limit shorter than the longest ordinary outage a
         * device might buffer through would throw away real data the moment the
         * device reconnected.
         */
        @NotNull @DurationMin(days = 1) Duration maxSampleAge) {
}
