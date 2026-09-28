package com.grainbin.telemetry.alerts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.grainbin.telemetry.config.ClockConfig.APPLICATION_ZONE;

/**
 * Writes alert state. Every write is a single statement, and every lifecycle
 * rule the database can enforce, it does -- see {@code V3__alerts.sql} and
 * {@code V4__device_scoped_offline_alert_dedupe.sql}.
 *
 * <p>Nothing here reports a transition. That is {@link AlertLifecycle}'s job,
 * using what these methods return.
 */
@Repository
public class AlertRepository {

	/**
	 * Consecutive clear evaluations before an alert auto-resolves. From the
	 * README: fewer would let an alert flap open and closed around a
	 * threshold.
	 */
	public static final int CLEAR_EVALUATIONS_TO_RESOLVE = 3;

	/**
	 * The upsert's DO UPDATE branch is a repeat detection of a condition that
	 * already has an open or acknowledged alert.
	 *
	 * <ul>
	 *   <li>{@code clear_streak = 0}: the condition is present again, so any
	 *       progress towards auto-resolving starts over.</li>
	 *   <li>{@code status} is not touched: an acknowledged alert stays
	 *       acknowledged. Someone has already seen it.</li>
	 *   <li>{@code GREATEST} on {@code last_detected_at}: two evaluations can
	 *       commit in either order, and the CHECK constraint forbids
	 *       {@code last_detected_at} from going below
	 *       {@code first_detected_at}.</li>
	 *   <li>The trigger and threshold values are replaced with the latest, so
	 *       the alert shows the current reading and the threshold it is
	 *       currently judged against.</li>
	 * </ul>
	 *
	 * <p>{@code (xmax = 0) AS inserted} is the standard PostgreSQL way to ask
	 * which branch an upsert took. {@code xmax} is a system column that is zero
	 * on a freshly inserted row version and non-zero on one written by the
	 * update branch. It tells a newly opened alert -- a state transition --
	 * apart from a repeat detection, which is not one.
	 */
	private static final String ON_CONFLICT_UPDATE = """
			DO UPDATE SET
				last_detected_at = GREATEST(alerts.last_detected_at, EXCLUDED.last_detected_at),
				trigger_value    = EXCLUDED.trigger_value,
				threshold_value  = EXCLUDED.threshold_value,
				clear_streak     = 0
			RETURNING id, (xmax = 0) AS inserted
			""";

	/*
	 * The conflict targets must name the index's columns AND repeat its WHERE
	 * clause. The dedupe indexes are partial, and PostgreSQL only infers a
	 * partial unique index as the arbiter when the conflict target's predicate
	 * implies the index's predicate. Leave the WHERE off and the statement
	 * fails with "there is no unique or exclusion constraint matching the ON
	 * CONFLICT specification".
	 */
	private static final String UPSERT_SENSOR_ALERT = """
			INSERT INTO alerts
				(bin_id, type, cable_index, depth_index, trigger_value, threshold_value,
				 first_detected_at, last_detected_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (bin_id, type, cable_index, depth_index)
				WHERE status <> 'RESOLVED' AND type <> 'DEVICE_OFFLINE'
			""" + ON_CONFLICT_UPDATE;

	/*
	 * trigger_value and threshold_value are deliberately NULL for this type.
	 * The natural values are "seconds silent" and "3x the interval", but the
	 * columns are NUMERIC(6,2), which tops out at 9,999.99 -- about 2.8 hours
	 * of silence. A longer outage would make the next detection fail. How long
	 * a device has been silent is derived from devices.last_seen_at instead,
	 * which is always current.
	 */
	private static final String UPSERT_DEVICE_OFFLINE_ALERT = """
			INSERT INTO alerts
				(bin_id, type, device_id, first_detected_at, last_detected_at)
			VALUES (?, 'DEVICE_OFFLINE', ?, ?, ?)
			ON CONFLICT (bin_id, type, device_id)
				WHERE status <> 'RESOLVED' AND type = 'DEVICE_OFFLINE'
			""" + ON_CONFLICT_UPDATE;

	/*
	 * One statement, so the increment and the resolution cannot be split by a
	 * concurrent evaluation, and so status and resolved_at change together as
	 * the alerts_resolved_has_timestamp CHECK requires.
	 *
	 * Every expression on the right of SET sees the row as it was BEFORE the
	 * update. That is why each CASE tests clear_streak + 1: it is the value the
	 * row is about to have.
	 */
	private static final String RECORD_CLEAR = """
			UPDATE alerts SET
				clear_streak = clear_streak + 1,
				status       = CASE WHEN clear_streak + 1 >= :resolveAt THEN 'RESOLVED' ELSE status END,
				resolved_at  = CASE WHEN clear_streak + 1 >= :resolveAt THEN :at ELSE resolved_at END
			WHERE id = :id
			  AND status <> 'RESOLVED'
			RETURNING id, bin_id, device_id, type, status
			""";

	private final JdbcClient jdbc;

	public AlertRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Which way an upsert went. */
	public record Detection(long alertId, long binId, AlertType type, boolean opened) {
	}

	/** An alert that has just recorded a clear evaluation. */
	/** @param deviceId set for {@code DEVICE_OFFLINE}, otherwise null */
	public record Clear(long alertId, long binId, Long deviceId, AlertType type, boolean resolved) {
	}

	/**
	 * Opens a sensor alert, or refreshes the one already open for this
	 * position.
	 */
	public Detection upsertSensorAlert(long binId, AlertType type, int cableIndex, int depthIndex,
			BigDecimal triggerValue, BigDecimal thresholdValue, Instant detectedAt) {
		if (!type.isSensorAlert()) {
			throw new IllegalArgumentException(type + " is not a sensor alert");
		}
		OffsetDateTime at = utc(detectedAt);
		return this.jdbc.sql(UPSERT_SENSOR_ALERT)
				.param(binId)
				.param(type.name())
				.param(cableIndex)
				.param(depthIndex)
				.param(triggerValue)
				.param(thresholdValue)
				.param(at)
				.param(at)
				.query((rs, rowNum) -> new Detection(
						rs.getLong("id"), binId, type, rs.getBoolean("inserted")))
				.single();
	}

	/** Opens a DEVICE_OFFLINE alert for this device, or refreshes its open one. */
	public Detection upsertDeviceOfflineAlert(long binId, long deviceId, Instant detectedAt) {
		OffsetDateTime at = utc(detectedAt);
		return this.jdbc.sql(UPSERT_DEVICE_OFFLINE_ALERT)
				.param(binId)
				.param(deviceId)
				.param(at)
				.param(at)
				.query((rs, rowNum) -> new Detection(
						rs.getLong("id"), binId, AlertType.DEVICE_OFFLINE, rs.getBoolean("inserted")))
				.single();
	}

	/**
	 * Records one evaluation during which the alert's condition was clear,
	 * resolving it on the {@value #CLEAR_EVALUATIONS_TO_RESOLVE}th in a row.
	 *
	 * @return empty if the alert does not exist or is already resolved
	 */
	public Optional<Clear> recordClear(long alertId, Instant at) {
		return this.jdbc.sql(RECORD_CLEAR)
				.param("resolveAt", CLEAR_EVALUATIONS_TO_RESOLVE)
				.param("at", utc(at))
				.param("id", alertId)
				.query((rs, rowNum) -> new Clear(
						rs.getLong("id"),
						rs.getLong("bin_id"),
						rs.getObject("device_id", Long.class),
						AlertType.valueOf(rs.getString("type")),
						AlertStatus.valueOf(rs.getString("status")) == AlertStatus.RESOLVED))
				.optional();
	}

	/** A non-resolved sensor alert: enough to find it again for a clear. */
	public record OpenSensorAlert(long alertId, AlertType type, int cableIndex, int depthIndex) {
	}

	/**
	 * The bin's open and acknowledged alerts of the given sensor types. Uses
	 * {@code alerts_bin_status_idx}; a bin has a handful of these at most.
	 */
	public List<OpenSensorAlert> findOpenSensorAlerts(long binId, List<AlertType> types) {
		if (types.stream().anyMatch(type -> !type.isSensorAlert())) {
			throw new IllegalArgumentException("Only sensor alert types have a position: " + types);
		}
		return this.jdbc.sql("""
				SELECT id, type, cable_index, depth_index
				FROM alerts
				WHERE bin_id = :binId
				  AND status <> 'RESOLVED'
				  AND type IN (:types)
				""")
				.param("binId", binId)
				.param("types", types.stream().map(AlertType::name).toList())
				.query((rs, rowNum) -> new OpenSensorAlert(
						rs.getLong("id"),
						AlertType.valueOf(rs.getString("type")),
						rs.getInt("cable_index"),
						rs.getInt("depth_index")))
				.list();
	}

	// -----------------------------------------------------------------------
	// the alerts API
	// -----------------------------------------------------------------------

	/*
	 * The device join is only for DEVICE_OFFLINE, to report when the device
	 * was last heard from. Sensor alerts have no device_id, so the LEFT JOIN
	 * finds nothing for them.
	 */
	private static final String SELECT_ALERTS = """
			SELECT a.id, a.bin_id, b.name AS bin_name, a.type, a.status,
			       a.cable_index, a.depth_index, a.device_id, d.last_seen_at AS device_last_seen_at,
			       a.trigger_value, a.threshold_value,
			       a.first_detected_at, a.last_detected_at, a.acknowledged_at, a.resolved_at
			FROM alerts a
			JOIN bins b ON b.id = a.bin_id
			LEFT JOIN devices d ON d.id = a.device_id
			""";

	/**
	 * Alerts in any of {@code statuses}, optionally for one bin, newest
	 * detection first.
	 *
	 * <p>The bin condition is appended only when a bin is given, rather than
	 * written as {@code (:binId IS NULL OR a.bin_id = :binId)}. The PostgreSQL
	 * driver cannot infer a type for a parameter that is only ever compared
	 * with NULL, and a query that is always the same shape also plans better.
	 * The appended text is fixed; the value is still a bound parameter.
	 */
	public List<AlertResponse> find(Collection<AlertStatus> statuses, Long binId, int limit) {
		String sql = SELECT_ALERTS
				+ " WHERE a.status IN (:statuses)"
				+ (binId == null ? "" : " AND a.bin_id = :binId")
				// id as a tiebreaker, so rows detected in the same instant come
				// back in a stable order between requests.
				+ " ORDER BY a.last_detected_at DESC, a.id DESC LIMIT :limit";

		var query = this.jdbc.sql(sql)
				.param("statuses", statuses.stream().map(AlertStatus::name).toList())
				.param("limit", limit);
		if (binId != null) {
			query = query.param("binId", binId);
		}
		return query.query(AlertRepository::toResponse).list();
	}

	public Optional<AlertResponse> findById(long alertId) {
		return this.jdbc.sql(SELECT_ALERTS + " WHERE a.id = ?")
				.param(alertId)
				.query(AlertRepository::toResponse)
				.optional();
	}

	/** An alert that has just moved from OPEN to ACKNOWLEDGED. */
	public record Acknowledgement(long alertId, long binId, Long deviceId, AlertType type) {
	}

	/**
	 * Moves an OPEN alert to ACKNOWLEDGED. Status and timestamp change in one
	 * statement, as the lifecycle CHECK constraints require.
	 *
	 * <p>The {@code status = 'OPEN'} condition makes this a compare-and-set:
	 * of two concurrent acknowledgements, exactly one updates the row, so the
	 * transition is reported once. So does an evaluator resolving the alert at
	 * the same moment -- the acknowledgement then finds nothing to update.
	 *
	 * @return empty if the alert does not exist or is not OPEN
	 */
	public Optional<Acknowledgement> acknowledge(long alertId, Instant at) {
		return this.jdbc.sql("""
				UPDATE alerts SET status = 'ACKNOWLEDGED', acknowledged_at = ?
				WHERE id = ? AND status = 'OPEN'
				RETURNING id, bin_id, device_id, type
				""")
				.param(utc(at))
				.param(alertId)
				.query((rs, rowNum) -> new Acknowledgement(
						rs.getLong("id"),
						rs.getLong("bin_id"),
						rs.getObject("device_id", Long.class),
						AlertType.valueOf(rs.getString("type"))))
				.optional();
	}

	private static AlertResponse toResponse(ResultSet rs, int rowNum) throws SQLException {
		return new AlertResponse(
				rs.getLong("id"),
				rs.getLong("bin_id"),
				rs.getString("bin_name"),
				AlertType.valueOf(rs.getString("type")),
				AlertStatus.valueOf(rs.getString("status")),
				rs.getObject("cable_index", Integer.class),
				rs.getObject("depth_index", Integer.class),
				rs.getObject("device_id", Long.class),
				instantOrNull(rs, "device_last_seen_at"),
				rs.getBigDecimal("trigger_value"),
				rs.getBigDecimal("threshold_value"),
				instantOrNull(rs, "first_detected_at"),
				instantOrNull(rs, "last_detected_at"),
				instantOrNull(rs, "acknowledged_at"),
				instantOrNull(rs, "resolved_at"));
	}

	private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant instant) {
		// The PostgreSQL driver cannot bind an Instant; see the ingest package.
		return instant.atOffset(APPLICATION_ZONE);
	}
}
