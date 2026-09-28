"""End to end: each alert scenario, against a running stack, raises its alert.

Skipped unless SIM_E2E_URL is set. Run against a local stack with the
alert checks shortened, so the scheduled alerts are judged within seconds:

    # terminal 1
    docker compose up -d db
    cd backend
    APP_ALERTS_RATE_OF_RISE_CHECK_INTERVAL=5s APP_ALERTS_OFFLINE_CHECK_INTERVAL=5s ./mvnw spring-boot:run

    # terminal 2
    cd simulator
    SIM_E2E_URL=http://localhost:8080 pytest -m e2e

The admin token comes from ADMIN_TOKEN, or the repository's .env. Each test
creates its own bin with a unique name, so runs never see each other's
alerts; the bins are left behind, since the API cannot delete them (E8).

The offline test takes about two minutes: DEVICE_OFFLINE runs on the server's
clock and cannot be compressed (ADR 0005).
"""

import os
import time
from datetime import timedelta

import pytest

from api import Api
from scenarios import HOT_CABLE, HOT_DEPTH, WET_DEPTH
from sim import MIN_EXPECTED_INTERVAL, SimDevice, load_dotenv, run

URL = os.environ.get("SIM_E2E_URL")

pytestmark = [
    pytest.mark.e2e,
    pytest.mark.skipif(not URL, reason="set SIM_E2E_URL to run against a live stack"),
]


@pytest.fixture(scope="module")
def api():
    load_dotenv()
    return Api(URL, admin_token=os.environ.get("ADMIN_TOKEN"))


def new_device(api, scenario):
    """A fresh bin and device for one test."""
    name = f"E2E {scenario} {time.time_ns()}"
    bin_id = api.create_bin(name, "E2E Farm", "canola")["id"]
    device = api.register_device(bin_id, MIN_EXPECTED_INTERVAL)
    return SimDevice(bin_id, name, device["id"], device["apiKey"], MIN_EXPECTED_INTERVAL)


def wait_for(api, bin_id, alert_type, timeout_s):
    """Polls the bin's open alerts until one of ``alert_type`` appears."""
    deadline = time.monotonic() + timeout_s
    while True:
        alerts = api.list_alerts(bin_id)
        matching = [a for a in alerts if a["type"] == alert_type]
        if matching:
            return matching, alerts
        if time.monotonic() > deadline:
            pytest.fail(f"no {alert_type} on bin {bin_id} within {timeout_s}s; open alerts: {alerts}")
        time.sleep(2)


def play(api, device, scenario, cycles, interval=1):
    # Replay as fast as the server will take it; a test has no audience.
    run(api, [device], scenario, interval=interval, cycles=cycles, seed=1,
        out=lambda *_: None, time_scale=1e9)


def test_hotspot_raises_rate_of_rise_and_not_high_temperature(api):
    device = new_device(api, "hotspot")

    play(api, device, "hotspot", cycles=1)

    rises, alerts = wait_for(api, device.bin_id, "RATE_OF_RISE", timeout_s=60)
    assert [(a["cableIndex"], a["depthIndex"]) for a in rises] == [(HOT_CABLE, HOT_DEPTH)]
    assert float(rises[0]["triggerValue"]) >= 2.0
    assert not [a for a in alerts if a["type"] == "HIGH_TEMPERATURE"]


def test_wet_raises_high_moisture_on_the_bottom_sensors(api):
    device = new_device(api, "wet")

    play(api, device, "wet", cycles=1)

    # Evaluated on ingest, so there is nothing to wait for.
    wet, alerts = wait_for(api, device.bin_id, "HIGH_MOISTURE", timeout_s=5)
    assert {a["depthIndex"] for a in wet} == {WET_DEPTH}
    assert len(wet) == 4, "one per cable"
    assert not [a for a in alerts if a["type"] in ("HIGH_TEMPERATURE", "RATE_OF_RISE")]


def test_offline_raises_device_offline_once_the_device_goes_quiet(api):
    device = new_device(api, "offline")

    play(api, device, "offline", cycles=None)  # the scenario's own five samples
    started = time.monotonic()

    offline, _ = wait_for(api, device.bin_id, "DEVICE_OFFLINE", timeout_s=180)
    assert [a["deviceId"] for a in offline] == [device.device_id]
    # Not before three registered intervals of silence.
    assert time.monotonic() - started >= 3 * MIN_EXPECTED_INTERVAL - 10
