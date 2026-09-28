package com.grainbin.telemetry.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code ingest_readings_total{outcome}}: readings received, by what happened
 * to them.
 *
 * <p>Spring already counts HTTP requests, but a request is a batch of up to
 * 500 samples of 24 readings each, so requests per second says little about
 * throughput. This counts readings. Its three outcomes also make device
 * behaviour visible: a rising share of duplicates is devices resending,
 * usually a flaky link; rejected readings are usually a device clock gone
 * wrong.
 *
 * <p>All three are registered up front, so every series exists at zero before
 * the first batch -- a counter that appears only on its first increment shows
 * as "no data" in Grafana, and its first increment is lost to {@code rate()}.
 */
@Component
public class IngestMetrics {

	static final String METRIC_NAME = "ingest.readings";

	private final Counter accepted;
	private final Counter duplicate;
	private final Counter rejected;

	public IngestMetrics(MeterRegistry registry) {
		this.accepted = counter(registry, "accepted");
		this.duplicate = counter(registry, "duplicate");
		this.rejected = counter(registry, "rejected");
	}

	private static Counter counter(MeterRegistry registry, String outcome) {
		return Counter.builder(METRIC_NAME)
				.description("Readings received by the ingest endpoint, by outcome")
				.tag("outcome", outcome)
				.register(registry);
	}

	/** Counts one stored batch. Called only after its transaction has committed. */
	void record(IngestResponse response) {
		this.accepted.increment(response.accepted());
		this.duplicate.increment(response.duplicates());
		this.rejected.increment(response.rejected());
	}
}
