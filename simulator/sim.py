"""Grain bin telemetry simulator.

Seed some bins, then stream readings to them:

    python simulator/sim.py --seed-bins 3
    python simulator/sim.py --scenario normal --bins 3 --interval 10
    python simulator/sim.py --scenario flaky --cycles 20

Point it at a deployed stack with --url. Device keys are kept per URL in
simulator/.devices.json, which is git-ignored: it holds plaintext keys.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from api import Api, ApiError
from scenarios import FlakyLink, ReliableLink, Sample, chunks, normal_sensors, seq_for

HERE = Path(__file__).resolve().parent
DEVICES_FILE = HERE / ".devices.json"
ENV_FILE = HERE.parent / ".env"

SIM_SITE = "Sim Farm"

# The backend refuses to register a device that claims to report more often
# than every 30 seconds. DEVICE_OFFLINE fires at three times the interval, and
# anything shorter would raise it after a single hiccup.
MIN_EXPECTED_INTERVAL = 30
SIM_GRAIN = "canola"

# Milestone 1 scenarios. hotspot, wet and offline -- and --time-scale, which
# they need -- arrive in Milestone 2.
#
# A note for the offline scenario: --time-scale cannot speed it up.
# DEVICE_OFFLINE compares the server's clock with devices.last_seen_at, which
# is also the server's clock (ADR 0005), so nothing the simulator does to
# recordedAt affects it. The alert fires after three real registered intervals,
# at least 90 seconds -- which is also why seeded devices use the 30-second
# minimum rather than something longer.
SCENARIOS = ("normal", "flaky")


# ---------------------------------------------------------------------------
# .env
# ---------------------------------------------------------------------------

def load_dotenv(path: Path = ENV_FILE, environ: dict | None = None) -> None:
    """Read KEY=VALUE lines from the repository's .env into the environment.

    Deliberately minimal rather than a dependency: the format the project uses
    is plain KEY=VALUE with # comments. A variable already set in the real
    environment wins, matching how Docker Compose and the API's local profile
    treat the same file.
    """
    environ = os.environ if environ is None else environ
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        environ.setdefault(key.strip(), value.strip())


# ---------------------------------------------------------------------------
# Device keys, kept per API URL
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SimDevice:
    bin_id: int
    bin_name: str
    device_id: int
    api_key: str


def load_devices(url: str, path: Path = DEVICES_FILE) -> list[SimDevice]:
    """The devices seeded against ``url``, in bin id order.

    Keyed by URL so that seeding a deployed stack does not overwrite the keys
    for the local one, or the other way round. A key only means anything to the
    database that issued it.
    """
    if not path.is_file():
        return []
    entries = json.loads(path.read_text(encoding="utf-8")).get(url, [])
    # By id, not name: "Sim Bin 10" would sort before "Sim Bin 2".
    return sorted((SimDevice(**entry) for entry in entries), key=lambda d: d.bin_id)


def save_devices(url: str, devices: list[SimDevice], path: Path = DEVICES_FILE) -> None:
    everything = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    everything[url] = [device.__dict__ for device in devices]
    path.write_text(json.dumps(everything, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# --seed-bins
# ---------------------------------------------------------------------------

def seed_bins(api: Api, url: str, count: int, interval: int,
              path: Path = DEVICES_FILE, out=print) -> list[SimDevice]:
    """Create ``Sim Bin 1`` to ``Sim Bin <count>``, each with one device.

    Each device is registered as reporting every ``interval`` seconds, raised to
    the backend's 30-second minimum if need be. Streaming more often than that
    later is fine: the interval is how often a device promises to report at
    least, and it only matters for deciding when it has gone quiet.

    Safe to run repeatedly. A bin this file already holds a key for is left
    alone. A bin that exists on the server but not here -- seeded from another
    machine, say -- gets a new device, because an existing key can never be
    retrieved again. And if the database has been reset, the bins are simply
    created afresh and their stale keys replaced.
    """
    known = {device.bin_name: device for device in load_devices(url, path)}
    seeded: dict[str, SimDevice] = dict(known)

    for n in range(1, count + 1):
        name = f"Sim Bin {n}"
        try:
            bin_id = api.create_bin(name, SIM_SITE, SIM_GRAIN)["id"]
        except ApiError as error:
            if error.status != 409:
                raise
            bin_id = next(b["id"] for b in api.list_bins() if b["site"] == SIM_SITE and b["name"] == name)
            if name in known and known[name].bin_id == bin_id:
                out(f"  {name}: already seeded (bin {bin_id})")
                continue

        device = api.register_device(bin_id, max(interval, MIN_EXPECTED_INTERVAL))
        seeded[name] = SimDevice(bin_id, name, device["id"], device["apiKey"])
        out(f"  {name}: bin {bin_id}, device {device['id']}")

    devices = sorted(seeded.values(), key=lambda d: d.bin_id)
    save_devices(url, devices, path)
    return devices


# ---------------------------------------------------------------------------
# Streaming readings
# ---------------------------------------------------------------------------

@dataclass
class Totals:
    accepted: int = 0
    duplicates: int = 0
    rejected: int = 0

    def add(self, response: dict) -> None:
        self.accepted += response["accepted"]
        self.duplicates += response["duplicates"]
        self.rejected += response["rejected"]


def run(api: Api, devices: list[SimDevice], scenario: str, interval: int,
        cycles: int | None, seed: int, out=print, sleep=time.sleep, now=lambda: datetime.now(UTC)) -> Totals:
    """Take a sample per device every ``interval`` seconds and send it.

    ``cycles`` of None means run until interrupted. ``sleep`` and ``now`` are
    injectable so tests need not wait in real time.
    """
    links = {
        device.device_id: FlakyLink(random.Random(seed + i)) if scenario == "flaky" else ReliableLink()
        for i, device in enumerate(devices)
    }
    rngs = {device.device_id: random.Random(seed * 1000 + i) for i, device in enumerate(devices)}
    totals = Totals()

    def send(device: SimDevice, batch: list[Sample]) -> None:
        for chunk in chunks(batch):
            response = api.post_readings(device.api_key, chunk)
            totals.add(response)
            out(f"{device.bin_name} (device {device.device_id}): sent {len(chunk)} sample(s) -> "
                f"accepted {response['accepted']}, duplicates {response['duplicates']}, "
                f"rejected {response['rejected']}")

    cycle = 0
    try:
        while cycles is None or cycle < cycles:
            started = time.monotonic()
            at = now().replace(microsecond=0)
            for i, device in enumerate(devices):
                sample = Sample(seq_for(at), at, normal_sensors(i, at, rngs[device.device_id]))
                batch = links[device.device_id].next_batch(sample)
                if batch:
                    send(device, batch)
                else:
                    out(f"{device.bin_name} (device {device.device_id}): skipped this interval")
            cycle += 1
            if cycles is None or cycle < cycles:
                sleep(max(0.0, interval - (time.monotonic() - started)))
    except KeyboardInterrupt:
        out("\nStopping.")
    finally:
        # A finite run should leave nothing buffered in a flaky link.
        for device in devices:
            leftover = links[device.device_id].flush()
            if leftover:
                send(device, leftover)

    return totals


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def positive(value: str) -> int:
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return number


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Simulate grain bin monitoring devices.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--seed-bins", type=positive, metavar="N",
                      help="create N bins with one device each, and save their keys")
    mode.add_argument("--scenario", choices=SCENARIOS,
                      help="stream readings: normal, or flaky (duplicates, reordering, skipped intervals)")
    parser.add_argument("--url", default="http://localhost:8080", help="API base URL (default: %(default)s)")
    parser.add_argument("--admin-token", help="admin token for --seed-bins (default: ADMIN_TOKEN from .env)")
    parser.add_argument("--bins", type=positive, help="stream to the first N seeded bins (default: all)")
    parser.add_argument("--interval", type=positive, default=10,
                        help="seconds between samples, at least 1 (default: %(default)s)")
    parser.add_argument("--cycles", type=positive, help="stop after N samples per device (default: run until Ctrl-C)")
    parser.add_argument("--seed", type=int, default=42, help="random seed, for repeatable runs (default: %(default)s)")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    # Line-buffered, so progress appears as it happens even when piped to a
    # file, and stays in order with messages on stderr.
    sys.stdout.reconfigure(line_buffering=True)
    args = parse_args(sys.argv[1:] if argv is None else argv)
    load_dotenv()
    api = Api(args.url, admin_token=args.admin_token or os.environ.get("ADMIN_TOKEN"))

    try:
        if args.seed_bins:
            print(f"Seeding {args.seed_bins} bin(s) at {args.url}:")
            if args.interval < MIN_EXPECTED_INTERVAL:
                print(f"  (devices registered as reporting every {MIN_EXPECTED_INTERVAL}s, the backend's minimum; "
                      f"streaming more often than that is fine)")
            seed_bins(api, args.url, args.seed_bins, args.interval)
            print(f"Device keys saved to {DEVICES_FILE.relative_to(HERE.parent)}")
            return 0

        devices = load_devices(args.url)
        if not devices:
            print(f"No devices seeded for {args.url}. Run with --seed-bins first.", file=sys.stderr)
            return 2
        if args.bins:
            if args.bins > len(devices):
                print(f"Only {len(devices)} bin(s) seeded for {args.url}; run --seed-bins {args.bins} first.",
                      file=sys.stderr)
                return 2
            devices = devices[:args.bins]

        print(f"Streaming '{args.scenario}' to {len(devices)} bin(s) every {args.interval}s. Ctrl-C to stop.")
        totals = run(api, devices, args.scenario, args.interval, args.cycles, args.seed)
        print(f"Total: accepted {totals.accepted}, duplicates {totals.duplicates}, rejected {totals.rejected}")
        return 0

    except ApiError as error:
        print(f"API error: {error}", file=sys.stderr)
        if error.status == 401 and not args.seed_bins:
            print("A device key was refused. If the database was reset, run --seed-bins again.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
