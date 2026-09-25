package com.grainbin.telemetry.alerts;

/**
 * The four conditions the engine detects. The names are stored as-is in
 * {@code alerts.type}, whose CHECK constraint lists the same four values.
 */
public enum AlertType {

	/** A sensor above the bin's {@code max_temperature_c}. Evaluated on ingest. */
	HIGH_TEMPERATURE,

	/** A sensor above the bin's {@code max_moisture_pct}. Evaluated on ingest. */
	HIGH_MOISTURE,

	/** A sensor warming by {@code rise_threshold_c} over the rise window. Scheduled. */
	RATE_OF_RISE,

	/** A device silent for more than 3x its expected interval. Scheduled. */
	DEVICE_OFFLINE;

	/**
	 * Whether the alert is about a sensor position rather than a device. The
	 * two kinds are de-duplicated by different indexes, so they need
	 * different {@code ON CONFLICT} targets -- see the package documentation.
	 */
	public boolean isSensorAlert() {
		return this != DEVICE_OFFLINE;
	}
}
