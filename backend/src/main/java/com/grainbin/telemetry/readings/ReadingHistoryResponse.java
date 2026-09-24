package com.grainbin.telemetry.readings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body of {@code GET /api/v1/bins/{binId}/readings}: per-sensor
 * aggregates over time, one series per sensor, for the line charts.
 *
 * <p>Buckets are aligned to UTC hour or day boundaries, so the first and last
 * can be partial when {@code from} and {@code to} fall mid-bucket. A bucket with
 * no readings is omitted rather than returned empty; charting libraries handle
 * the gap, and filling it server-side would multiply the response size for
 * nothing.
 *
 * @param bucket the bucket width, as named in the request ({@code hour} or
 *               {@code day})
 */
public record ReadingHistoryResponse(long binId, Instant from, Instant to, String bucket, List<Series> series) {

	/** Every bucket for one sensor position, oldest first. */
	public record Series(int cable, int depth, List<Point> points) {
	}

	/**
	 * @param bucketStart start of the bucket, in UTC
	 * @param readings    how many readings the bucket aggregates
	 * @param moisturePct null when none of the readings carried a moisture value
	 */
	public record Point(Instant bucketStart, int readings, Stats temperatureC, Stats moisturePct) {
	}

	/** Averages are rounded to two decimal places; min and max are as stored. */
	public record Stats(BigDecimal avg, BigDecimal min, BigDecimal max) {
	}
}
