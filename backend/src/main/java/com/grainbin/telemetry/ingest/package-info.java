/**
 * The write path: {@code POST /api/v1/readings}.
 *
 * <p>Validates and stores batches of sensor samples idempotently. This is the
 * hot path -- it is the only part of the service expected to take sustained
 * load, so it is written as explicit batched SQL rather than object mapping.
 */
package com.grainbin.telemetry.ingest;
