package com.grainbin.telemetry.alerts;

import java.math.BigDecimal;

/**
 * Decides whether a temperature is a measurement or a probe fault.
 *
 * <p>Common probes report fixed values when something is wrong, and those
 * values are stored like any other reading. Fed to the engine, they raise
 * false alarms caused by hardware rather than grain: a sensor reading
 * {@code -127} and then {@code 12} looks like a 139 degree rise, and {@code 85}
 * trips {@code HIGH_TEMPERATURE} at once. See {@code docs/enhancements.md}, E3.
 *
 * <p>An implausible value is still stored and still shown on the dashboard --
 * a probe reporting faults is itself worth seeing. It is only kept out of
 * alert evaluation, where it counts as neither a detection nor a clear.
 *
 * <h2>Why the range is wide</h2>
 *
 * <p>Getting this wrong has two very different costs. Treating a fault as real
 * costs a false alarm. Treating a real reading as a fault costs a missed one --
 * and the readings most at risk of that are the extreme ones, which are
 * exactly the ones that matter most. Heating grain can pass 60 degrees on the
 * way to combustion. So the range only excludes what no bin can physically
 * report, and the known fault codes are matched exactly rather than by
 * narrowing the range around them.
 */
public final class SensorPlausibility {

	/**
	 * Colder than any stored grain on the prairies has reached. Also excludes
	 * {@code -127}, the value a DS18B20 reports when its probe is
	 * disconnected.
	 */
	static final BigDecimal MIN_PLAUSIBLE_TEMPERATURE_C = new BigDecimal("-50.0");

	/** Grain is ash well before this. */
	static final BigDecimal MAX_PLAUSIBLE_TEMPERATURE_C = new BigDecimal("100.0");

	/**
	 * A DS18B20's power-on reset value: it reports exactly this before its
	 * first conversion completes. Inside the plausible range, so it has to be
	 * matched exactly. A bin that is genuinely at 85 degrees does not sit on
	 * exactly 85.0 -- the readings either side of it still alert.
	 */
	static final BigDecimal POWER_ON_RESET_C = new BigDecimal("85.0");

	private SensorPlausibility() {
	}

	/** Whether a temperature should take part in alert evaluation. */
	public static boolean isPlausibleTemperature(BigDecimal temperatureC) {
		// compareTo, not equals: BigDecimal.equals treats 85.0 and 85.00 as
		// different values.
		return temperatureC.compareTo(MIN_PLAUSIBLE_TEMPERATURE_C) >= 0
				&& temperatureC.compareTo(MAX_PLAUSIBLE_TEMPERATURE_C) <= 0
				&& temperatureC.compareTo(POWER_ON_RESET_C) != 0;
	}

	/**
	 * The same rule as a SQL predicate, for queries that aggregate readings
	 * in the database (the rate-of-rise averages). Kept next to the Java
	 * version so that the two cannot drift apart unnoticed; a test checks
	 * that they agree.
	 */
	public static final String PLAUSIBLE_TEMPERATURE_SQL =
			"temperature_c BETWEEN -50.0 AND 100.0 AND temperature_c <> 85.0";
}
