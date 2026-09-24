package com.grainbin.telemetry.devices;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body of {@code POST /api/v1/bins/{binId}/devices}.
 *
 * <p>The body may be omitted entirely, in which case the interval defaults.
 * A device is identified by its key, not by a name, so there is nothing else
 * to supply at registration.
 *
 * @param expectedIntervalSeconds how often the device should report. The
 *        DEVICE_OFFLINE alert fires at three times this, so the lower bound
 *        matters: a device claiming to report every second would be declared
 *        offline three seconds after any hiccup.
 */
public record RegisterDeviceRequest(

        @Min(value = 30, message = "must be at least 30 seconds")
        @Max(value = 86_400, message = "must be at most 86400 seconds (24 hours)")
        Integer expectedIntervalSeconds) {

    /** Matches the schema default. */
    public static final int DEFAULT_INTERVAL_SECONDS = 300;

    public int intervalOrDefault() {
        return (this.expectedIntervalSeconds == null)
                ? DEFAULT_INTERVAL_SECONDS
                : this.expectedIntervalSeconds;
    }
}
