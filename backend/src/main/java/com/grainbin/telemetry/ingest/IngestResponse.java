package com.grainbin.telemetry.ingest;

/**
 * Outcome of one ingest request.
 *
 * <p><strong>All three counts are in readings -- one per sensor value -- not in
 * samples.</strong> The README's example is one sample with two sensors
 * returning {@code "accepted": 2}, which fixes the unit. Counting rejections the
 * same way keeps an invariant a device can check:
 *
 * <pre>
 * accepted + duplicates + rejected == number of sensor values sent
 * </pre>
 *
 * @param accepted   newly stored
 * @param duplicates already stored by an earlier request, or repeated within
 *                   this one; not stored again
 * @param rejected   not stored because the sample was recorded outside the
 *                   accepted window: more than five minutes in the future, or
 *                   longer ago than {@code app.ingest.max-sample-age} (30 days
 *                   by default), both by the server's clock
 */
public record IngestResponse(int accepted, int duplicates, int rejected) {
}
