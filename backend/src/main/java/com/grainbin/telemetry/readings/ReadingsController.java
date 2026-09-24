package com.grainbin.telemetry.readings;

import com.grainbin.telemetry.bins.BinRepository;
import com.grainbin.telemetry.common.NotFoundException;
import com.grainbin.telemetry.common.RequestValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The dashboard read endpoints for one bin.
 *
 * <p>Authenticated by {@code AdminAuthFilter}, which covers all of
 * {@code /api/v1}.
 */
@RestController
@RequestMapping("/api/v1/bins/{binId}")
public class ReadingsController {

	/**
	 * How far back {@code /latest} looks for each sensor's newest value.
	 *
	 * <p>This is a semantic choice as much as a performance one. A sensor that
	 * has reported nothing for a week is effectively dead, and showing its
	 * week-old value in the heatmap as though it were current would be
	 * misleading -- the grain may have heated since. Such a sensor is left out,
	 * so the grid shows a gap rather than a stale number. By then the device
	 * will long since have raised {@code DEVICE_OFFLINE}.
	 *
	 * <p>The bound also lets PostgreSQL skip every partition older than the
	 * window. Seven days touches at most two monthly partitions.
	 */
	static final Duration LATEST_LOOKBACK = Duration.ofDays(7);

	/**
	 * Most buckets one history request may return per sensor. Hourly, that is
	 * about 41 days; daily, about 2.7 years. Without a cap, a request for ten
	 * years of hourly data would ask for 87,600 buckets per sensor. The 30-day
	 * dashboard view fits comfortably at hourly resolution.
	 */
	static final int MAX_BUCKETS = 1000;

	private final ReadingQueryRepository readings;
	private final BinRepository bins;
	private final Clock clock;

	public ReadingsController(ReadingQueryRepository readings, BinRepository bins, Clock clock) {
		this.readings = readings;
		this.bins = bins;
		this.clock = clock;
	}

	/** The newest value from each sensor, for the heatmap grid. */
	@GetMapping("/latest")
	LatestReadingsResponse latest(@PathVariable long binId) {
		requireBin(binId);
		Instant since = this.clock.instant().minus(LATEST_LOOKBACK);
		return new LatestReadingsResponse(binId, since, this.readings.latest(binId, since));
	}

	/**
	 * Per-sensor average, minimum and maximum in hourly or daily buckets over
	 * {@code [from, to)}, for the line charts.
	 *
	 * <p>{@code from} and {@code to} are required ISO-8601 instants, for example
	 * {@code 2026-09-24T00:00:00Z}. A missing or unparseable one is a 400 from
	 * Spring MVC before this method runs. {@code bucket} defaults to
	 * {@code hour}.
	 */
	@GetMapping("/readings")
	ReadingHistoryResponse history(@PathVariable long binId,
			@RequestParam Instant from,
			@RequestParam Instant to,
			@RequestParam(defaultValue = "hour") String bucket) {

		Bucket width = Bucket.parse(bucket).orElseThrow(() -> new RequestValidationException("bucket",
				"must be one of: " + Bucket.allowedValues()));

		if (!from.isBefore(to)) {
			throw new RequestValidationException("from", "must be earlier than to");
		}

		long buckets = Duration.between(from, to).dividedBy(width.width());
		if (buckets > MAX_BUCKETS) {
			throw new RequestValidationException("bucket", "the range covers " + buckets + " "
					+ width.apiName() + " buckets; at most " + MAX_BUCKETS
					+ " are returned per request. Use a shorter range or a wider bucket.");
		}

		// Parameters are validated before the bin is looked up, so a malformed
		// request costs no database round trip.
		requireBin(binId);

		return new ReadingHistoryResponse(binId, from, to, width.apiName(),
				this.readings.history(binId, from, to, width));
	}

	private void requireBin(long binId) {
		if (!this.bins.exists(binId)) {
			throw new NotFoundException("Bin", binId);
		}
	}
}
