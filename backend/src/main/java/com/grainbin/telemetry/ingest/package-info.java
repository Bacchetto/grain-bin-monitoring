/**
 * The write path: {@code POST /api/v1/readings}.
 *
 * <p>Validates and stores batches of sensor samples idempotently. This is the
 * hot path -- it is the only part of the service expected to take sustained
 * load, so it is written as explicit batched SQL rather than object mapping.
 *
 * <h2>Binding timestamps</h2>
 *
 * <p>The PostgreSQL JDBC driver <strong>cannot bind a
 * {@link java.time.Instant}</strong>. Passing one fails with:
 *
 * <pre>
 * Can't infer the SQL type to use for an instance of java.time.Instant.
 * </pre>
 *
 * <p>Spring translates that into a {@code BadSqlGrammarException}, so the
 * error names the statement rather than the parameter and points nowhere near
 * the real cause.
 *
 * <p>Samples arrive carrying {@code Instant}, and {@code recorded_at} and
 * {@code received_at} are {@code TIMESTAMPTZ}, so every such parameter must be
 * converted before binding:
 *
 * <pre>
 * instant.atOffset(ZoneOffset.UTC)   // OffsetDateTime -- binds correctly
 * </pre>
 *
 * <p>Use UTC rather than the system default. The readings partitions have UTC
 * month boundaries, and a reading near a month boundary bound in local time
 * would route to the wrong partition.
 */
package com.grainbin.telemetry.ingest;
