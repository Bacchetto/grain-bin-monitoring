package com.grainbin.telemetry.readings;

import com.grainbin.telemetry.config.ClockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the monthly partitions of {@code readings} in existence.
 *
 * <p>The rule this exists to uphold is absolute: <strong>an insert must never
 * fail because a partition is missing.</strong> There is no default partition
 * to fall back on (see {@code V2__readings_partitioned.sql} for why), so the
 * partition covering a row has to exist before the row is written.
 *
 * <p>That is guaranteed two ways, deliberately overlapping:
 *
 * <ol>
 *   <li><strong>Ahead of time.</strong> A scheduled job keeps the next few
 *       months available, and runs once at startup so a long-idle deployment
 *       does not have to wait for the first firing.</li>
 *   <li><strong>On demand.</strong> The ingest path calls
 *       {@link #ensureForRecordedAt} before writing a batch. This covers what
 *       scheduling cannot: a device with a badly skewed clock, or one that
 *       reconnects after a long outage and back-fills readings older than the
 *       oldest partition.</li>
 * </ol>
 *
 * <p>The scheduled job alone would be a guess about how far ahead devices
 * might report; the on-demand call alone would leave the first write of each
 * month paying for DDL. Together neither case is a problem.
 */
@Service
public class PartitionMaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceService.class);

	/**
	 * How many months beyond the current one the scheduled job keeps
	 * available. Three is far more than devices need, and the cost of an
	 * unused empty partition is negligible.
	 */
	static final int MONTHS_AHEAD = 3;

	private final JdbcClient jdbc;
	private final Clock clock;

	/**
	 * Months whose partition is known to exist.
	 *
	 * <p>This is purely an optimisation: it keeps the common ingest case --
	 * a batch recorded in the current month -- from making a database round
	 * trip on every request. A miss is always safe, because
	 * {@code create_readings_partition} is idempotent.
	 *
	 * <p>The cache assumes partitions are never dropped while the application
	 * is running. That holds today because nothing implements retention. If
	 * partition dropping is ever added, it must invalidate this cache, or an
	 * instance will believe a partition exists after it has been removed and
	 * inserts into that month will start failing.
	 */
	private final Set<YearMonth> knownMonths = ConcurrentHashMap.newKeySet();

	public PartitionMaintenanceService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// -----------------------------------------------------------------------
	// on demand, from the ingest path
	// -----------------------------------------------------------------------

	/**
	 * Ensures a partition exists for every month represented in the given
	 * instants.
	 *
	 * <p>Callers pass a whole batch rather than calling per row: a batch of
	 * 12,000 readings usually spans one or two months, so this collapses to
	 * one or two cache lookups and, almost always, no database work at all.
	 *
	 * @param recordedAt the {@code recorded_at} values about to be written
	 */
	public void ensureForRecordedAt(Collection<Instant> recordedAt) {
		Set<YearMonth> months = new LinkedHashSet<>();
		for (Instant instant : recordedAt) {
			months.add(monthOf(instant));
		}
		months.forEach(this::ensure);
	}

	/** Convenience for a single instant. See {@link #ensureForRecordedAt}. */
	public void ensureFor(Instant recordedAt) {
		ensure(monthOf(recordedAt));
	}

	// -----------------------------------------------------------------------
	// ahead of time, scheduled
	// -----------------------------------------------------------------------

	/**
	 * Creates the current month's partition and the next {@link #MONTHS_AHEAD}.
	 *
	 * <p>Runs on every instance rather than being leader-elected. The
	 * underlying function tolerates the race, so the only cost of duplicate
	 * work is a few wasted round trips once a day.
	 */
	@Scheduled(cron = "${app.partitions.maintenance-cron:0 15 2 * * *}", zone = "UTC")
	public void maintainUpcomingPartitions() {
		try {
			int created = createFrom(YearMonth.now(clock.withZone(ClockConfig.APPLICATION_ZONE)), MONTHS_AHEAD);
			if (created > 0) {
				log.info("Partition maintenance created {} readings partition(s)", created);
			}
		}
		catch (RuntimeException ex) {
			// Swallowing would hide a database problem until an insert fails
			// for a missing partition, so this is logged loudly. The next
			// firing retries, and the ingest path's on-demand call is the
			// backstop in the meantime.
			log.error("Partition maintenance failed; readings partitions may fall behind", ex);
		}
	}

	/**
	 * Runs the same maintenance once at startup, so an instance deployed
	 * months after the last migration does not depend on the ingest path's
	 * on-demand call, nor wait until the next scheduled firing.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void maintainOnStartup() {
		maintainUpcomingPartitions();
	}

	// -----------------------------------------------------------------------
	// internals
	// -----------------------------------------------------------------------

	/**
	 * Ensures partitions exist for {@code start} and the following
	 * {@code monthsAhead} months.
	 *
	 * <p>Package-private and taking an explicit starting month so that tests
	 * can exercise it at a known point in time.
	 *
	 * @return how many months were not already known to this instance
	 */
	int createFrom(YearMonth start, int monthsAhead) {
		int created = 0;
		for (int i = 0; i <= monthsAhead; i++) {
			if (ensure(start.plusMonths(i))) {
				created++;
			}
		}
		return created;
	}

	/**
	 * @return {@code true} if this instance had to go to the database,
	 *         {@code false} if the month was already known
	 */
	private boolean ensure(YearMonth month) {
		if (knownMonths.contains(month)) {
			return false;
		}
		// Deliberately not holding a lock across this call. Two threads racing
		// on the same month both call an idempotent function; the only cost is
		// a duplicated round trip.
		String partition = jdbc.sql("SELECT create_readings_partition(?)")
				.param(month.atDay(1))
				.query(String.class)
				.single();
		rememberOnceCommitted(month);
		log.info("Ensured readings partition {} for {}", partition, month);
		return true;
	}

	/**
	 * Records a month as known -- but only once the DDL that created its
	 * partition has actually committed.
	 *
	 * <p>PostgreSQL DDL is transactional. If this is called inside a caller's
	 * transaction and that transaction rolls back, the partition is rolled back
	 * with it. Caching the month immediately would leave this instance believing
	 * a partition exists that does not, and every later insert into that month
	 * would fail with "no partition of relation found" -- the exact failure this
	 * class exists to prevent, caused by the cache that was only meant to make it
	 * faster.
	 *
	 * <p>Deferring to {@code afterCommit} means a rollback costs, at worst, one
	 * more call to an idempotent function later. The cache can therefore be
	 * pessimistic but never optimistic, which is the only safe direction for it
	 * to be wrong in.
	 *
	 * <p>The ingest path avoids this situation entirely by ensuring partitions
	 * before it opens its transaction. This is the second line of defence, for
	 * any future caller that does not.
	 */
	private void rememberOnceCommitted(YearMonth month) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					knownMonths.add(month);
				}
			});
		}
		else {
			// No surrounding transaction: the call above ran in autocommit and
			// has already committed.
			knownMonths.add(month);
		}
	}

	/**
	 * The partition key is UTC, so the month a reading belongs to must be
	 * derived in UTC. Using the system default zone here would put readings
	 * near a month boundary in the wrong partition.
	 */
	private static YearMonth monthOf(Instant instant) {
		return YearMonth.from(instant.atZone(ClockConfig.APPLICATION_ZONE));
	}

	/** Visible for testing. */
	Set<YearMonth> knownMonths() {
		return Set.copyOf(knownMonths);
	}
}
