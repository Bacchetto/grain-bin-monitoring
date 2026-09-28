package com.grainbin.telemetry.alerts;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one place an alert state change is reported, as the README requires:
 * a structured log line and the {@code alerts_transitions_total{type,to_state}}
 * counter.
 *
 * <h2>Reported after commit, never before</h2>
 *
 * <p>Transitions happen inside transactions -- an ingest batch, a scheduled
 * run -- and a transaction can still roll back after the write that caused
 * the transition. Reporting immediately would then count and log an alert
 * that never existed. So when a transaction is active, the report is
 * deferred to {@code afterCommit} and is simply dropped on rollback. The
 * readings package uses the same rule for its partition cache.
 */
@Component
public class AlertTransitions {

	private static final Logger log = LoggerFactory.getLogger(AlertTransitions.class);

	static final String METRIC_NAME = "alerts.transitions";

	private final Map<AlertType, Map<AlertStatus, Counter>> counters = new EnumMap<>(AlertType.class);

	/**
	 * Every combination is registered up front. A counter that does not exist
	 * until its first increment is missing from Prometheus until then, so a
	 * Grafana panel shows "no data" rather than zero, and a rate() across the
	 * first increment is lost.
	 */
	public AlertTransitions(MeterRegistry registry) {
		for (AlertType type : AlertType.values()) {
			Map<AlertStatus, Counter> byState = new EnumMap<>(AlertStatus.class);
			for (AlertStatus toState : AlertStatus.values()) {
				byState.put(toState, Counter.builder(METRIC_NAME)
						.description("Alert state changes, by alert type and the state entered")
						.tag("type", type.name())
						.tag("to_state", toState.name())
						.register(registry));
			}
			this.counters.put(type, byState);
		}
	}

	/**
	 * Reports that an alert entered {@code toState}.
	 *
	 * @param deviceId set for {@code DEVICE_OFFLINE}, otherwise null
	 */
	public void report(long alertId, long binId, AlertType type, AlertStatus toState, Long deviceId) {
		Runnable report = () -> {
			this.counters.get(type).get(toState).increment();
			// Key-value pairs: with structured logging on, each one becomes its
			// own JSON field, so a log query can filter on alertId or type
			// without parsing a message. The message repeats the essentials
			// because the plain-text format of the local profile prints only
			// the message -- found by reading the log of a live run, where
			// every line said just "Alert transition".
			var event = log.atInfo()
					.setMessage("Alert " + alertId + " " + type + " on bin " + binId + " -> " + toState)
					.addKeyValue("alertId", alertId)
					.addKeyValue("binId", binId)
					.addKeyValue("alertType", type.name())
					.addKeyValue("toState", toState.name());
			if (deviceId != null) {
				event = event.addKeyValue("deviceId", deviceId);
			}
			event.log();
		};

		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					report.run();
				}
			});
		}
		else {
			// No surrounding transaction: the write ran in autocommit and has
			// already committed.
			report.run();
		}
	}
}
