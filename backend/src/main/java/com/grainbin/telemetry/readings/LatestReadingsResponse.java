package com.grainbin.telemetry.readings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body of {@code GET /api/v1/bins/{binId}/latest}: the newest value from
 * each sensor position in the bin, for the heatmap grid.
 *
 * <p>Field names match the ingest payload -- {@code cable}, {@code depth},
 * {@code temperatureC}, {@code moisturePct} -- so a value reads the same way
 * going in and coming out.
 *
 * @param since   the start of the lookback window. A sensor with no reading
 *                since then is omitted rather than shown with a stale value.
 * @param sensors one entry per {@code (cable, depth)}, ordered by cable then
 *                depth
 */
public record LatestReadingsResponse(long binId, Instant since, List<SensorValue> sensors) {

	/**
	 * @param recordedAt when the value was taken, by the device's clock, so a
	 *                   client can show how old each value is
	 */
	public record SensorValue(
			int cable,
			int depth,
			BigDecimal temperatureC,
			BigDecimal moisturePct,
			Instant recordedAt) {
	}
}
