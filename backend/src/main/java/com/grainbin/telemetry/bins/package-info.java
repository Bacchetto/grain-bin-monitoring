/**
 * Grain bins: the physical storage units being monitored.
 *
 * <p>Owns bin records and their per-bin alert thresholds, and the admin
 * endpoints for creating bins and listing them with current status.
 *
 * <h2>Deleting a bin does not delete its readings</h2>
 *
 * <p>There is no delete endpoint today. <strong>If one is added, read this
 * first.</strong>
 *
 * <p>Deletion looks fully handled, and is not. {@code devices} and
 * {@code alerts} both reference {@code bins} with {@code ON DELETE CASCADE},
 * so they disappear with the bin. {@code readings} does not: it carries
 * {@code bin_id} and {@code device_id} as plain columns with no foreign key,
 * for the throughput reasons recorded in
 * {@code docs/decisions/0001-no-foreign-keys-on-readings.md}.
 *
 * <p>So deleting a bin leaves its readings behind permanently. They consume
 * storage, are counted by any aggregate that does not filter, and no
 * constraint will ever flag them. Two of three child tables cascading is what
 * makes this easy to miss.
 *
 * <p>A delete endpoint must therefore remove the readings explicitly, in the
 * same transaction as the bin. Note that this is not a cheap operation across
 * a partitioned table, which should be weighed when designing the endpoint --
 * a soft delete, or a background reclaim, may be the better shape.
 *
 * <p>The same applies to deleting a single device; see
 * {@code com.grainbin.telemetry.devices}.
 */
package com.grainbin.telemetry.bins;
