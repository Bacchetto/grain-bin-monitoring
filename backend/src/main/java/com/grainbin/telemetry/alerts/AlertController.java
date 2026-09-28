package com.grainbin.telemetry.alerts;

import com.grainbin.telemetry.common.ConflictException;
import com.grainbin.telemetry.common.NotFoundException;
import com.grainbin.telemetry.common.RequestValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The alerts view: listing alerts and acknowledging them.
 *
 * <p>Authenticated by {@code AdminAuthFilter}, which covers all of
 * {@code /api/v1}.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

	static final int DEFAULT_LIMIT = 100;

	/**
	 * Resolved alerts accumulate forever -- history is kept -- so an
	 * unfiltered list grows without bound. The spec does not ask for
	 * pagination; a cap keeps any one response a sensible size, and the
	 * newest-first order means the cap drops the oldest history, not current
	 * problems.
	 */
	static final int MAX_LIMIT = 500;

	private final AlertRepository alerts;
	private final AlertLifecycle lifecycle;
	private final Clock clock;

	public AlertController(AlertRepository alerts, AlertLifecycle lifecycle, Clock clock) {
		this.alerts = alerts;
		this.lifecycle = lifecycle;
		this.clock = clock;
	}

	/**
	 * Alerts, newest detection first.
	 *
	 * <p>{@code status} takes one value, as the README specifies --
	 * {@code open}, {@code acknowledged} or {@code resolved} -- or several,
	 * comma-separated: {@code status=open,acknowledged} is exactly what the
	 * dashboard's alerts view shows. Omitted, it means every status.
	 *
	 * <p>{@code binId} narrows to one bin. It is a filter, not a resource in
	 * the path, so an unknown bin gives an empty list rather than a 404.
	 */
	@GetMapping
	List<AlertResponse> list(
			@RequestParam(required = false) List<String> status,
			@RequestParam(required = false) Long binId,
			@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

		if (limit < 1 || limit > MAX_LIMIT) {
			throw new RequestValidationException("limit", "must be between 1 and " + MAX_LIMIT);
		}
		return this.alerts.find(parseStatuses(status), binId, limit);
	}

	/**
	 * Marks an alert as seen.
	 *
	 * <ul>
	 *   <li>OPEN: becomes ACKNOWLEDGED. 200 with the updated alert.</li>
	 *   <li>Already ACKNOWLEDGED: 200 with the alert, unchanged. Idempotent, so
	 *       a double-click or a retried request is harmless.</li>
	 *   <li>RESOLVED: 409. The condition is over; there is nothing left to
	 *       acknowledge.</li>
	 *   <li>No such alert: 404.</li>
	 * </ul>
	 *
	 * <p>POST, not PATCH: this is an action with a server-set timestamp, not
	 * a client editing a field.
	 */
	@PostMapping("/{id}/acknowledge")
	AlertResponse acknowledge(@PathVariable long id) {
		this.lifecycle.acknowledge(id, this.clock.instant());

		// Read back whatever state the alert is now in. If this call did not
		// acknowledge it, that state explains why.
		AlertResponse alert = this.alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert", id));
		if (alert.status() == AlertStatus.RESOLVED) {
			throw new ConflictException("Alert " + id + " is already resolved and cannot be acknowledged.");
		}
		return alert;
	}

	private static Set<AlertStatus> parseStatuses(List<String> values) {
		if (values == null || values.isEmpty()) {
			return EnumSet.allOf(AlertStatus.class);
		}
		Set<AlertStatus> statuses = EnumSet.noneOf(AlertStatus.class);
		List<String> unknown = new ArrayList<>();
		for (String value : values) {
			// Spring has already split "open,acknowledged" into two values.
			String name = value.strip().toUpperCase(Locale.ROOT);
			if (Arrays.stream(AlertStatus.values()).anyMatch(s -> s.name().equals(name))) {
				statuses.add(AlertStatus.valueOf(name));
			}
			else {
				unknown.add(value);
			}
		}
		if (!unknown.isEmpty()) {
			throw new RequestValidationException("status", "unknown value " + unknown + "; must be one of: "
					+ Arrays.stream(AlertStatus.values())
							.map(s -> s.name().toLowerCase(Locale.ROOT))
							.collect(Collectors.joining(", ")));
		}
		return statuses;
	}
}
