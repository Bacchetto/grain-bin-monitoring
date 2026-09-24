package com.grainbin.telemetry.bins;

import java.time.Instant;

/** Response body of {@code GET /api/v1/bins/{id}}. */
public record BinDetailResponse(
        long id,
        String name,
        String site,
        String grainType,
        Integer capacityBushels,
        BinThresholds thresholds,
        Instant createdAt) {
}
