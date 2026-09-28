/**
 * Application wiring: security filters, scheduling, and beans.
 *
 * <p>Holds the servlet filters in front of {@code /api/v1} -- CORS first, then
 * the two authentication filters (device API key, admin bearer token) -- and
 * typed configuration properties.
 *
 * <p>Decisions recorded for this package:
 *
 * <ul>
 *   <li>{@code docs/decisions/0003-filter-based-auth.md}</li>
 *   <li>{@code docs/decisions/0015-advisory-lock-for-scheduled-alert-jobs.md} --
 *       why scheduled jobs are not all safe to run on every instance</li>
 *   <li>{@code docs/decisions/0017-cors-filter-before-authentication.md} --
 *       why the filter order matters</li>
 * </ul>
 */
package com.grainbin.telemetry.config;
