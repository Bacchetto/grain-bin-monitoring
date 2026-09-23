/**
 * The read path: latest values and time-bucketed history.
 *
 * <p>Serves the dashboard queries. Kept separate from {@code ingest} because
 * the two have opposite performance characteristics: many small writes versus
 * a few large aggregate reads.
 */
package com.grainbin.telemetry.readings;
