package com.grainbin.telemetry.readings;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Width of the time buckets the history endpoint aggregates into.
 *
 * <p>Each constant carries the {@code date_trunc} unit it maps to. That unit is
 * written directly into the SQL, which is safe <em>only</em> because it comes
 * from this enum: the caller's string is parsed into a constant or rejected, and
 * never reaches the query itself.
 */
public enum Bucket {

	HOUR("hour", Duration.ofHours(1)),
	DAY("day", Duration.ofDays(1));

	private final String sqlUnit;
	private final Duration width;

	Bucket(String sqlUnit, Duration width) {
		this.sqlUnit = sqlUnit;
		this.width = width;
	}

	/** The {@code date_trunc} unit. A fixed literal, never caller input. */
	String sqlUnit() {
		return this.sqlUnit;
	}

	Duration width() {
		return this.width;
	}

	/** The name used in the API, e.g. {@code "hour"}. */
	public String apiName() {
		return this.sqlUnit;
	}

	/** Case-insensitive; empty if the value names no bucket. */
	static Optional<Bucket> parse(String value) {
		return Arrays.stream(values())
				.filter(bucket -> bucket.sqlUnit.equals(value.trim().toLowerCase(Locale.ROOT)))
				.findFirst();
	}

	static String allowedValues() {
		return Arrays.stream(values()).map(Bucket::apiName).collect(Collectors.joining(", "));
	}
}
