package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.bins.BinRepository;
import com.grainbin.telemetry.bins.BinThresholds;
import com.grainbin.telemetry.readings.ReadingQueryRepository;
import com.grainbin.telemetry.readings.ReadingQueryRepository.SensorPosition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluates {@code HIGH_TEMPERATURE} and {@code HIGH_MOISTURE} for a batch of
 * newly stored readings. Called by ingest inside its write transaction, so an
 * alert and the reading that caused it commit together or not at all.
 *
 * <h2>What counts as one evaluation</h2>
 *
 * <p>Auto-resolve needs three consecutive clear <em>evaluations</em>, so what
 * an evaluation is decides how fast alerts resolve. The rules:
 *
 * <ol>
 *   <li><strong>Only newly stored readings.</strong> The caller passes only
 *       the rows the insert accepted. A resent batch comes back as duplicates
 *       and evaluates nothing -- otherwise a device on a flaky link, resending
 *       the same clear sample, would resolve a real alert without the grain
 *       ever being re-measured.</li>
 *   <li><strong>One evaluation per sensor per batch</strong>, using that
 *       sensor's newest reading in the batch. A device that buffered an hour
 *       of samples through an outage sends them together; they describe one
 *       moment of catching up, not twelve separate checks.</li>
 *   <li><strong>Not if something newer is already stored.</strong> A batch
 *       can arrive after a later one -- a delayed retry, say. Its readings
 *       describe the past, and judging the present by them would open an
 *       alert for a condition that has since cleared, or count a stale clear
 *       reading towards resolving a condition that has not.</li>
 *   <li><strong>Implausible values are skipped</strong>
 *       ({@link SensorPlausibility}), counting as neither a detection nor a
 *       clear. A missing moisture value is likewise no evaluation of
 *       moisture.</li>
 * </ol>
 *
 * <p>A threshold is breached when the value is strictly <em>above</em> it,
 * which is how the README words both rules.
 *
 * <p>The cost on a healthy bin is three small indexed reads -- thresholds,
 * open alerts, newer readings -- and no writes at all. Writes happen only
 * where a threshold is breached, or where an open alert reads clear.
 */
@Service
public class ThresholdEvaluator {

	private static final List<AlertType> THRESHOLD_TYPES =
			List.of(AlertType.HIGH_TEMPERATURE, AlertType.HIGH_MOISTURE);

	/** One newly stored reading, as ingest hands it over. */
	public record Reading(int cable, int depth, long seq, Instant recordedAt,
			BigDecimal temperatureC, BigDecimal moisturePct) {

		SensorPosition position() {
			return new SensorPosition(cable, depth);
		}
	}

	/*
	 * Newest first by recorded_at, then by seq. Two samples can carry the same
	 * timestamp -- a device clock with one-second resolution -- and seq is the
	 * device's own ordering, so it breaks the tie.
	 */
	private static final Comparator<Reading> CHRONOLOGICAL =
			Comparator.comparing(Reading::recordedAt).thenComparingLong(Reading::seq);

	private final BinRepository bins;
	private final AlertRepository alerts;
	private final ReadingQueryRepository readings;
	private final AlertLifecycle lifecycle;

	public ThresholdEvaluator(BinRepository bins, AlertRepository alerts,
			ReadingQueryRepository readings, AlertLifecycle lifecycle) {
		this.bins = bins;
		this.alerts = alerts;
		this.readings = readings;
		this.lifecycle = lifecycle;
	}

	/**
	 * @param accepted    readings the insert just stored; duplicates and
	 *                    rejected samples must not be included
	 * @param evaluatedAt the server's receive time, recorded as the detection
	 *                    time -- the same clock as everything else the engine
	 *                    compares (ADR 0005)
	 */
	public void evaluate(long binId, Collection<Reading> accepted, Instant evaluatedAt) {
		if (accepted.isEmpty()) {
			return;
		}

		Map<SensorPosition, Reading> candidates = newestPerSensor(accepted);
		dropStale(binId, candidates);
		if (candidates.isEmpty()) {
			return;
		}

		BinThresholds thresholds = this.bins.findById(binId)
				.orElseThrow(() -> new IllegalStateException("Readings stored for unknown bin " + binId))
				.thresholds();
		Map<AlertKey, Long> open = this.alerts.findOpenSensorAlerts(binId, THRESHOLD_TYPES).stream()
				.collect(Collectors.toMap(
						alert -> new AlertKey(alert.type(), new SensorPosition(alert.cableIndex(), alert.depthIndex())),
						AlertRepository.OpenSensorAlert::alertId));

		for (Reading reading : candidates.values()) {
			if (SensorPlausibility.isPlausibleTemperature(reading.temperatureC())) {
				judge(binId, AlertType.HIGH_TEMPERATURE, reading, reading.temperatureC(),
						thresholds.maxTemperatureC(), open, evaluatedAt);
			}
			if (reading.moisturePct() != null) {
				judge(binId, AlertType.HIGH_MOISTURE, reading, reading.moisturePct(),
						thresholds.maxMoisturePct(), open, evaluatedAt);
			}
		}
	}

	private record AlertKey(AlertType type, SensorPosition position) {
	}

	/** One sensor, one alert type: a detection, a clear, or nothing to do. */
	private void judge(long binId, AlertType type, Reading reading, BigDecimal value, BigDecimal threshold,
			Map<AlertKey, Long> open, Instant evaluatedAt) {
		if (value.compareTo(threshold) > 0) {
			this.lifecycle.sensorConditionDetected(binId, type, reading.cable(), reading.depth(),
					value, threshold, evaluatedAt);
			return;
		}
		Long openAlert = open.get(new AlertKey(type, reading.position()));
		if (openAlert != null) {
			this.lifecycle.conditionClear(openAlert, evaluatedAt);
		}
		// Clear, and nothing open: the common case, and no write at all.
	}

	private static Map<SensorPosition, Reading> newestPerSensor(Collection<Reading> accepted) {
		return new HashMap<>(accepted.stream().collect(Collectors.toMap(
				Reading::position,
				Function.identity(),
				(a, b) -> CHRONOLOGICAL.compare(a, b) >= 0 ? a : b)));
	}

	/**
	 * Removes candidates older than a reading already stored for the same
	 * sensor. The query only has to look after the oldest candidate, so it
	 * reads the narrow slice of time the batch itself covers.
	 */
	private void dropStale(long binId, Map<SensorPosition, Reading> candidates) {
		Instant oldestCandidate = candidates.values().stream()
				.map(Reading::recordedAt)
				.min(Comparator.naturalOrder())
				.orElseThrow();

		this.readings.newestPerSensorAfter(binId, oldestCandidate).forEach((position, newestStored) -> {
			Reading candidate = candidates.get(position);
			if (candidate != null && newestStored.isAfter(candidate.recordedAt())) {
				candidates.remove(position);
			}
		});
	}
}
