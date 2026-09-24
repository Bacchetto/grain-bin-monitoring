"""Data generation and the flaky transport. Pure functions, no network."""

import random
import statistics
from datetime import UTC, datetime, timedelta

from scenarios import (CABLES, DEPTHS, MAX_SAMPLES_PER_BATCH, FlakyLink, ReliableLink, Sample, chunks,
                       normal_sensors, seq_for)

NOON = datetime(2026, 9, 24, 12, 0, tzinfo=UTC)


def sample_at(at, seq=None):
    return Sample(seq if seq is not None else seq_for(at), at, normal_sensors(0, at, random.Random(1)))


# -- normal -----------------------------------------------------------------

def test_every_sensor_position_reports_exactly_once():
    sensors = normal_sensors(0, NOON, random.Random(1))

    positions = [(s.cable, s.depth) for s in sensors]
    assert len(positions) == CABLES * DEPTHS == 24
    assert len(set(positions)) == len(positions)
    assert {c for c, _ in positions} == set(range(CABLES))
    assert {d for _, d in positions} == set(range(DEPTHS))


def test_values_are_plausible_and_fit_the_backend_columns():
    for hour in range(24):
        for sensor in normal_sensors(3, NOON.replace(hour=hour), random.Random(hour)):
            assert 5.0 <= sensor.temperature_c <= 20.0
            assert 12.0 <= sensor.moisture_pct <= 16.0
            # One decimal place: the column is NUMERIC(4,1).
            assert round(sensor.temperature_c, 1) == sensor.temperature_c
            assert round(sensor.moisture_pct, 1) == sensor.moisture_pct


def test_the_daily_swing_is_near_the_top_and_gone_at_the_bottom():
    def temperatures_over_a_day(depth):
        return [
            next(s.temperature_c for s in normal_sensors(0, NOON.replace(hour=h), random.Random(0))
                 if s.cable == 0 and s.depth == depth)
            for h in range(24)
        ]

    top_range = max(temperatures_over_a_day(0)) - min(temperatures_over_a_day(0))
    bottom_range = max(temperatures_over_a_day(DEPTHS - 1)) - min(temperatures_over_a_day(DEPTHS - 1))

    # Grain insulates: the headspace follows the day, the bulk does not.
    assert top_range > 2.5
    assert bottom_range < 1.0


def test_generation_is_repeatable_for_a_given_seed():
    assert normal_sensors(1, NOON, random.Random(7)) == normal_sensors(1, NOON, random.Random(7))


def test_bins_do_not_all_report_the_same_baseline():
    means = [statistics.mean(s.temperature_c for s in normal_sensors(i, NOON, random.Random(0)))
             for i in range(3)]
    assert len({round(m, 1) for m in means}) == 3


# -- seq --------------------------------------------------------------------

def test_seq_increases_with_time_and_repeats_for_the_same_instant():
    assert seq_for(NOON + timedelta(seconds=10)) > seq_for(NOON)
    # A resent sample must carry the same seq, or the backend could not tell
    # it was a duplicate.
    assert seq_for(NOON) == seq_for(NOON)


# -- links ------------------------------------------------------------------

def test_a_reliable_link_sends_each_sample_once_immediately():
    link = ReliableLink()
    sample = sample_at(NOON)

    assert link.next_batch(sample) == [sample]
    assert link.flush() == []


def run_flaky(cycles=200, seed=3):
    link = FlakyLink(random.Random(seed))
    taken, batches = [], []
    for i in range(cycles):
        sample = sample_at(NOON + timedelta(seconds=10 * i))
        taken.append(sample)
        batches.append(link.next_batch(sample))
    batches.append(link.flush())
    return taken, batches


def test_a_flaky_link_never_loses_a_sample():
    taken, batches = run_flaky()
    sent = {s for batch in batches for s in batch}

    assert sent == set(taken)


def test_a_flaky_link_skips_intervals():
    _, batches = run_flaky()
    assert sum(1 for b in batches[:-1] if not b) > 10


def test_a_flaky_link_resends_samples():
    _, batches = run_flaky()
    sends = [s for batch in batches for s in batch]

    # The same sample going out more than once is what the backend reports as
    # a duplicate.
    assert len(sends) > len(set(sends))


def test_a_flaky_link_sends_samples_out_of_order():
    _, batches = run_flaky()
    shuffled = [b for b in batches if len(b) > 1
                and [s.recorded_at for s in b] != sorted(s.recorded_at for s in b)]
    assert shuffled


def test_a_skipped_interval_is_backfilled_by_the_next_send():
    link = FlakyLink(random.Random(0), skip_probability=1.0)
    first, second = sample_at(NOON), sample_at(NOON + timedelta(seconds=10))
    assert link.next_batch(first) == []

    link.skip_probability = 0.0
    link.resend_probability = 0.0
    assert set(link.next_batch(second)) == {first, second}


# -- batch limit ------------------------------------------------------------

def test_a_large_backlog_is_split_under_the_backend_limit():
    samples = [sample_at(NOON + timedelta(seconds=i)) for i in range(1234)]

    split = chunks(samples)

    assert [len(c) for c in split] == [500, 500, 234]
    assert all(len(c) <= MAX_SAMPLES_PER_BATCH for c in split)
    assert [s for c in split for s in c] == samples
