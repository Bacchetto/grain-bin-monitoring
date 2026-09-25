package com.grainbin.telemetry.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of {@code POST /api/v1/readings}: one or more reporting cycles from a
 * single device.
 *
 * <pre>
 * { "samples": [
 *     { "seq": 1042, "recordedAt": "2026-10-01T14:00:00Z",
 *       "sensors": [ { "cable": 0, "depth": 0, "temperatureC": 11.4, "moisturePct": 13.9 } ] }
 * ] }
 * </pre>
 *
 * <p>There is deliberately no device or bin id anywhere in this shape. Both come
 * from the authenticated API key. If a client sends one anyway it is ignored,
 * because unknown JSON properties are ignored -- it is never trusted.
 *
 * <p>The sample-count limit is <em>not</em> expressed here. Exceeding it is a
 * 413, not a validation failure, and is checked before these constraints run;
 * see {@code IngestController}.
 */
public record IngestRequest(@NotEmpty List<@NotNull @Valid Sample> samples) {

	/**
	 * Upper bound on sensors in one sample. The README does not specify one, but
	 * without it a single sample could carry an unbounded number of rows and the
	 * 500-sample batch limit would bound nothing. Real grain cables carry a few
	 * dozen sensors per bin; 256 is generous without being unbounded, and caps a
	 * whole batch at 128,000 rows.
	 */
	public static final int MAX_SENSORS_PER_SAMPLE = 256;

	/**
	 * One reporting cycle.
	 *
	 * @param seq        device-assigned and monotonically increasing; part of
	 *                   the idempotency key
	 * @param recordedAt the device's clock. Determines ordering and partition,
	 *                   never arrival order.
	 */
	public record Sample(
			@NotNull @PositiveOrZero Long seq,
			@NotNull Instant recordedAt,
			@NotEmpty @Size(max = MAX_SENSORS_PER_SAMPLE) List<@NotNull @Valid Sensor> sensors) {
	}

	/**
	 * One sensor value.
	 *
	 * <p>The temperature bounds are what {@code NUMERIC(4,1)} can store, not what
	 * is physically plausible. That is deliberate: a value the column cannot hold
	 * would otherwise surface as a database error, but a value that is merely
	 * implausible -- the {@code -127} a disconnected DS18B20 probe reports -- is a
	 * data-quality question for the alert engine, not a reason to fail the batch.
 * See {@code docs/enhancements.md}, E3.
	 *
	 * <p>The upper bound on cable and depth keeps values inside {@code SMALLINT}
	 * with room to spare; no real bin has 256 cables.
	 *
	 * @param moisturePct optional; not every sensor measures moisture
	 */
	public record Sensor(
			@NotNull @Min(0) @Max(255) Integer cable,
			@NotNull @Min(0) @Max(255) Integer depth,
			@NotNull @DecimalMin("-999.9") @DecimalMax("999.9") BigDecimal temperatureC,
			@DecimalMin("0.0") @DecimalMax("100.0") BigDecimal moisturePct) {
	}
}
