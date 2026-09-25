package com.grainbin.telemetry.alerts;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * What the evaluators call when they have decided something. It pairs each
 * write with the transition it causes, if any, so no evaluator can change an
 * alert's state without it being counted and logged.
 *
 * <p>Deciding whether a condition holds is the evaluators' job; this class
 * only records the outcome. It joins the caller's transaction when there is
 * one.
 */
@Service
public class AlertLifecycle {

	private final AlertRepository alerts;
	private final AlertTransitions transitions;

	public AlertLifecycle(AlertRepository alerts, AlertTransitions transitions) {
		this.alerts = alerts;
		this.transitions = transitions;
	}

	/**
	 * A sensor alert's condition holds. Opens an alert, or refreshes the one
	 * already open for this position; only opening is a transition.
	 */
	public AlertRepository.Detection sensorConditionDetected(long binId, AlertType type,
			int cableIndex, int depthIndex, BigDecimal triggerValue, BigDecimal thresholdValue,
			Instant detectedAt) {
		AlertRepository.Detection detection = this.alerts.upsertSensorAlert(
				binId, type, cableIndex, depthIndex, triggerValue, thresholdValue, detectedAt);
		if (detection.opened()) {
			this.transitions.report(detection.alertId(), binId, type, AlertStatus.OPEN, null);
		}
		return detection;
	}

	/** A device is offline. Opens its alert, or refreshes the open one. */
	public AlertRepository.Detection deviceOfflineDetected(long binId, long deviceId, Instant detectedAt) {
		AlertRepository.Detection detection = this.alerts.upsertDeviceOfflineAlert(binId, deviceId, detectedAt);
		if (detection.opened()) {
			this.transitions.report(detection.alertId(), binId, AlertType.DEVICE_OFFLINE, AlertStatus.OPEN, deviceId);
		}
		return detection;
	}

	/**
	 * An open or acknowledged alert's condition was clear in this
	 * evaluation. Resolves it once that has happened
	 * {@value AlertRepository#CLEAR_EVALUATIONS_TO_RESOLVE} times in a row.
	 *
	 * @return empty if the alert is unknown or already resolved
	 */
	public Optional<AlertRepository.Clear> conditionClear(long alertId, Instant at) {
		Optional<AlertRepository.Clear> clear = this.alerts.recordClear(alertId, at);
		clear.filter(AlertRepository.Clear::resolved).ifPresent(resolved -> this.transitions.report(
				resolved.alertId(), resolved.binId(), resolved.type(), AlertStatus.RESOLVED,
				resolved.deviceId()));
		return clear;
	}
}
