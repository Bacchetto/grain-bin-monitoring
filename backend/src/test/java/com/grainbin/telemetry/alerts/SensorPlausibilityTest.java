package com.grainbin.telemetry.alerts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SensorPlausibilityTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"-127.0", "-127", "85.0", "85", "85.00", "-50.1", "100.1", "999.9"})
	@DisplayName("probe fault codes and impossible values are excluded")
	void faultsAreImplausible(String value) {
		assertThat(SensorPlausibility.isPlausibleTemperature(new BigDecimal(value))).isFalse();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"-50.0", "-35.0", "0.0", "12.4", "20.1", "60.0", "65.0", "84.9", "85.1", "100.0"})
	@DisplayName("real readings are kept, including dangerously hot ones")
	void measurementsArePlausible(String value) {
		// 65 matters most: heating grain passes 60 degrees on its way to
		// combustion, and filtering it out would suppress the alert that
		// matters most. See the class javadoc.
		assertThat(SensorPlausibility.isPlausibleTemperature(new BigDecimal(value))).isTrue();
	}
}
