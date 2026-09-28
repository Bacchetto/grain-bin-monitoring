"""What a simulated bin reports, and how a misbehaving device transmits it.

Everything here is pure: no network, and no clock reads -- the caller passes
the time in. That keeps the tests deterministic and lets the same code drive a
real-time run or a replay of the past at ``--time-scale``.

Each scenario's sensor function takes ``origin``, the moment the scenario
started, so a shape like "steady for three days, then warming" is defined in
simulated time and comes out the same whether it is replayed or run live.
"""

from __future__ import annotations

import math
import random
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta

# One device per bin, 4 cables of 6 sensors each: 24 readings per sample.
# Depth 0 is the top of the cable, nearest the headspace.
CABLES = 4
DEPTHS = 6

# The backend's batch limit. Exceeding it is a 413.
MAX_SAMPLES_PER_BATCH = 500

# Mid-afternoon in Alberta, UTC-6 in summer. The daily temperature swing peaks
# here. The exact hour hardly matters -- it only has to be plausible.
PEAK_HOUR_UTC = 21


@dataclass(frozen=True)
class Sensor:
    cable: int
    depth: int
    temperature_c: float
    moisture_pct: float | None


@dataclass(frozen=True)
class Sample:
    """One reporting cycle from one device."""

    seq: int
    recorded_at: datetime
    sensors: tuple[Sensor, ...]


def seq_for(at: datetime) -> int:
    """The sequence number for a sample taken at ``at``.

    The backend requires ``seq`` to increase monotonically per device. A real
    controller keeps a counter in non-volatile memory; this simulator derives it
    from the timestamp instead, in whole seconds. That is monotonic, survives the
    simulator being restarted without any state on disk, and -- the point for the
    ``flaky`` scenario -- gives a resent sample exactly the same ``seq`` as the
    original, so the backend recognises it as a duplicate.

    It does mean one device cannot sample more than once a second, which the CLI
    enforces.
    """
    return int(at.timestamp())


def normal_sensors(bin_index: int, at: datetime, rng: random.Random) -> tuple[Sensor, ...]:
    """Stable temperatures, with a small daily swing near the top of the bin.

    Grain is a good insulator, so the air above the grain warms and cools with
    the day but the bulk does not: the swing is largest at depth 0 and has died
    out by depth 3. Each bin sits at a slightly different baseline so the
    dashboard does not show identical bins, and the deeper grain is a little
    warmer and wetter, as it tends to be.
    """
    baseline = 9.0 + (bin_index % 5) * 0.8
    hours = at.hour + at.minute / 60
    daily = math.cos(2 * math.pi * (hours - PEAK_HOUR_UTC) / 24)  # +1 at the peak

    sensors = []
    for cable in range(CABLES):
        for depth in range(DEPTHS):
            swing = 1.5 * max(0.0, 1 - depth / 3)
            temperature = baseline + 0.3 * depth + swing * daily + rng.gauss(0, 0.1)
            moisture = 13.5 + 0.1 * depth + rng.gauss(0, 0.1)
            # One decimal place: the backend stores NUMERIC(4,1), and sending
            # more precision than is kept would only be rounded away.
            sensors.append(Sensor(cable, depth, round(temperature, 1), round(moisture, 1)))
    return tuple(sensors)


# ---------------------------------------------------------------------------
# hotspot: one sensor slowly heating
# ---------------------------------------------------------------------------

# Mid-bin, where heating from insects or moisture usually starts and where the
# daily swing of the headspace does not reach.
HOT_CABLE, HOT_DEPTH = 1, 3

# Steady for three days, then warming 1.5 degrees a day. The three days give
# the backend's rate-of-rise rule a full baseline: it compares the last 24 h
# with the 24 h that ended 72 h ago. After two days of warming, the daily
# averages are about 2.25 degrees apart -- over the 2.0 threshold -- while the
# sensor itself is still near 13 degrees, far below HIGH_TEMPERATURE's 20.
# That ordering is the point of the scenario (README): RATE_OF_RISE is the
# early warning. The backend's RateOfRiseEvaluatorIntegrationTest uses the
# same shape.
HOTSPOT_STEADY = timedelta(hours=72)
HOTSPOT_RISE_PER_DAY = 1.5


def hotspot_rise(at: datetime, origin: datetime) -> float:
    """Degrees the hot sensor has gained over its normal value by ``at``."""
    warming = at - origin - HOTSPOT_STEADY
    return max(0.0, warming / timedelta(days=1)) * HOTSPOT_RISE_PER_DAY


def hotspot_sensors(bin_index: int, at: datetime, rng: random.Random, origin: datetime) -> tuple[Sensor, ...]:
    """The normal bin, except that one sensor mid-bin is heating."""
    rise = hotspot_rise(at, origin)
    return tuple(
        replace(s, temperature_c=round(s.temperature_c + rise, 1)) if (s.cable, s.depth) == (HOT_CABLE, HOT_DEPTH) else s
        for s in normal_sensors(bin_index, at, rng)
    )


# ---------------------------------------------------------------------------
# wet: moisture rising at the bottom
# ---------------------------------------------------------------------------

# Water collects at the bottom of a bin -- a leak, or condensation on the
# floor. Only the deepest sensor on each cable is affected.
WET_DEPTH = DEPTHS - 1

# The bottom normally reads about 14.0 %; at 1 % a day it passes the default
# 14.5 % threshold twelve hours in, raising HIGH_MOISTURE on ingest.
WET_RISE_PER_DAY = 1.0


def wet_sensors(bin_index: int, at: datetime, rng: random.Random, origin: datetime) -> tuple[Sensor, ...]:
    """The normal bin, except that moisture climbs on the bottom sensors."""
    rise = max(0.0, (at - origin) / timedelta(days=1)) * WET_RISE_PER_DAY
    return tuple(
        replace(s, moisture_pct=round(s.moisture_pct + rise, 1)) if s.depth == WET_DEPTH else s
        for s in normal_sensors(bin_index, at, rng)
    )


# ---------------------------------------------------------------------------
# The scenarios
# ---------------------------------------------------------------------------

SensorFunction = Callable[[int, datetime, random.Random, datetime], tuple[Sensor, ...]]


@dataclass(frozen=True)
class Scenario:
    """What a scenario reports, and how.

    ``history`` is how much of the past a run replays before going live, by
    default. The backend judges rate of rise against its own clock, so a shape
    that takes days to develop has to be placed in the past to be seen now.
    """

    name: str
    sensors: SensorFunction
    history: timedelta = timedelta(0)
    flaky: bool = False
    #: Stop after this many samples per device, by default -- the offline
    #: scenario's "device stops reporting after N samples".
    stops_after: int | None = None


def _normal(bin_index: int, at: datetime, rng: random.Random, origin: datetime) -> tuple[Sensor, ...]:
    return normal_sensors(bin_index, at, rng)


OFFLINE_AFTER = 5

SCENARIOS: dict[str, Scenario] = {
    "normal": Scenario("normal", _normal),
    "flaky": Scenario("flaky", _normal, flaky=True),
    # Five days: the three steady days, then two of warming.
    "hotspot": Scenario("hotspot", hotspot_sensors, history=timedelta(hours=120)),
    "wet": Scenario("wet", wet_sensors, history=timedelta(hours=24)),
    # Nothing to replay: DEVICE_OFFLINE is judged on the server's clock
    # against when readings arrived, so it cannot be compressed (ADR 0005).
    "offline": Scenario("offline", _normal, stops_after=OFFLINE_AFTER),
}


# ---------------------------------------------------------------------------
# How samples reach the server
# ---------------------------------------------------------------------------


class ReliableLink:
    """Sends every sample as soon as it is taken, exactly once."""

    def next_batch(self, sample: Sample) -> list[Sample]:
        return [sample]

    def flush(self) -> list[Sample]:
        return []


@dataclass
class FlakyLink:
    """A device on a poor connection. Exercises the backend's idempotency.

    Each cycle it may:

    * **skip the interval** -- nothing is sent, and the sample waits in a
      backlog that goes out with the next successful send, the way a real
      device buffers through an outage;
    * **resend the previous batch** -- as a device does when the server stored
      its data but the acknowledgement was lost, so it cannot tell the send
      worked. These come back as ``duplicates``;
    * **shuffle** whatever it sends, so samples arrive out of order.

    Nothing is ever lost: every sample is sent at least once, by ``flush`` at
    the latest.
    """

    rng: random.Random
    skip_probability: float = 0.25
    resend_probability: float = 0.3
    backlog: list[Sample] = field(default_factory=list)
    last_sent: list[Sample] = field(default_factory=list)

    def next_batch(self, sample: Sample) -> list[Sample]:
        self.backlog.append(sample)
        if self.rng.random() < self.skip_probability:
            return []

        batch = list(self.backlog)
        if self.last_sent and self.rng.random() < self.resend_probability:
            batch.extend(self.last_sent)
        self.rng.shuffle(batch)

        self.last_sent = list(self.backlog)
        self.backlog.clear()
        return batch

    def flush(self) -> list[Sample]:
        """Whatever is still buffered, so a finite run leaves nothing unsent."""
        batch, self.backlog = self.backlog, []
        return batch


def chunks(samples: list[Sample], size: int = MAX_SAMPLES_PER_BATCH) -> list[list[Sample]]:
    """Split a batch so no single request exceeds the backend's limit.

    A flaky device that missed hundreds of intervals would otherwise send a
    backlog large enough to be refused outright with a 413.
    """
    return [samples[i:i + size] for i in range(0, len(samples), size)]
