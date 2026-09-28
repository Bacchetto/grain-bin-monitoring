package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.config.SchedulingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The schedule's wiring, which the integration tests cannot see: they run with
 * scheduling switched off (see {@link AlertSchedule}) and call the evaluators
 * directly.
 *
 * <p>The evaluators are Mockito mocks here. What is under test is only that
 * Spring runs them, on the configured intervals, with the clock's time -- not
 * what they do, which the Testcontainers tests cover against real
 * PostgreSQL.
 */
class AlertScheduleTest {

	private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

	private final DeviceOfflineEvaluator deviceOffline = mock(DeviceOfflineEvaluator.class);
	private final RateOfRiseEvaluator rateOfRise = mock(RateOfRiseEvaluator.class);

	/*
	 * Scheduling is switched back on explicitly. Surefire sets
	 * APP_ALERTS_SCHEDULING_ENABLED=false for the whole test run, and the
	 * runner's environment reads real environment variables -- without this
	 * line the bean under test would not exist. (Properties set here take
	 * precedence over environment variables.)
	 */
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withPropertyValues("app.alerts.scheduling-enabled=true")
			.withUserConfiguration(SchedulingConfig.class, AlertSchedule.class)
			.withBean(DeviceOfflineEvaluator.class, () -> deviceOffline)
			.withBean(RateOfRiseEvaluator.class, () -> rateOfRise)
			.withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("the shipped intervals in application.properties are valid")
	void shippedIntervalsParse() {
		// "1m" and "5m" are parsed when the @Scheduled methods are registered.
		// A format Spring did not accept would stop the application starting,
		// and no other test would notice, because every other test runs with
		// the schedule switched off.
		this.runner.withInitializer(new ConfigDataApplicationContextInitializer())
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(AlertSchedule.class);
					assertThat(context.getEnvironment().getProperty("app.alerts.offline-check-interval"))
							.isEqualTo("1m");
				});
	}

	@Test
	@DisplayName("both jobs run on their interval, with the clock's time")
	void jobsRunWithClockTime() {
		this.runner.withPropertyValues(
						"app.alerts.offline-check-interval=50ms",
						"app.alerts.rate-of-rise-check-interval=50ms")
				.run(context -> {
					verify(deviceOffline, timeout(5_000).atLeast(2)).evaluate(NOW);
					verify(rateOfRise, timeout(5_000).atLeast(2)).evaluate(NOW);
				});
	}

	@Test
	@DisplayName("the schedule can be switched off")
	void canBeDisabled() {
		this.runner.withPropertyValues("app.alerts.scheduling-enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(AlertSchedule.class));
	}
}
