package com.grainbin.telemetry.bins;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/bins}.
 *
 * <p>Thresholds are not settable here. A new bin takes the documented
 * defaults and is adjusted afterwards through
 * {@code PATCH /bins/{id}/thresholds}, which keeps one endpoint responsible
 * for threshold validation instead of two.
 *
 * @param capacityBushels optional; some bins are not recorded with a capacity
 */
public record CreateBinRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String site,

        @NotBlank(message = "must not be blank")
        @Size(max = 50, message = "must be at most 50 characters")
        String grainType,

        @Positive(message = "must be greater than zero")
        Integer capacityBushels) {
}
