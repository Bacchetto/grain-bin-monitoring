package com.grainbin.telemetry.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} method execution.
 *
 * <p>Scheduled work in this service is deliberately idempotent and safe to run
 * on every instance at once. There is no leader election and no distributed
 * lock: with more than one ECS task, every task runs every job. That is
 * acceptable because each job either converges on the same state
 * (partition creation) or is guarded by a unique constraint in the database
 * (alert de-duplication, Milestone 2).
 *
 * <p>If a job is ever added that is <em>not</em> safe to run concurrently,
 * that assumption has to be revisited rather than quietly relied upon.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
