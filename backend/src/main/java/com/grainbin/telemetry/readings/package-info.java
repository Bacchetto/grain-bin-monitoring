/**
 * The {@code readings} table: its storage lifecycle and its query paths.
 *
 * <p>Owns everything about the table itself -- keeping its monthly partitions
 * in existence, and serving the dashboard reads (latest value per sensor, and
 * time-bucketed history).
 *
 * <p>Kept separate from {@code ingest}, which owns the HTTP write endpoint and
 * its validation rules. The two have opposite performance characteristics:
 * many small writes versus a few large aggregate reads. {@code ingest} depends
 * on this package for partition maintenance, not the other way round.
 */
package com.grainbin.telemetry.readings;
