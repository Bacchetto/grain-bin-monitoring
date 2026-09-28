package com.grainbin.telemetry.alerts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Runs the scheduled alert evaluators. Kept apart from the evaluators so they
 * can be tested by calling {@code evaluate(now)} with a chosen instant, and so
 * the schedule can be switched off as a whole.
 *
 * <p><strong>Switched off in tests</strong>, by
 * {@code APP_ALERTS_SCHEDULING_ENABLED=false} in the surefire configuration.
 * A background run against the real clock would share the test database
 * with tests that call the evaluators themselves, and could take the job
 * lock at the moment a test needs it. Tests that care about scheduling
 * check this bean's wiring instead.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}: the next run starts a
 * full interval after the previous one finishes, so a slow run can never
 * overlap the next one on the same instance.
 */
@Component
@ConditionalOnBooleanProperty(name = "app.alerts.scheduling-enabled", matchIfMissing = true)
public class AlertSchedule {

	private final DeviceOfflineEvaluator deviceOffline;
	private final RateOfRiseEvaluator rateOfRise;
	private final Clock clock;

	public AlertSchedule(DeviceOfflineEvaluator deviceOffline, RateOfRiseEvaluator rateOfRise, Clock clock) {
		this.deviceOffline = deviceOffline;
		this.rateOfRise = rateOfRise;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.alerts.offline-check-interval}",
			initialDelayString = "${app.alerts.offline-check-interval}")
	void checkDevicesOffline() {
		this.deviceOffline.evaluate(this.clock.instant());
	}

	@Scheduled(fixedDelayString = "${app.alerts.rate-of-rise-check-interval}",
			initialDelayString = "${app.alerts.rate-of-rise-check-interval}")
	void checkRateOfRise() {
		this.rateOfRise.evaluate(this.clock.instant());
	}
}
