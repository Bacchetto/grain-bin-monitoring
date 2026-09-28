# 0015 - One instance at a time for scheduled alert jobs, by advisory lock

**Status:** Accepted, 2026-09-25 (recorded 2026-09-28)
**Applies to:** `alerts.ScheduledJobLock`, `alerts.AlertSchedule`, `config.SchedulingConfig`

## Context

With more than one API instance -- two ECS tasks, in Milestone 3 -- every
instance runs every `@Scheduled` job. Milestone 1's only job, partition
maintenance, converges: creating a partition that already exists changes
nothing, so any number of instances can run it together.

The alert jobs do not converge. The unique indexes stop two instances opening
duplicate alerts, but nothing stops both recording a clear. Each run would
increment `clear_streak`, and an alert meant to need three clear evaluations
would resolve after two runs' worth of time, or fewer.

## Decision

Each scheduled alert job runs in one transaction that first calls
**`pg_try_advisory_xact_lock(<job key>)`**. The instance that gets the lock
runs the job; any other gets `false`, skips that run, and tries again at its
next interval.

An advisory lock is a lock on a number, rather than on a row, which PostgreSQL
holds on the application's behalf. The `try` form never waits. The `xact` form
is released automatically when the transaction ends, so a crashed instance
cannot leave it held.

## Alternatives

**ShedLock.** The usual library for this. It stores lock rows with an expiry
time, which is what a job needs if it spans transactions or must survive a
restart mid-run. These jobs are one short transaction each, so a lock the
database releases with the transaction is simpler, and needs no dependency and
no table.

**Leader election** (one designated instance runs every job). More moving
parts for the same effect.

**Make the clear idempotent per run.** Possible -- record a run id against each
clear -- but it changes the schema to work around a concurrency problem the
database can simply prevent.

## Consequences

- Only one instance records each clear, however many are running. A test holds
  the lock from a second connection -- as another instance would -- and checks
  that the run is skipped.
- A skipped run is not retried until the next interval, at most five minutes
  for rate of rise and one for offline. Nothing is lost: the next run judges the
  same data.
- `SchedulingConfig` now says, per job, why each one is or is not safe to run
  on every instance. Any new job has to make the same choice explicitly.
- The scheduled jobs are switched off in tests, which call the evaluators with
  chosen instants; a separate test checks the schedule's wiring.
