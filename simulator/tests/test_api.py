"""The HTTP client, against a fake session: the wire format and error handling."""

import json
from datetime import UTC, datetime

import pytest

from api import Api, ApiError, iso_utc, readings_payload
from scenarios import Sample, Sensor


class FakeResponse:
    def __init__(self, status, body=None, reason="OK"):
        self.status_code = status
        self._body = body
        self.reason = reason

    def json(self):
        if self._body is None:
            raise ValueError("no body")
        return self._body


class FakeSession:
    """Records every request and answers with a queued response."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def request(self, method, url, headers=None, json=None, timeout=None):
        self.requests.append({"method": method, "url": url, "headers": headers, "json": json})
        return self.responses.pop(0)


SAMPLE = Sample(
    seq=1042,
    recorded_at=datetime(2026, 10, 1, 14, 0, tzinfo=UTC),
    sensors=(Sensor(0, 0, 11.4, 13.9), Sensor(0, 1, 12.1, None)),
)


def test_payload_matches_the_readme_contract_exactly():
    payload = readings_payload([SAMPLE])

    # Byte-for-byte the README's field names. The backend has a matching test
    # that posts the README example verbatim; between them, a rename on either
    # side fails a test.
    assert payload == {
        "samples": [{
            "seq": 1042,
            "recordedAt": "2026-10-01T14:00:00Z",
            "sensors": [
                {"cable": 0, "depth": 0, "temperatureC": 11.4, "moisturePct": 13.9},
                {"cable": 0, "depth": 1, "temperatureC": 12.1, "moisturePct": None},
            ],
        }]
    }


def test_payload_never_names_a_device_or_bin():
    # Both come from the API key on the server side.
    text = json.dumps(readings_payload([SAMPLE]))
    assert "device" not in text.lower()
    assert "bin" not in text.lower()


def test_timestamps_are_utc_with_a_z_suffix():
    from datetime import timedelta, timezone
    edmonton = datetime(2026, 10, 1, 8, 0, tzinfo=timezone(timedelta(hours=-6)))
    assert iso_utc(edmonton) == "2026-10-01T14:00:00Z"


def test_readings_are_sent_with_the_device_key_and_no_admin_token():
    session = FakeSession(FakeResponse(202, {"accepted": 2, "duplicates": 0, "rejected": 0}))
    api = Api("http://api.example/", admin_token="admin-secret", session=session)

    result = api.post_readings("gbk_device", [SAMPLE])

    sent = session.requests[0]
    assert result == {"accepted": 2, "duplicates": 0, "rejected": 0}
    assert sent["url"] == "http://api.example/api/v1/readings"
    assert sent["headers"] == {"X-Device-Key": "gbk_device"}


def test_admin_calls_carry_the_bearer_token():
    session = FakeSession(FakeResponse(201, {"id": 7}))
    api = Api("http://api.example", admin_token="admin-secret", session=session)

    api.create_bin("Sim Bin 1", "Sim Farm", "canola")

    assert session.requests[0]["headers"] == {"Authorization": "Bearer admin-secret"}
    assert session.requests[0]["json"] == {"name": "Sim Bin 1", "site": "Sim Farm", "grainType": "canola"}


def test_admin_calls_without_a_token_fail_clearly_before_any_request():
    session = FakeSession()
    api = Api("http://api.example", admin_token=None, session=session)

    with pytest.raises(ApiError, match="ADMIN_TOKEN"):
        api.list_bins()
    assert session.requests == []


def test_a_problem_document_becomes_an_api_error_with_its_detail():
    session = FakeSession(FakeResponse(409, {"title": "Conflict", "detail": "That resource already exists."},
                                       reason="Conflict"))
    api = Api("http://api.example", admin_token="t", session=session)

    with pytest.raises(ApiError) as raised:
        api.create_bin("Sim Bin 1", "Sim Farm", "canola")

    assert raised.value.status == 409
    assert raised.value.detail == "That resource already exists."


def test_an_error_without_a_json_body_still_reports_the_status():
    session = FakeSession(FakeResponse(502, None, reason="Bad Gateway"))
    api = Api("http://api.example", session=session)

    with pytest.raises(ApiError) as raised:
        api.post_readings("gbk_x", [SAMPLE])

    assert raised.value.status == 502
    assert raised.value.detail == "Bad Gateway"


def test_validation_errors_name_the_offending_fields():
    problem = {"title": "Bad Request", "detail": "The request failed validation.",
               "errors": [{"field": "expectedIntervalSeconds", "message": "must be at least 30 seconds"}]}
    session = FakeSession(FakeResponse(400, problem, reason="Bad Request"))
    api = Api("http://api.example", admin_token="t", session=session)

    with pytest.raises(ApiError) as raised:
        api.register_device(1, 10)

    # The message that would have made the first end-to-end failure obvious.
    assert "expectedIntervalSeconds: must be at least 30 seconds" in str(raised.value)
