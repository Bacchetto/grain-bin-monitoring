"""The CLI's moving parts: .env, the device file, seeding, and the run loop."""

from datetime import UTC, datetime, timedelta

import pytest

import sim
from api import ApiError
from sim import SimDevice, Totals, load_devices, load_dotenv, run, save_devices, seed_bins


# -- .env -------------------------------------------------------------------

def test_dotenv_reads_keys_and_skips_comments_and_blanks(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text(
        "# a comment\n"
        "\n"
        "ADMIN_TOKEN=abc123\n"
        "DB_URL=jdbc:postgresql://localhost:5432/grain?x=1\n",
        encoding="utf-8")
    environ = {}

    load_dotenv(env_file, environ)

    assert environ == {"ADMIN_TOKEN": "abc123",
                       # Only the first = separates key from value.
                       "DB_URL": "jdbc:postgresql://localhost:5432/grain?x=1"}


def test_dotenv_never_overrides_the_real_environment(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text("ADMIN_TOKEN=from-file\n", encoding="utf-8")
    environ = {"ADMIN_TOKEN": "from-shell"}

    load_dotenv(env_file, environ)

    assert environ["ADMIN_TOKEN"] == "from-shell"


def test_a_missing_dotenv_is_fine(tmp_path):
    environ = {}
    load_dotenv(tmp_path / "absent", environ)
    assert environ == {}


# -- device file ------------------------------------------------------------

def test_devices_are_kept_separately_per_url(tmp_path):
    path = tmp_path / ".devices.json"
    local = [SimDevice(1, "Sim Bin 1", 10, "gbk_local")]
    cloud = [SimDevice(1, "Sim Bin 1", 99, "gbk_cloud")]

    save_devices("http://localhost:8080", local, path)
    save_devices("https://alb.example", cloud, path)

    # A key only means anything to the database that issued it, so seeding
    # the cloud must not overwrite the local keys.
    assert load_devices("http://localhost:8080", path) == local
    assert load_devices("https://alb.example", path) == cloud
    assert load_devices("http://elsewhere", path) == []


def test_devices_come_back_in_bin_id_order_not_name_order(tmp_path):
    path = tmp_path / ".devices.json"
    save_devices("u", [SimDevice(10, "Sim Bin 10", 1, "k10"), SimDevice(2, "Sim Bin 2", 2, "k2")], path)

    assert [d.bin_id for d in load_devices("u", path)] == [2, 10]


# -- seeding ----------------------------------------------------------------

class FakeApi:
    """Just enough of Api for seeding: bins by name, devices by counter."""

    def __init__(self, existing_bins=None):
        self.bins = dict(existing_bins or {})   # name -> id
        self.devices_registered = []

    def create_bin(self, name, site, grain_type):
        if name in self.bins:
            raise ApiError(409, "That resource already exists.")
        self.bins[name] = 100 + len(self.bins)
        return {"id": self.bins[name]}

    def list_bins(self):
        return [{"id": i, "name": n, "site": sim.SIM_SITE} for n, i in self.bins.items()]

    def register_device(self, bin_id, interval):
        # The backend's rule. A fake that accepted what the real service
        # rejects is how the first end-to-end run failed while every unit test
        # passed.
        if interval < 30:
            raise ApiError(400, "The request failed validation.")
        self.devices_registered.append(bin_id)
        self.intervals = getattr(self, "intervals", []) + [interval]
        return {"id": 500 + len(self.devices_registered), "apiKey": f"gbk_{bin_id}_{len(self.devices_registered)}"}


def quiet(*_):
    pass


def test_seeding_creates_bins_and_one_device_each(tmp_path):
    api = FakeApi()

    devices = seed_bins(api, "u", 3, 10, tmp_path / "d.json", out=quiet)

    assert [d.bin_name for d in devices] == ["Sim Bin 1", "Sim Bin 2", "Sim Bin 3"]
    assert len(api.devices_registered) == 3
    assert load_devices("u", tmp_path / "d.json") == devices


def test_seeding_registers_at_least_the_backends_minimum_interval(tmp_path):
    api = FakeApi()

    # 10 is the CLI's default --interval and the README's own example.
    seed_bins(api, "u", 2, 10, tmp_path / "d.json", out=quiet)

    assert api.intervals == [30, 30]


def test_seeding_keeps_a_longer_interval_as_given(tmp_path):
    api = FakeApi()
    seed_bins(api, "u", 1, 120, tmp_path / "d.json", out=quiet)
    assert api.intervals == [120]


def test_seeding_again_does_nothing_for_bins_already_held(tmp_path):
    path = tmp_path / "d.json"
    api = FakeApi()
    first = seed_bins(api, "u", 2, 10, path, out=quiet)

    second = seed_bins(api, "u", 2, 10, path, out=quiet)

    assert second == first
    assert len(api.devices_registered) == 2   # no new devices


def test_seeding_more_bins_adds_only_the_new_ones(tmp_path):
    path = tmp_path / "d.json"
    api = FakeApi()
    seed_bins(api, "u", 2, 10, path, out=quiet)

    devices = seed_bins(api, "u", 3, 10, path, out=quiet)

    assert len(devices) == 3
    assert len(api.devices_registered) == 3


def test_a_bin_that_exists_on_the_server_but_not_here_gets_a_new_device(tmp_path):
    # Seeded from another machine: the bin exists, but its key cannot be
    # retrieved again, so the only way to stream to it is a new device.
    api = FakeApi(existing_bins={"Sim Bin 1": 42})

    devices = seed_bins(api, "u", 1, 10, tmp_path / "d.json", out=quiet)

    assert devices[0].bin_id == 42
    assert api.devices_registered == [42]


def test_other_api_errors_are_not_swallowed(tmp_path):
    class Broken(FakeApi):
        def create_bin(self, *args):
            raise ApiError(500, "boom")

    with pytest.raises(ApiError):
        seed_bins(Broken(), "u", 1, 10, tmp_path / "d.json", out=quiet)


# -- the run loop -----------------------------------------------------------

class RecordingApi:
    def __init__(self):
        self.posts = []

    def post_readings(self, key, samples):
        self.posts.append((key, samples))
        return {"accepted": 24 * len(samples), "duplicates": 0, "rejected": 0}


def fixed_clock(start):
    ticks = iter(start + timedelta(seconds=10 * i) for i in range(10_000))
    return lambda: next(ticks)


DEVICES = [SimDevice(1, "Sim Bin 1", 11, "k1"), SimDevice(2, "Sim Bin 2", 12, "k2")]
START = datetime(2026, 9, 24, 12, 0, tzinfo=UTC)


def test_normal_sends_one_sample_per_device_per_cycle_without_waiting():
    api = RecordingApi()
    sleeps = []

    totals = run(api, DEVICES, "normal", 10, cycles=3, seed=1, out=quiet,
                 sleep=sleeps.append, now=fixed_clock(START))

    assert len(api.posts) == 6
    assert all(len(samples) == 1 for _, samples in api.posts)
    assert {key for key, _ in api.posts} == {"k1", "k2"}
    assert totals == Totals(accepted=6 * 24)
    # Sleeps between cycles, not after the last one.
    assert len(sleeps) == 2


def test_flaky_delivers_every_sample_by_the_end_of_a_finite_run():
    api = RecordingApi()

    run(api, DEVICES, "flaky", 10, cycles=50, seed=5, out=quiet, sleep=lambda _: None, now=fixed_clock(START))

    for key in ("k1", "k2"):
        delivered = {s.seq for k, samples in api.posts if k == key for s in samples}
        # 50 cycles, one sample each; skipped ones are flushed at the end.
        assert len(delivered) == 50
