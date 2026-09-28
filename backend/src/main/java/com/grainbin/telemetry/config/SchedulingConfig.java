package com.grainbin.telemetry.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} method execution.
 *
 * <p>With more than one ECS task, every task runs every job. How each job
 * copes with that is decided per job, not assumed:
 *
 * <ul>
 *   <li><strong>Partition maintenance</strong> converges: creating a
 *       partition that exists is a no-op, so any number of instances can run
 *       it at once.</li>
 *   <li><strong>The alert jobs</strong> do not. Unique indexes stop duplicate
 *       alerts, but two instances would both record a clear, and an alert
 *       would auto-resolve after fewer real evaluations than intended. They
 *       run under a PostgreSQL advisory lock, so one instance at a time does
 *       the work: {@code alerts.ScheduledJobLock}.</li>
 * </ul>
 *
 * <p>A new job has to make the same choice explicitly.
 *
 * <p>All jobs share Spring's default single scheduler thread. That is enough:
 * each run is short, and the rate-of-rise run, the longest, reads two days of
 * data per bin.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
