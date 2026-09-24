package com.grainbin.telemetry.bins;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes bins.
 *
 * <p>Hand-written SQL via {@link JdbcClient}, per the JDBC-over-JPA decision in
 * {@code docs/decisions/0008-spring-jdbc-over-jpa.md}.
 * Every query here is visible in full, which is the point.
 */
@Repository
public class BinRepository {

	private final JdbcClient jdbc;

	public BinRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// -----------------------------------------------------------------------
	// writes
	// -----------------------------------------------------------------------

	/**
	 * @return the new bin's id
	 * @throws org.springframework.dao.DuplicateKeyException if the site
	 *         already has a bin with this name
	 */
	public long create(CreateBinRequest request) {
		return this.jdbc.sql("""
				INSERT INTO bins (name, site, grain_type, capacity_bushels)
				VALUES (?, ?, ?, ?)
				RETURNING id
				""")
				.param(request.name().strip())
				.param(request.site().strip())
				.param(request.grainType().strip())
				.param(request.capacityBushels())
				.query(Long.class)
				.single();
	}

	/**
	 * Applies a partial threshold update.
	 *
	 * <p>{@code COALESCE(?, column)} is what makes this a PATCH in one
	 * statement: a null parameter leaves the existing value in place. The
	 * alternative -- read the row, merge in Java, write it back -- would need a
	 * transaction and a lock to be safe against a concurrent update, to
	 * achieve the same thing.
	 *
	 * @return the updated bin, or empty if no bin has that id
	 */
	public Optional<BinDetailResponse> updateThresholds(long id, UpdateThresholdsRequest request) {
		return this.jdbc.sql("""
				UPDATE bins SET
					max_temperature_c = COALESCE(?, max_temperature_c),
					max_moisture_pct  = COALESCE(?, max_moisture_pct),
					rise_threshold_c  = COALESCE(?, rise_threshold_c),
					rise_window_hours = COALESCE(?, rise_window_hours)
				WHERE id = ?
				RETURNING id, name, site, grain_type, capacity_bushels,
				          max_temperature_c, max_moisture_pct, rise_threshold_c,
				          rise_window_hours, created_at
				""")
				.param(request.maxTemperatureC())
				.param(request.maxMoisturePct())
				.param(request.riseThresholdC())
				.param(request.riseWindowHours())
				.param(id)
				.query(BinRepository::toDetail)
				.optional();
	}

	// -----------------------------------------------------------------------
	// reads
	// -----------------------------------------------------------------------

	public Optional<BinDetailResponse> findById(long id) {
		return this.jdbc.sql("""
				SELECT id, name, site, grain_type, capacity_bushels,
				       max_temperature_c, max_moisture_pct, rise_threshold_c,
				       rise_window_hours, created_at
				FROM bins
				WHERE id = ?
				""")
				.param(id)
				.query(BinRepository::toDetail)
				.optional();
	}

	public boolean exists(long id) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM bins WHERE id = ?)")
				.param(id)
				.query(Boolean.class)
				.single();
	}

	/**
	 * Every bin with its current status, in one query.
	 *
	 * <p>Two things are worth explaining here.
	 *
	 * <p><strong>Last reading time comes from {@code devices.last_seen_at},
	 * not from {@code readings}.</strong> The obvious query is
	 * {@code MAX(recorded_at) FROM readings WHERE bin_id = ?}, but
	 * {@code readings} is partitioned by month with no upper bound on how far
	 * back it goes, and an unbounded {@code MAX} has to look in every
	 * partition. {@code last_seen_at} is maintained by the ingest path for
	 * exactly this purpose and lives on a small table. It carries the right
	 * meaning too: it is advanced only when a reading is actually stored, so a
	 * device that reconnects and sends nothing but duplicates does not refresh
	 * it.
	 *
	 * <p><strong>"Worst" needs an ordering the specification does not
	 * give.</strong> The ranking below runs from spoilage already happening,
	 * through spoilage starting, to conditions that invite it, to having no
	 * data at all. A non-resolved alert counts whether or not it has been
	 * acknowledged: acknowledging a hot spot does not cool it.
	 */
	public List<BinSummaryResponse> findAllWithStatus() {
		return this.jdbc.sql("""
				SELECT b.id,
				       b.name,
				       b.site,
				       b.grain_type,
				       (SELECT MAX(d.last_seen_at)
				          FROM devices d
				         WHERE d.bin_id = b.id)                AS last_reading_at,
				       a.worst_open_alert,
				       COALESCE(a.open_alert_count, 0)          AS open_alert_count
				FROM bins b
				LEFT JOIN LATERAL (
				    SELECT count(*) AS open_alert_count,
				           (array_agg(type ORDER BY CASE type
				                WHEN 'HIGH_TEMPERATURE' THEN 1
				                WHEN 'RATE_OF_RISE'     THEN 2
				                WHEN 'HIGH_MOISTURE'    THEN 3
				                WHEN 'DEVICE_OFFLINE'   THEN 4
				            END))[1]                            AS worst_open_alert
				      FROM alerts
				     WHERE alerts.bin_id = b.id
				       AND alerts.status <> 'RESOLVED'
				) a ON TRUE
				ORDER BY b.site, b.name
				""")
				.query((rs, rowNum) -> new BinSummaryResponse(
						rs.getLong("id"),
						rs.getString("name"),
						rs.getString("site"),
						rs.getString("grain_type"),
						instantOrNull(rs, "last_reading_at"),
						rs.getString("worst_open_alert"),
						rs.getInt("open_alert_count")))
				.list();
	}

	// -----------------------------------------------------------------------
	// mapping
	// -----------------------------------------------------------------------

	private static BinDetailResponse toDetail(ResultSet rs, int rowNum) throws SQLException {
		return new BinDetailResponse(
				rs.getLong("id"),
				rs.getString("name"),
				rs.getString("site"),
				rs.getString("grain_type"),
				// getInt returns 0 for SQL NULL, which is a real capacity, so
				// the nullable column is read as an object.
				rs.getObject("capacity_bushels", Integer.class),
				new BinThresholds(
						rs.getBigDecimal("max_temperature_c"),
						rs.getBigDecimal("max_moisture_pct"),
						rs.getBigDecimal("rise_threshold_c"),
						rs.getInt("rise_window_hours")),
				rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
	}

	private static java.time.Instant instantOrNull(ResultSet rs, String column) throws SQLException {
		java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}
}
