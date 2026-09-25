/**
 * Detection and lifecycle of alert conditions.
 *
 * <p>Threshold alerts are evaluated synchronously on ingest; rate-of-rise and
 * device-offline alerts run on a schedule. Alerts move
 * OPEN -&gt; ACKNOWLEDGED -&gt; RESOLVED.
 *
 * <p>The pieces:
 *
 * <ul>
 *   <li>{@link com.grainbin.telemetry.alerts.AlertRepository} -- single-statement
 *       writes: the two upserts and the clear-and-maybe-resolve.</li>
 *   <li>{@link com.grainbin.telemetry.alerts.AlertLifecycle} -- what evaluators
 *       call; pairs each write with the transition it causes.</li>
 *   <li>{@link com.grainbin.telemetry.alerts.AlertTransitions} -- the metric and
 *       the structured log line, reported only after commit.</li>
 *   <li>{@link com.grainbin.telemetry.alerts.SensorPlausibility} -- keeps probe
 *       fault values out of evaluation.</li>
 *   <li>{@link com.grainbin.telemetry.alerts.ThresholdEvaluator} --
 *       {@code HIGH_TEMPERATURE} and {@code HIGH_MOISTURE}, called by ingest
 *       inside its transaction. Its javadoc defines what counts as one
 *       evaluation.</li>
 * </ul>
 *
 * <h2>De-duplication: two rules, not one</h2>
 *
 * <p>At most one non-resolved alert may exist per condition, but what counts
 * as "a condition" depends on what the alert is about:
 *
 * <table border="1">
 *   <caption>Active-alert keys</caption>
 *   <tr><th>Alert types</th><th>Key</th></tr>
 *   <tr>
 *     <td>{@code HIGH_TEMPERATURE}, {@code HIGH_MOISTURE}, {@code RATE_OF_RISE}</td>
 *     <td>{@code (bin, type, cable, depth)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DEVICE_OFFLINE}</td>
 *     <td>{@code (bin, type, device)}</td>
 *   </tr>
 * </table>
 *
 * <p>{@code DEVICE_OFFLINE} has no sensor position, so keying it by cable and
 * depth would collapse to one alert per bin and a second offline controller
 * could never be reported. See
 * {@code docs/decisions/0002-device-scoped-offline-alert-dedupe.md}.
 *
 * <h2>What this means for the engine</h2>
 *
 * <p>The two rules are enforced by two disjoint partial unique indexes, so
 * <strong>there is no single {@code ON CONFLICT} clause that covers both</strong>.
 * Turning "detected again" into an update of {@code last_detected_at} requires
 * the conflict target matching the alert type being written:
 *
 * <pre>
 * sensor alerts    ON CONFLICT (bin_id, type, cable_index, depth_index)
 * DEVICE_OFFLINE   ON CONFLICT (bin_id, type, device_id)
 * </pre>
 *
 * <p>Both indexes are partial, so the {@code WHERE status &lt;&gt; 'RESOLVED'}
 * predicate has to be repeated in the conflict target for PostgreSQL to use
 * them for inference.
 *
 * <p>Consequences worth knowing before writing the engine:
 *
 * <ul>
 *   <li>{@code GET /alerts} may return several {@code DEVICE_OFFLINE} rows for
 *       one bin. The dashboard's "worst open alert" badge is unaffected, since
 *       it reduces to a severity rather than a count.</li>
 *   <li>Auto-resolve is per alert row, so one device recovering must not clear
 *       another device's outage on the same bin.</li>
 *   <li>Probe fault values are stored as ordinary readings. {@code -127}
 *       would make the next real value look like a huge
 *       {@code RATE_OF_RISE}, and {@code 85} on power-up would trip
 *       {@code HIGH_TEMPERATURE}. Every evaluator must skip readings that
 *       {@link com.grainbin.telemetry.alerts.SensorPlausibility} rejects,
 *       counting them as neither a detection nor a clear. See also
 *       {@code docs/enhancements.md}, E3.</li>
 *   <li>Lifecycle invariants are {@code CHECK} constraints in the database, so
 *       setting {@code status} and its matching timestamp must happen in the
 *       same statement or the write is rejected.</li>
 * </ul>
 */
package com.grainbin.telemetry.alerts;
