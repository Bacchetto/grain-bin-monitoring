package com.grainbin.telemetry.bins;

import java.time.Instant;

/**
 * One row of {@code GET /api/v1/bins}: enough to render the bin list without
 * a request per bin.
 *
 * @param lastReadingAt   when this bin last had a reading stored, or null if
 *                        it never has. Taken from {@code devices.last_seen_at}
 *                        rather than from {@code readings} -- see
 *                        {@code BinRepository} for why that matters.
 * @param worstOpenAlert  the most serious non-resolved alert on the bin, or
 *                        null if there are none. Always null until the alert
 *                        engine lands in Milestone 2; the query is real, there
 *                        is simply nothing writing alerts yet.
 * @param openAlertCount  how many non-resolved alerts the bin has
 */
public record BinSummaryResponse(
        long id,
        String name,
        String site,
        String grainType,
        Instant lastReadingAt,
        String worstOpenAlert,
        int openAlertCount) {
}
