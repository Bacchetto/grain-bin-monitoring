package com.grainbin.telemetry.bins;

import java.math.BigDecimal;

/**
 * The four per-bin alert thresholds.
 *
 * <p>{@link BigDecimal} rather than {@code double} throughout, matching the
 * {@code NUMERIC(4,1)} columns. These values are compared for ordering and
 * shown to people; binary floating point would introduce rounding that has no
 * business being in a threshold a user typed.
 */
public record BinThresholds(
        BigDecimal maxTemperatureC,
        BigDecimal maxMoisturePct,
        BigDecimal riseThresholdC,
        int riseWindowHours) {
}
