package com.grainbin.telemetry.alerts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.grainbin.telemetry.config.ClockConfig.APPLICATION_ZONE;

/**
 * {@code DEVICE_OFFLINE}: a device with no successfully stored reading for
 * more than three times its expected reporting interval.
 *
 * <h2>The rule</h2>
 *
 * <pre>now - COALESCE(last_seen_at, created_at) &gt; 3 x expected_interval_seconds</pre>
 *
 * <ul>
 *   <li>{@code last_seen_at} advances only when a reading is actually stored
 *       (README), so a device that connects but sends only duplicates or
 *       rejected data is still offline.</li>
 *   <li>It is set from the server's clock, as is {@code now}, so a device's
 *       clock skew can neither cause nor hide this alert (ADR 0005).</li>
 *   <li><strong>A device that has never reported</strong> is measured from
 *       its registration. The alternative -- ignoring it until its first
 *       reading -- would leave a controller that was registered but never
 *       installed, or never powered on, silently unmonitored forever.</li>
 * </ul>
 *
 * <p>Each run is one evaluation per device: offline is a detection, back
 * within the window is a clear, and three clear runs resolve the alert. Every
 * device is judged separately, so one controller recovering never resolves
 * another's outage on the same bin (ADR 0002).
 */
@Service
public class DeviceOfflineEvaluator {

	/*
	 * Every device, whether it is offline now, and its non-resolved
	 * DEVICE_OFFLINE alert if it has one -- at most one, by the V4 unique
	 * index. The devices table is small, so reading all of it each minute is
	 * cheap; nothing here touches readings.
	 */
	private static final String DEVICE_STATES = """
			SELECT d.id     AS device_id,
			       d.bin_id,
			       COALESCE(d.last_seen_at, d.created_at)
			           < :now - make_interval(secs => 3 * d.expected_interval_seconds) AS offline,
			       a.id     AS open_alert_id
			FROM devices d
			LEFT JOIN alerts a
			       ON a.device_id = d.id
			      AND a.type = 'DEVICE_OFFLINE'
			      AND a.status <> 'RESOLVED'
			""";

	private record DeviceState(long deviceId, long binId, boolean offline, Long openAlertId) {
	}

	private final JdbcClient jdbc;
	private final ScheduledJobLock lock;
	private final AlertLifecycle lifecycle;

	public DeviceOfflineEvaluator(JdbcClient jdbc, ScheduledJobLock lock, AlertLifecycle lifecycle) {
		this.jdbc = jdbc;
		this.lock = lock;
		this.lifecycle = lifecycle;
	}

	/**
	 * Judges every device against {@code now}. Takes the time as a parameter
	 * rather than reading a clock, so tests can drive it directly.
	 *
	 * @return empty if another instance is running this job right now
	 */
	public Optional<EvaluationRun> evaluate(Instant now) {
		return this.lock.runExclusively(ScheduledJobLock.Job.DEVICE_OFFLINE, () -> evaluateLocked(now));
	}

	private EvaluationRun evaluateLocked(Instant now) {
		List<DeviceState> devices = this.jdbc.sql(DEVICE_STATES)
				.param("now", OffsetDateTime.ofInstant(now, APPLICATION_ZONE))
				.query((rs, rowNum) -> new DeviceState(
						rs.getLong("device_id"),
						rs.getLong("bin_id"),
						rs.getBoolean("offline"),
						rs.getObject("open_alert_id", Long.class)))
				.list();

		int detected = 0;
		int clears = 0;
		for (DeviceState device : devices) {
			if (device.offline()) {
				this.lifecycle.deviceOfflineDetected(device.binId(), device.deviceId(), now);
				detected++;
			}
			else if (device.openAlertId() != null) {
				this.lifecycle.conditionClear(device.openAlertId(), now);
				clears++;
			}
		}
		return new EvaluationRun(devices.size(), detected, clears);
	}
}
