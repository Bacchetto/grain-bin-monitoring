package com.grainbin.telemetry.ingest;

import com.grainbin.telemetry.config.ClockConfig;
import com.grainbin.telemetry.devices.AuthenticatedDevice;
import com.grainbin.telemetry.readings.PartitionMaintenanceService;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores a batch of readings idempotently.
 *
 * <p>The steps, in order:
 *
 * <ol>
 *   <li>Take one "now" from the injected clock. Every time-dependent decision
 *       in the request -- the future cutoff, {@code received_at},
 *       {@code last_seen_at} -- uses this single instant, so they cannot
 *       disagree with each other.</li>
 *   <li>Set aside samples recorded more than five minutes in the future, or
 *       longer ago than the configured maximum age (30 days by default). They
 *       are counted as rejected; the rest of the batch continues.</li>
 *   <li>Make sure a partition exists for every month in the batch --
 *       <em>outside</em> the write transaction (see below).</li>
 *   <li>In one transaction: insert every reading with
 *       {@code ON CONFLICT DO NOTHING}, count the outcomes from the per-row
 *       update counts, and advance {@code last_seen_at} if anything was
 *       stored.</li>
 * </ol>
 *
 * <h2>Why the partition step is outside the transaction</h2>
 *
 * <p>Partition creation is DDL, and in PostgreSQL DDL is transactional. Done
 * inside the write transaction, a batch that failed for any unrelated reason
 * would roll the new partition back with it -- while the in-memory cache in
 * {@link PartitionMaintenanceService} had already recorded that month as
 * existing. Every later insert into that month would then fail with "no
 * partition found". Doing it first, in its own autocommit, means a partition
 * that was created stays created. The service also refuses to cache a month
 * until the creating transaction has committed, as a second line of defence.
 *
 * <p>That is also why this class uses a {@link TransactionTemplate} rather than
 * {@code @Transactional}: the annotation would wrap the whole method,
 * partition step included.
 */
@Service
public class IngestService {

	/** From the README. Exceeding it is a 413. */
	public static final int MAX_SAMPLES_PER_BATCH = 500;

	/**
	 * From the README. Anything recorded later than this past the server's
	 * clock is counted as rejected. Some skew is tolerated because device
	 * clocks drift and are corrected only periodically.
	 */
	static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

	private static final String INSERT_READING = """
			INSERT INTO readings
				(device_id, bin_id, seq, cable_index, depth_index,
				 recorded_at, received_at, temperature_c, moisture_pct)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT DO NOTHING
			""";

	/*
	 * GREATEST, not a plain assignment. Two batches from the same device can
	 * commit in either order; without it the later commit could carry the
	 * earlier timestamp and move last_seen_at backwards. PostgreSQL's GREATEST
	 * ignores NULL, so this also handles a device's first ever reading.
	 */
	private static final String TOUCH_LAST_SEEN = """
			UPDATE devices SET last_seen_at = GREATEST(last_seen_at, ?) WHERE id = ?
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final PartitionMaintenanceService partitions;
	private final Clock clock;
	private final Duration maxSampleAge;

	public IngestService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			PartitionMaintenanceService partitions, Clock clock, IngestProperties properties) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.clock = clock;
		this.maxSampleAge = properties.maxSampleAge();
	}

	/** One reading, flattened out of its sample, ready to bind. */
	private record Row(long seq, Instant recordedAt, IngestRequest.Sensor sensor) {
	}

	/**
	 * @param device  from the authentication filter -- never from the request
	 * @param samples already validated, and at most {@link #MAX_SAMPLES_PER_BATCH}
	 */
	public IngestResponse ingest(AuthenticatedDevice device, List<IngestRequest.Sample> samples) {
		Instant receivedAt = this.clock.instant();
		Instant latestAcceptable = receivedAt.plus(MAX_FUTURE_SKEW);
		// The floor. Beyond stopping junk data, it bounds which partitions a
		// device can cause to be created: without it, any authenticated device
		// with a reset clock could trigger DDL for an arbitrary past month.
		Instant earliestAcceptable = receivedAt.minus(this.maxSampleAge);

		List<Row> rows = new ArrayList<>();
		int rejected = 0;
		for (IngestRequest.Sample sample : samples) {
			if (sample.recordedAt().isAfter(latestAcceptable) || sample.recordedAt().isBefore(earliestAcceptable)) {
				rejected += sample.sensors().size();
				continue;
			}
			for (IngestRequest.Sensor sensor : sample.sensors()) {
				rows.add(new Row(sample.seq(), sample.recordedAt(), sensor));
			}
		}

		if (rows.isEmpty()) {
			// Everything was rejected. Nothing is written and last_seen_at does
			// not move: a device sending only out-of-window data is, from a data
			// standpoint, not reporting.
			return new IngestResponse(0, 0, rejected);
		}

		// Outside the transaction, deliberately. See the class javadoc.
		this.partitions.ensureForRecordedAt(rows.stream().map(Row::recordedAt).toList());

		int rejectedCount = rejected;
		return this.transactions.execute(status -> {
			int[] counts = insert(device, rows, receivedAt);

			int accepted = 0;
			int duplicates = 0;
			for (int count : counts) {
				switch (count) {
					case 1 -> accepted++;
					case 0 -> duplicates++;
					default -> throw new IllegalStateException(unexpectedUpdateCount(count));
				}
			}

			// README: "Update devices.last_seen_at only after a successful
			// insert." A batch of nothing but duplicates is not a successful
			// insert of anything, so it does not count as the device being seen.
			if (accepted > 0) {
				this.jdbc.update(TOUCH_LAST_SEEN, utc(receivedAt), device.deviceId());
			}

			// Milestone 2: synchronous threshold-alert evaluation belongs here,
			// inside the transaction, so an alert and the reading that triggered
			// it commit together or not at all. The upsert needs a different
			// ON CONFLICT target per alert type; see the alerts package and
			// docs/decisions/0002-device-scoped-offline-alert-dedupe.md.

			return new IngestResponse(accepted, duplicates, rejectedCount);
		});
	}

	/**
	 * Inserts every row as one JDBC batch and returns the per-row update counts.
	 *
	 * <p>This is the whole idempotency mechanism. {@code ON CONFLICT DO NOTHING}
	 * reports 1 for a row it stored and 0 for one it skipped, so the counts
	 * <em>are</em> the accepted/duplicate split -- no SELECT beforehand, and no
	 * race between checking and inserting.
	 */
	private int[] insert(AuthenticatedDevice device, List<Row> rows, Instant receivedAt) {
		OffsetDateTime receivedAtUtc = utc(receivedAt);

		return this.jdbc.batchUpdate(INSERT_READING, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				Row row = rows.get(i);
				ps.setLong(1, device.deviceId());
				ps.setLong(2, device.binId());
				ps.setLong(3, row.seq());
				ps.setInt(4, row.sensor().cable());
				ps.setInt(5, row.sensor().depth());
				// TIMESTAMPTZ must be bound as OffsetDateTime; the PostgreSQL
				// driver cannot infer a SQL type for Instant. See package-info.
				ps.setObject(6, utc(row.recordedAt()));
				ps.setObject(7, receivedAtUtc);
				ps.setBigDecimal(8, row.sensor().temperatureC());
				ps.setObject(9, row.sensor().moisturePct(), Types.NUMERIC);
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ClockConfig.APPLICATION_ZONE);
	}

	/**
	 * Fails loudly rather than miscounting.
	 *
	 * <p>The likely cause is the PostgreSQL driver option
	 * {@code reWriteBatchedInserts=true}. It is a well-known throughput tweak --
	 * it rewrites a batch into multi-row INSERTs -- and it makes the driver
	 * report {@link Statement#SUCCESS_NO_INFO} (-2) instead of a real count.
	 * With it on, accepted and duplicate could not be told apart, and silently
	 * reporting everything as one or the other would be worse than failing.
	 */
	private static String unexpectedUpdateCount(int count) {
		String cause = (count == Statement.SUCCESS_NO_INFO)
				? " (SUCCESS_NO_INFO -- is reWriteBatchedInserts enabled on the JDBC URL?)"
				: "";
		return "Unexpected update count " + count + " from an ON CONFLICT DO NOTHING insert" + cause
				+ ". Accepted and duplicate readings cannot be counted.";
	}
}
