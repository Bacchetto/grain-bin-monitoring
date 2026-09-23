package com.grainbin.telemetry;

import org.springframework.boot.SpringApplication;

public class TestTelemetryApplication {

	public static void main(String[] args) {
		SpringApplication.from(TelemetryApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
