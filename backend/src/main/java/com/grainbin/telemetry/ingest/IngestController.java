package com.grainbin.telemetry.ingest;

import com.grainbin.telemetry.common.PayloadTooLargeException;
import com.grainbin.telemetry.common.RequestValidationException;
import com.grainbin.telemetry.devices.AuthenticatedDevice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * {@code POST /api/v1/readings}: the device write path.
 *
 * <p>Authenticated by {@code DeviceAuthFilter}, which resolves the device from
 * its {@code X-Device-Key} before this class runs.
 *
 * <p><strong>Why 202 and not 201 or 200.</strong> The README specifies 202
 * Accepted. Processing is in fact synchronous today -- the readings are stored
 * by the time the response is sent -- so 202 slightly understates it. It is
 * still the right contract: the README's stretch goal puts an SQS buffer in
 * front of the database writer, at which point the work genuinely does become
 * asynchronous, and devices will not need to change.
 */
@RestController
public class IngestController {

	private final IngestService ingest;
	private final Validator validator;

	public IngestController(IngestService ingest, Validator validator) {
		this.ingest = ingest;
		this.validator = validator;
	}

	/**
	 * Validation is run by hand rather than with {@code @Valid}, because the
	 * order matters. The batch limit is checked first: an oversized request is
	 * a 413 whatever its contents, and validating every one of 100,000 samples
	 * only to reject the lot for its size would be wasted work. {@code @Valid}
	 * runs before the method body and so cannot express that.
	 *
	 * <p>A structurally invalid sample fails the <em>whole</em> batch with 400,
	 * and nothing is stored. Only future-dated samples are rejected
	 * individually, because that is the one per-sample rejection the README
	 * defines.
	 */
	@PostMapping("/api/v1/readings")
	ResponseEntity<IngestResponse> ingest(@RequestBody IngestRequest request, HttpServletRequest http) {
		// Throws -- a 500, deliberately -- if the filter is not registered on
		// this path. That is a wiring bug, not something to treat as anonymous.
		AuthenticatedDevice device = AuthenticatedDevice.require(http);

		if (request.samples() != null && request.samples().size() > IngestService.MAX_SAMPLES_PER_BATCH) {
			throw new PayloadTooLargeException(
					"A batch may contain at most " + IngestService.MAX_SAMPLES_PER_BATCH + " samples; this one has "
							+ request.samples().size() + ". Split it into smaller requests.",
					IngestService.MAX_SAMPLES_PER_BATCH);
		}

		Set<ConstraintViolation<IngestRequest>> violations = this.validator.validate(request);
		if (!violations.isEmpty()) {
			throw new RequestValidationException(violations);
		}

		return ResponseEntity.accepted().body(this.ingest.ingest(device, request.samples()));
	}
}
