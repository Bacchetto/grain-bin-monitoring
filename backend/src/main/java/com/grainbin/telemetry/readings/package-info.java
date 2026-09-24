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
 *
 * <p>Both read queries are bounded on {@code recorded_at}, the partition key, so
 * PostgreSQL opens only the partitions a request's time range overlaps. Their
 * plans, measured on 6.2 million rows, and the reason one index was dropped
 * after they were measured, are in
 * {@code docs/decisions/0007-read-path-indexes-verified-with-explain-analyze.md}.
 */
package com.grainbin.telemetry.readings;
