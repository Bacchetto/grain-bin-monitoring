/**
 * Monitoring controllers attached to bins.
 *
 * <p>Owns device registration, API key generation and hashing, and the
 * {@code last_seen_at} timestamp that the DEVICE_OFFLINE alert depends on.
 * A device authenticates with its own key and never sends its own id.
 *
 * <h2>device_id is the only thing keeping readings honest</h2>
 *
 * <p>{@code readings} has no foreign key to this table
 * ({@code docs/decisions/0001-no-foreign-keys-on-readings.md}). The database
 * will happily store a reading whose {@code device_id} refers to nothing.
 *
 * <p>The reason that is safe today is entirely a property of this package:
 * {@code device_id} and {@code bin_id} are read from the device row resolved
 * from the API key hash, never taken from the request. A caller cannot name a
 * device, so it cannot name the wrong one.
 *
 * <p>That is a guarantee about code, not about the schema. <strong>Anything
 * that writes to {@code readings} outside the authenticated ingest path --
 * a repair script, a bulk import, a backfill tool -- must validate
 * {@code device_id} and {@code bin_id} itself.</strong>
 *
 * <p>Deleting a device has the same consequence as deleting a bin: its
 * readings survive it, orphaned and unflagged. See
 * {@code com.grainbin.telemetry.bins}.
 *
 * <p>Orphans can be found, but only by asking:
 *
 * <pre>
 * SELECT r.bin_id, count(*)
 * FROM readings r
 * LEFT JOIN devices d ON d.id = r.device_id
 * WHERE d.id IS NULL
 * GROUP BY r.bin_id;
 * </pre>
 *
 * <p>Nothing runs this. If orphans ever become a real concern it belongs in a
 * scheduled check reporting a metric, not in a constraint.
 */
package com.grainbin.telemetry.devices;
