package com.grainbin.telemetry.alerts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs a scheduled alert job on at most one instance at a time.
 *
 * <h2>Why the alert jobs need this when partition maintenance does not</h2>
 *
 * <p>With more than one API instance -- two ECS tasks, in Milestone 3 --
 * every instance runs every {@code @Scheduled} job. Partition creation
 * converges on the same state however many instances run it. The alert jobs
 * do not: the unique indexes stop two instances opening duplicate alerts, but
 * nothing stops both of them recording a clear. Each run would increment
 * {@code clear_streak}, and an alert meant to need three clear evaluations
 * would resolve after two runs' worth of time, or fewer.
 *
 * <h2>How</h2>
 *
 * <p>A PostgreSQL <em>advisory lock</em>: a lock on an arbitrary number
 * rather than a row, which the database holds on the application's behalf.
 * {@code pg_try_advisory_xact_lock} takes it without waiting and returns
 * false if another session holds it; the {@code _xact_} form releases it
 * automatically when the transaction ends, so a crashed instance cannot leave
 * it held. The instance that gets the lock runs the job; any other skips that
 * run, and the next run is at most one interval away.
 *
 * <p>This needs no new dependency and no extra table. The usual alternative,
 * ShedLock, stores lock rows with an expiry time -- worth it for jobs that
 * must survive restarts or span transactions, which these do not.
 */
@Component
public class ScheduledJobLock {

	/**
	 * One lock number per job. The values are arbitrary but must not collide
	 * with any other use of advisory locks in this database; there is none
	 * today. Different jobs can run concurrently -- they write different alert
	 * types.
	 */
	public enum Job {
		DEVICE_OFFLINE(0x4742_0001L),
		RATE_OF_RISE(0x4742_0002L);

		private final long lockKey;

		Job(long lockKey) {
			this.lockKey = lockKey;
		}

		long lockKey() {
			return lockKey;
		}
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;

	public ScheduledJobLock(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/**
	 * Runs {@code work} in one transaction while holding the job's lock.
	 *
	 * @return the work's result, or empty if another session holds the lock
	 *         and this run was skipped
	 */
	public <T> Optional<T> runExclusively(Job job, Supplier<T> work) {
		return this.transactions.execute(status -> {
			boolean acquired = this.jdbc.sql("SELECT pg_try_advisory_xact_lock(?)")
					.param(job.lockKey())
					.query(Boolean.class)
					.single();
			return acquired ? Optional.of(work.get()) : Optional.<T>empty();
		});
	}
}
