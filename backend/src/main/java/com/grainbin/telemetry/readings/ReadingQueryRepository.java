package com.grainbin.telemetry.readings;

import com.grainbin.telemetry.config.ClockConfig;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The two dashboard read queries.
 *
 * <p>Both are bounded on {@code recorded_at}, which is the partition key. That
 * bound is what lets PostgreSQL skip every monthly partition outside the range
 * instead of opening each one -- without it, these queries would get slower
 * every month the system ran. The indexes each one relies on, and the plans
 * that prove they are used, are recorded in ADR 0007.
 */
@Repository
public class ReadingQueryRepository {

	/*
	 * DISTINCT ON keeps the first row of each (cable_index, depth_index) group,
	 * and the ORDER BY makes that first row the newest.
	 *
	 * The plan is a bitmap scan of readings_bin_recorded_idx for the bin's rows
	 * in the lookback window, then a sort. An index declared in the ORDER BY's
	 * own order was tried and dropped in V5: DISTINCT ON reads every row in the
	 * window whichever index supplies them, so the planner never used it, and it
	 * cost ~70% more insert time. The cost here therefore grows with sensors x
	 * readings per week -- about 29 ms for 24 sensors at five-minute reporting.
	 * See ADR 0007, and enhancement E13 for what to do if that ever gets slow.
	 */
	private static final String LATEST = """
			SELECT DISTINCT ON (cable_index, depth_index)
			       cable_index, depth_index, recorded_at, temperature_c, moisture_pct
			FROM readings
			WHERE bin_id = ? AND recorded_at >= ?
			ORDER BY cable_index, depth_index, recorded_at DESC
			""";

	/*
	 * The %s is the date_trunc unit, and it comes only from the Bucket enum; see
	 * Bucket for why that is safe to inline.
	 *
	 * date_trunc takes a third argument, the time zone, deliberately. The
	 * two-argument form truncates a timestamptz in the SESSION's time zone, so on
	 * a server configured for America/Edmonton every "day" would start at 06:00
	 * UTC -- the same class of bug the partition bounds in V2 guard against.
	 *
	 * The range is half-open, [from, to), so consecutive requests for adjacent
	 * ranges never count a reading twice.
	 */
	private static final String HISTORY = """
			SELECT date_trunc('%s', recorded_at, 'UTC') AS bucket_start,
			       cable_index,
			       depth_index,
			       count(*)                     AS readings,
			       round(avg(temperature_c), 2) AS temperature_avg,
			       min(temperature_c)           AS temperature_min,
			       max(temperature_c)           AS temperature_max,
			       count(moisture_pct)          AS moisture_readings,
			       round(avg(moisture_pct), 2)  AS moisture_avg,
			       min(moisture_pct)            AS moisture_min,
			       max(moisture_pct)            AS moisture_max
			FROM readings
			WHERE bin_id = ? AND recorded_at >= ? AND recorded_at < ?
			GROUP BY cable_index, depth_index, bucket_start
			ORDER BY cable_index, depth_index, bucket_start
			""";

	private final JdbcClient jdbc;

	public ReadingQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The newest reading per sensor position recorded at or after {@code since}. */
	public List<LatestReadingsResponse.SensorValue> latest(long binId, Instant since) {
		return this.jdbc.sql(LATEST)
				.param(binId)
				.param(utc(since))
				.query((rs, rowNum) -> new LatestReadingsResponse.SensorValue(
						rs.getInt("cable_index"),
						rs.getInt("depth_index"),
						rs.getBigDecimal("temperature_c"),
						rs.getBigDecimal("moisture_pct"),
						instant(rs, "recorded_at")))
				.list();
	}

	/** Bucketed aggregates for {@code [from, to)}, grouped into one series per sensor. */
	public List<ReadingHistoryResponse.Series> history(long binId, Instant from, Instant to, Bucket bucket) {
		List<Row> rows = this.jdbc.sql(HISTORY.formatted(bucket.sqlUnit()))
				.param(binId)
				.param(utc(from))
				.param(utc(to))
				.query(ReadingQueryRepository::toRow)
				.list();
		return groupBySensor(rows);
	}

	// -----------------------------------------------------------------------
	// mapping
	// -----------------------------------------------------------------------

	private record Row(int cable, int depth, ReadingHistoryResponse.Point point) {
	}

	private static Row toRow(ResultSet rs, int rowNum) throws SQLException {
		ReadingHistoryResponse.Stats temperature = new ReadingHistoryResponse.Stats(
				rs.getBigDecimal("temperature_avg"),
				rs.getBigDecimal("temperature_min"),
				rs.getBigDecimal("temperature_max"));

		// Moisture is optional per reading. A bucket where no reading carried one
		// reports null, rather than three nulls dressed up as statistics.
		ReadingHistoryResponse.Stats moisture = (rs.getInt("moisture_readings") == 0) ? null
				: new ReadingHistoryResponse.Stats(
						rs.getBigDecimal("moisture_avg"),
						rs.getBigDecimal("moisture_min"),
						rs.getBigDecimal("moisture_max"));

		return new Row(
				rs.getInt("cable_index"),
				rs.getInt("depth_index"),
				new ReadingHistoryResponse.Point(
						instant(rs, "bucket_start"), rs.getInt("readings"), temperature, moisture));
	}

	/** Rows arrive ordered by (cable, depth, bucket), so each series is one contiguous run. */
	private static List<ReadingHistoryResponse.Series> groupBySensor(List<Row> rows) {
		List<ReadingHistoryResponse.Series> series = new ArrayList<>();

		for (Row row : rows) {
			boolean newSensor = series.isEmpty()
					|| series.getLast().cable() != row.cable()
					|| series.getLast().depth() != row.depth();
			if (newSensor) {
				series.add(new ReadingHistoryResponse.Series(row.cable(), row.depth(), new ArrayList<>()));
			}
			series.getLast().points().add(row.point());
		}
		return series;
	}

	/** A sensor position within a bin. */
	public record SensorPosition(int cable, int depth) {
	}

	/**
	 * The newest {@code recorded_at} per sensor, among readings recorded
	 * strictly after {@code after}.
	 *
	 * <p>The alert engine uses this to recognise a batch that arrived late --
	 * one whose readings are older than something already stored for the same
	 * sensor. In the common case the batch holds the newest data and this
	 * returns nothing. It reads only a narrow slice of the
	 * {@code (bin_id, recorded_at)} index, from {@code after} to the present,
	 * and prunes every older partition.
	 */
	public Map<SensorPosition, Instant> newestPerSensorAfter(long binId, Instant after) {
		return this.jdbc.sql("""
				SELECT cable_index, depth_index, max(recorded_at) AS newest
				FROM readings
				WHERE bin_id = ?
				  AND recorded_at > ?
				GROUP BY cable_index, depth_index
				""")
				.param(binId)
				.param(utc(after))
				.query((rs, rowNum) -> Map.entry(
						new SensorPosition(rs.getInt("cable_index"), rs.getInt("depth_index")),
						instant(rs, "newest")))
				.list()
				.stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ClockConfig.APPLICATION_ZONE);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}
}
