package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.readings.ReadingQueryRepository.SensorPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.grainbin.telemetry.config.ClockConfig.APPLICATION_ZONE;

/**
 * {@code RATE_OF_RISE}: a sensor whose temperature has risen by at least the
 * bin's {@code rise_threshold_c} over its {@code rise_window_hours}.
 *
 * <h2>How a rise is measured: daily averages</h2>
 *
 * <pre>
 * rise = avg(last 24 h) - avg(the 24 h that ended rise_window_hours ago)
 *
 *        |-- baseline 24 h --|                    |-- current 24 h --|
 *   now - W - 24h        now - W                now - 24h           now
 * </pre>
 *
 * <p>The obvious rule -- newest reading minus the lowest in the window --
 * raises false alarms every afternoon. Grain near the top of a bin warms and
 * cools with the air above it, by about 3 degrees a day in the simulator's
 * {@code normal} scenario, and the default threshold is 2. Averaging whole
 * days cancels that cycle exactly, because every hour of the day is counted
 * once in each window. It also blunts single noisy readings. The cost is
 * reaction time: a rise shows fully only once a day of it has been averaged,
 * which suits grain, where spoilage builds over days. See ADR 0013.
 *
 * <p>Each window is its own range on the {@code (bin_id, recorded_at)}
 * index, so the query reads two days of a bin's data whatever the window
 * length is.
 *
 * <h2>When a sensor is not judged at all</h2>
 *
 * <p><strong>Coverage.</strong> Each window must hold at least half the
 * readings the device's registered interval implies for a day. An average of
 * a few hours reintroduces the daily cycle -- an afternoon-only window reads
 * warm -- so a thinly covered window is not evidence either way: no
 * detection, and no clear. That also covers a new device, which has no
 * baseline until it has reported for {@code rise_window_hours} plus a day.
 *
 * <p><strong>Plausibility.</strong> Probe fault values are left out of both
 * averages and both counts ({@link SensorPlausibility}).
 *
 * <p>A sensor that is judged is one evaluation per run: a rise at or above
 * the threshold is a detection, below it is a clear, and three clear runs
 * resolve the alert.
 */
@Service
public class RateOfRiseEvaluator {

	private static final Duration DAY = Duration.ofDays(1);
	private static final long SECONDS_PER_DAY = DAY.toSeconds();

	private static final String BINS = """
			SELECT id, rise_threshold_c, rise_window_hours FROM bins
			""";

	/*
	 * Both windows in one pass. Each aggregate is filtered to its own window;
	 * a row can count in both when the windows overlap, which happens when
	 * rise_window_hours is under 24, and is correct -- the two averages are
	 * independent.
	 *
	 * Grouped by device as well as position so each sensor's coverage is
	 * judged against the interval of the device that reports it. The join to
	 * devices also drops readings from a device that no longer exists
	 * (readings has no foreign key, ADR 0001).
	 */
	private static final String DAILY_AVERAGES = """
			SELECT r.device_id,
			       r.cable_index,
			       r.depth_index,
			       d.expected_interval_seconds,
			       avg(r.temperature_c)
			           FILTER (WHERE r.recorded_at >= :currentFrom AND r.recorded_at < :now)       AS current_avg,
			       count(*)
			           FILTER (WHERE r.recorded_at >= :currentFrom AND r.recorded_at < :now)       AS current_n,
			       avg(r.temperature_c)
			           FILTER (WHERE r.recorded_at >= :baselineFrom AND r.recorded_at < :baselineTo) AS baseline_avg,
			       count(*)
			           FILTER (WHERE r.recorded_at >= :baselineFrom AND r.recorded_at < :baselineTo) AS baseline_n
			FROM readings r
			JOIN devices d ON d.id = r.device_id
			WHERE r.bin_id = :binId
			  AND (   (r.recorded_at >= :currentFrom  AND r.recorded_at < :now)
			       OR (r.recorded_at >= :baselineFrom AND r.recorded_at < :baselineTo))
			  AND %s
			GROUP BY r.device_id, r.cable_index, r.depth_index, d.expected_interval_seconds
			""".formatted(SensorPlausibility.PLAUSIBLE_TEMPERATURE_SQL);

	private record Bin(long id, BigDecimal riseThresholdC, int riseWindowHours) {
	}

	private record SensorWindows(SensorPosition position, int expectedIntervalSeconds,
			BigDecimal currentAvg, long currentCount, BigDecimal baselineAvg, long baselineCount) {

		/** Enough readings in both windows for their averages to mean a whole day. */
		boolean covered() {
			return isCovered(currentCount) && isCovered(baselineCount);
		}

		/*
		 * count >= (readings a day at this interval) / 2, rearranged to stay in
		 * integers: count * 2 * interval >= seconds per day.
		 */
		private boolean isCovered(long count) {
			return count * 2 * expectedIntervalSeconds >= SECONDS_PER_DAY;
		}

		BigDecimal rise() {
			// Two decimal places: the alerts.trigger_value column is NUMERIC(6,2).
			return currentAvg.subtract(baselineAvg).setScale(2, RoundingMode.HALF_UP);
		}
	}

	private final JdbcClient jdbc;
	private final ScheduledJobLock lock;
	private final AlertRepository alerts;
	private final AlertLifecycle lifecycle;

	public RateOfRiseEvaluator(JdbcClient jdbc, ScheduledJobLock lock, AlertRepository alerts,
			AlertLifecycle lifecycle) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.alerts = alerts;
		this.lifecycle = lifecycle;
	}

	/**
	 * Judges every sensor of every bin against {@code now}.
	 *
	 * @return empty if another instance is running this job right now
	 */
	public Optional<EvaluationRun> evaluate(Instant now) {
		return this.lock.runExclusively(ScheduledJobLock.Job.RATE_OF_RISE, () -> evaluateLocked(now));
	}

	private EvaluationRun evaluateLocked(Instant now) {
		List<Bin> bins = this.jdbc.sql(BINS)
				.query((rs, rowNum) -> new Bin(
						rs.getLong("id"), rs.getBigDecimal("rise_threshold_c"), rs.getInt("rise_window_hours")))
				.list();

		int evaluated = 0;
		int detected = 0;
		int clears = 0;
		for (Bin bin : bins) {
			Map<SensorPosition, Long> open = this.alerts
					.findOpenSensorAlerts(bin.id(), List.of(AlertType.RATE_OF_RISE)).stream()
					.collect(Collectors.toMap(
							alert -> new SensorPosition(alert.cableIndex(), alert.depthIndex()),
							AlertRepository.OpenSensorAlert::alertId));

			for (SensorWindows sensor : dailyAverages(bin, now)) {
				if (!sensor.covered()) {
					continue;
				}
				evaluated++;
				BigDecimal rise = sensor.rise();
				if (rise.compareTo(bin.riseThresholdC()) >= 0) {
					this.lifecycle.sensorConditionDetected(bin.id(), AlertType.RATE_OF_RISE,
							sensor.position().cable(), sensor.position().depth(),
							rise, bin.riseThresholdC(), now);
					detected++;
				}
				else {
					Long openAlert = open.get(sensor.position());
					if (openAlert != null) {
						this.lifecycle.conditionClear(openAlert, now);
						clears++;
					}
				}
			}
		}
		return new EvaluationRun(evaluated, detected, clears);
	}

	private List<SensorWindows> dailyAverages(Bin bin, Instant now) {
		Instant baselineTo = now.minus(Duration.ofHours(bin.riseWindowHours()));
		return this.jdbc.sql(DAILY_AVERAGES)
				.param("binId", bin.id())
				.param("now", utc(now))
				.param("currentFrom", utc(now.minus(DAY)))
				.param("baselineFrom", utc(baselineTo.minus(DAY)))
				.param("baselineTo", utc(baselineTo))
				.query((rs, rowNum) -> new SensorWindows(
						new SensorPosition(rs.getInt("cable_index"), rs.getInt("depth_index")),
						rs.getInt("expected_interval_seconds"),
						rs.getBigDecimal("current_avg"),
						rs.getLong("current_n"),
						rs.getBigDecimal("baseline_avg"),
						rs.getLong("baseline_n")))
				.list();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, APPLICATION_ZONE);
	}
}
