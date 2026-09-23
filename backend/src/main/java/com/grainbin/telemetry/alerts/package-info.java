/**
 * Detection and lifecycle of alert conditions.
 *
 * <p>Threshold alerts are evaluated synchronously on ingest; rate-of-rise and
 * device-offline alerts run on a schedule. Alerts move
 * OPEN -> ACKNOWLEDGED -> RESOLVED and are de-duplicated per
 * (bin, type, cable, depth).
 *
 * <p>Milestone 1 provides only the database schema; the engine arrives in
 * Milestone 2.
 */
package com.grainbin.telemetry.alerts;
