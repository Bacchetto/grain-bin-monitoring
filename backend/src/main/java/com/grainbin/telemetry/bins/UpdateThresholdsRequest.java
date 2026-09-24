package com.grainbin.telemetry.bins;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/v1/bins/{id}/thresholds}.
 *
 * <p>Every field is optional and a null field means "leave this one alone" --
 * that is what makes this a PATCH rather than a PUT. Bean Validation skips
 * null values, so the range constraints below apply only to fields actually
 * supplied.
 *
 * <p>The ranges duplicate CHECK constraints that already exist in the schema.
 * That is deliberate: without them the database would reject the write and the
 * caller would get a 409 naming an internal constraint, instead of a 400 that
 * says which field was wrong.
 */
public record UpdateThresholdsRequest(

        @DecimalMin(value = "-50.0", message = "must be at least -50.0")
        @DecimalMax(value = "100.0", message = "must be at most 100.0")
        BigDecimal maxTemperatureC,

        @DecimalMin(value = "0.0", message = "must be at least 0.0")
        @DecimalMax(value = "100.0", message = "must be at most 100.0")
        BigDecimal maxMoisturePct,

        @DecimalMin(value = "0.1", message = "must be greater than zero")
        @DecimalMax(value = "100.0", message = "must be at most 100.0")
        BigDecimal riseThresholdC,

        @Min(value = 1, message = "must be at least 1 hour")
        @Max(value = 8760, message = "must be at most 8760 hours")
        Integer riseWindowHours) {

    /**
     * Rejects a body with nothing in it.
     *
     * <p>An empty patch would otherwise succeed as a no-op, which hides a
     * client that is sending the wrong field names: it would look like the
     * update worked while nothing changed.
     */
    @AssertTrue(message = "at least one threshold must be provided")
    public boolean isAnyThresholdPresent() {
        return this.maxTemperatureC != null
                || this.maxMoisturePct != null
                || this.riseThresholdC != null
                || this.riseWindowHours != null;
    }
}
