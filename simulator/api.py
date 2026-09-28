"""A thin client for the parts of the grain bin API the simulator uses."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import requests

from scenarios import Sample


class ApiError(Exception):
    """The API refused a request. Carries the RFC 9457 problem detail if one came back."""

    def __init__(self, status: int, detail: str, body: Any = None):
        # A 400 from the backend lists the offending fields under "errors".
        # Without them the message says only that validation failed, not what
        # to fix.
        fields = ""
        if isinstance(body, dict) and isinstance(body.get("errors"), list):
            fields = " " + "; ".join(f"{e.get('field')}: {e.get('message')}" for e in body["errors"])
        super().__init__(f"HTTP {status}: {detail}{fields}")
        self.status = status
        self.detail = detail
        self.body = body


def iso_utc(at: datetime) -> str:
    """``2026-10-01T14:00:00Z`` -- the form the README's example uses."""
    return at.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def readings_payload(samples: list[Sample]) -> dict:
    """The ``POST /api/v1/readings`` body, in exactly the README's shape.

    Kept as a pure function so the wire format can be tested without a server.
    There is no device or bin id anywhere in it: the backend takes both from the
    API key.
    """
    return {
        "samples": [
            {
                "seq": sample.seq,
                "recordedAt": iso_utc(sample.recorded_at),
                "sensors": [
                    {
                        "cable": sensor.cable,
                        "depth": sensor.depth,
                        "temperatureC": sensor.temperature_c,
                        "moisturePct": sensor.moisture_pct,
                    }
                    for sensor in sample.sensors
                ],
            }
            for sample in samples
        ]
    }


class Api:
    """
    ``session`` is injectable so tests can substitute a fake and run without a
    network. In real use it is a ``requests.Session``, which also reuses
    connections between calls.
    """

    def __init__(self, base_url: str, admin_token: str | None = None,
                 session: Any = None, timeout: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.admin_token = admin_token
        self.session = session if session is not None else requests.Session()
        self.timeout = timeout

    # -- admin -------------------------------------------------------------

    def create_bin(self, name: str, site: str, grain_type: str) -> dict:
        return self._admin("POST", "/api/v1/bins",
                           {"name": name, "site": site, "grainType": grain_type})

    def list_bins(self) -> list[dict]:
        return self._admin("GET", "/api/v1/bins")

    def register_device(self, bin_id: int, expected_interval_seconds: int) -> dict:
        return self._admin("POST", f"/api/v1/bins/{bin_id}/devices",
                           {"expectedIntervalSeconds": expected_interval_seconds})

    def list_alerts(self, bin_id: int, status: str = "open,acknowledged") -> list[dict]:
        """A bin's alerts, newest first. Used by the end-to-end tests to see
        whether a scenario raised what it should."""
        return self._admin("GET", f"/api/v1/alerts?binId={bin_id}&status={status}")

    def _admin(self, method: str, path: str, body: dict | None = None) -> Any:
        if not self.admin_token:
            raise ApiError(0, "No admin token. Set ADMIN_TOKEN in .env, or pass --admin-token.")
        headers = {"Authorization": f"Bearer {self.admin_token}"}
        return self._send(method, path, headers, body)

    # -- device ------------------------------------------------------------

    def post_readings(self, api_key: str, samples: list[Sample]) -> dict:
        """Returns the backend's ``{accepted, duplicates, rejected}``."""
        return self._send("POST", "/api/v1/readings", {"X-Device-Key": api_key},
                          readings_payload(samples))

    # -- transport ---------------------------------------------------------

    def _send(self, method: str, path: str, headers: dict, body: dict | None) -> Any:
        try:
            response = self.session.request(method, self.base_url + path, headers=headers,
                                            json=body, timeout=self.timeout)
        except requests.ConnectionError as error:
            raise ApiError(0, f"Cannot reach {self.base_url}. Is the API running?") from error

        if response.status_code >= 400:
            try:
                problem = response.json()
                detail = problem.get("detail") or problem.get("title") or response.reason
            except ValueError:
                problem, detail = None, response.reason
            raise ApiError(response.status_code, detail, problem)

        return response.json()
