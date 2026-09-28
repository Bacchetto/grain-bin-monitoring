"""The alert scenarios produce the shapes their alerts need.

These check the generators against the backend's rules, re-implemented here
from their definitions rather than imported from anywhere: the point is an
independent statement of what the backend will see. The backend proves its
side against the same shapes in RateOfRiseEvaluatorIntegrationTest and
ThresholdAlertIntegrationTest; the opt-in end-to-end tests in test_e2e.py
prove the two agree.
"""

import random
from datetime import UTC, datetime, timedelta

from scenarios import (CABLES, DEPTHS, HOT_CABLE, HOT_DEPTH, SCENARIOS, WET_DEPTH, hotspot_sensors,
                       normal_sensors, wet_sensors)

ORIGIN = datetime(2026, 9, 1, 0, 0, tzinfo=UTC)
STEP = timedelta(minutes=5)
DAY = timedelta(days=1)

# The backend's defaults (README).
MAX_TEMPERATURE = 20.0
MAX_MOISTURE = 14.5
RISE_THRESHOLD = 2.0
RISE_WINDOW = timedelta(hours=72)


def series(sensors, days, cable, depth, field="temperature_c"):
    """One sensor's values every five minutes for ``days`` days from ORIGIN."""
    rng = random.Random(7)
    out = []
    at = ORIGIN
    while at < ORIGIN + days * DAY:
        sensor = next(s for s in sensors(0, at, rng, ORIGIN) if (s.cable, s.depth) == (cable, depth))
        out.append((at, getattr(sensor, field)))
        at += STEP
    return out


def mean_between(points, start, end):
    values = [v for at, v in points if start <= at < end]
    return sum(values) / len(values) if values else None


def rise_at(points, now):
    """The backend's rule (ADR 0013): the last 24 h's average minus that of the
    24 h ending RISE_WINDOW ago. None while either window is empty."""
    current = mean_between(points, now - DAY, now)
    baseline = mean_between(points, now - RISE_WINDOW - DAY, now - RISE_WINDOW)
    return None if current is None or baseline is None else current - baseline


def first_time(points, condition):
    return next((at for at, _ in points if condition(at)), None)


def normal(bin_index, at, rng, origin):
    return normal_sensors(bin_index, at, rng)


# -- hotspot ----------------------------------------------------------------

def test_hotspot_raises_rate_of_rise_well_before_high_temperature():
    points = series(hotspot_sensors, days=12, cable=HOT_CABLE, depth=HOT_DEPTH)
    value = dict(points)

    # Checked hourly, as a stand-in for the backend's five-minute schedule.
    hourly = [at for at, _ in points if at.minute == 0]
    rises = next(at for at in hourly if (rise_at(points, at) or 0) >= RISE_THRESHOLD)
    too_hot = first_time(points, lambda at: value[at] > MAX_TEMPERATURE)

    assert too_hot is not None, "the hot spot should eventually pass 20 degrees"
    assert rises < too_hot
    # Not just before, but with days to spare: that is what an early warning is.
    assert too_hot - rises > 3 * DAY
    assert value[rises] < 15.0


def test_hotspot_rises_within_its_default_history():
    # A replay of the scenario's default history ends at "now". The rise must
    # already be over the threshold then, or the demo shows nothing.
    history = SCENARIOS["hotspot"].history
    points = series(hotspot_sensors, days=history / DAY, cable=HOT_CABLE, depth=HOT_DEPTH)

    assert rise_at(points, ORIGIN + history) >= RISE_THRESHOLD
    assert max(v for _, v in points) < MAX_TEMPERATURE


def test_hotspot_changes_only_the_hot_sensor():
    rng_a, rng_b = random.Random(3), random.Random(3)
    at = ORIGIN + 4 * DAY

    hot = hotspot_sensors(0, at, rng_a, ORIGIN)
    plain = normal_sensors(0, at, rng_b)

    differing = [(h.cable, h.depth) for h, p in zip(hot, plain) if h != p]
    assert differing == [(HOT_CABLE, HOT_DEPTH)]


def test_hotspot_is_steady_for_its_first_three_days():
    points = series(hotspot_sensors, days=3, cable=HOT_CABLE, depth=HOT_DEPTH)
    plain = series(normal, days=3, cable=HOT_CABLE, depth=HOT_DEPTH)

    assert points == plain


# -- normal: the control ------------------------------------------------------

def test_normal_raises_nothing_although_the_top_swings_more_than_the_threshold():
    for cable in range(CABLES):
        for depth in range(DEPTHS):
            points = series(normal, days=5, cable=cable, depth=depth)
            rise = rise_at(points, ORIGIN + 5 * DAY)
            assert abs(rise) < 0.5, (cable, depth, rise)
            assert max(v for _, v in points) <= MAX_TEMPERATURE

    top = series(normal, days=1, cable=0, depth=0)
    assert max(v for _, v in top) - min(v for _, v in top) > RISE_THRESHOLD


# -- wet --------------------------------------------------------------------

def test_wet_raises_high_moisture_on_the_bottom_sensors_only():
    for cable in range(CABLES):
        bottom = series(wet_sensors, days=2, cable=cable, depth=WET_DEPTH, field="moisture_pct")
        crossed = first_time(bottom, lambda at, b=dict(bottom): b[at] > MAX_MOISTURE)
        assert crossed is not None
        # Within its default 24 h of history, so a replay shows it at once.
        assert crossed - ORIGIN < SCENARIOS["wet"].history

        for depth in range(DEPTHS - 1):
            above = series(wet_sensors, days=2, cable=cable, depth=depth, field="moisture_pct")
            assert max(v for _, v in above) <= MAX_MOISTURE, (cable, depth)


def test_wet_leaves_temperature_alone():
    rng_a, rng_b = random.Random(3), random.Random(3)
    at = ORIGIN + DAY

    wet = wet_sensors(0, at, rng_a, ORIGIN)
    plain = normal_sensors(0, at, rng_b)

    assert [s.temperature_c for s in wet] == [s.temperature_c for s in plain]


# -- the registry -----------------------------------------------------------

def test_every_scenario_the_readme_lists_exists():
    assert set(SCENARIOS) == {"normal", "flaky", "hotspot", "wet", "offline"}


def test_only_offline_stops_by_itself():
    assert {name for name, s in SCENARIOS.items() if s.stops_after is not None} == {"offline"}
