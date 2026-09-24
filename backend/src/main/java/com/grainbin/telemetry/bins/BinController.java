package com.grainbin.telemetry.bins;

import com.grainbin.telemetry.common.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Admin and dashboard endpoints for bins.
 *
 * <p>Authenticated by {@code AdminAuthFilter}, which covers all of
 * {@code /api/v1}. There is no authentication logic in this class, and no
 * check that the caller "owns" a bin: with a single shared admin token there
 * is only one caller.
 */
@RestController
@RequestMapping("/api/v1/bins")
public class BinController {

	private final BinRepository bins;

	public BinController(BinRepository bins) {
		this.bins = bins;
	}

	/**
	 * Creates a bin, which starts with the default thresholds.
	 *
	 * <p>Returns 201 with a {@code Location} header. A duplicate
	 * {@code (site, name)} surfaces as a 409 from the unique constraint rather
	 * than being pre-checked with a SELECT, which would be both slower and
	 * racy.
	 */
	@PostMapping
	ResponseEntity<BinDetailResponse> create(@Valid @RequestBody CreateBinRequest request,
			UriComponentsBuilder uriBuilder) {

		long id = this.bins.create(request);
		BinDetailResponse created = this.bins.findById(id)
				.orElseThrow(() -> new IllegalStateException("Bin " + id + " vanished immediately after insert"));

		URI location = uriBuilder.path("/api/v1/bins/{id}").buildAndExpand(id).toUri();
		return ResponseEntity.created(location).body(created);
	}

	/** Every bin with its current status, for the bin list. */
	@GetMapping
	List<BinSummaryResponse> list() {
		return this.bins.findAllWithStatus();
	}

	@GetMapping("/{id}")
	BinDetailResponse get(@PathVariable long id) {
		return this.bins.findById(id).orElseThrow(() -> new NotFoundException("Bin", id));
	}

	/**
	 * Updates any subset of the bin's alert thresholds.
	 *
	 * <p>Returns the whole bin rather than just the thresholds, so a client
	 * that renders bin detail does not need a follow-up GET to refresh.
	 */
	@PatchMapping("/{id}/thresholds")
	BinDetailResponse updateThresholds(@PathVariable long id, @Valid @RequestBody UpdateThresholdsRequest request) {
		return this.bins.updateThresholds(id, request).orElseThrow(() -> new NotFoundException("Bin", id));
	}
}
