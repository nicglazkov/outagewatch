"""API tests with faked dependencies."""

from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from outagewatch.api import main as api_main
from tests.fakes import FakeEtaHistory, FakeSlo, FakeStateStore, FakeSubs
from watcher.types import Item


class FakeDeps:
    def __init__(self):
        self.state = FakeStateStore()
        self.subs = FakeSubs()
        self.eta_history = FakeEtaHistory()
        self.slo = FakeSlo()


@pytest.fixture
def client():
    deps = FakeDeps()
    api_main.app.dependency_overrides[api_main.get_deps] = lambda: deps
    yield TestClient(api_main.app), deps
    api_main.app.dependency_overrides.clear()


def _outage(oid="o1", lat=38.44, lon=-122.71):
    return Item(
        id=oid,
        eta=datetime(2026, 7, 9, 18, tzinfo=UTC),
        lat=lat,
        lon=lon,
        geometry={"type": "Point", "coordinates": [lon, lat]},
        payload={"OUTAGE_CAUSE": "POLE FIRE", "EST_CUSTOMERS": 40, "CITY": "Santa Rosa"},
    )


def test_healthz(client):
    c, _ = client
    assert c.get("/healthz").json() == {"status": "ok"}


def test_list_outages_near_point(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage(), "far": _outage(oid="far", lat=41.5, lon=-120.5)}
    resp = c.get("/v1/outages", params={"lat": 38.45, "lon": -122.71, "radius_km": 10})
    ids = [o["id"] for o in resp.json()]
    assert ids == ["o1"]
    body = resp.json()[0]
    assert body["cause"] == "POLE FIRE"
    assert body["geometry"] is None  # not requested


def test_list_outages_by_zip_uses_centroid(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage()}  # ~10km from 95404 centroid
    resp = c.get("/v1/outages", params={"zip": "95404"})
    assert [o["id"] for o in resp.json()] == ["o1"]
    assert c.get("/v1/outages", params={"zip": "10001"}).status_code == 404


def test_outage_detail_includes_geometry_and_history(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage()}
    deps.eta_history.append("o1", datetime(2026, 7, 9, 18, tzinfo=UTC), datetime.now(UTC))
    body = c.get("/v1/outages/o1").json()
    assert body["outage"]["geometry"]["type"] == "Point"
    assert len(body["eta_history"]) == 1
    assert c.get("/v1/outages/nope").status_code == 404


def test_create_zip_subscription_resolves_centroid(client):
    c, deps = client
    resp = c.post(
        "/v1/subscriptions",
        json={"token": "tok-12345678", "zip_code": "95404", "platform": "android"},
    )
    assert resp.status_code == 201
    sub = deps.subs.list_all()[0]
    assert sub.lat is not None and sub.radius_km >= 9
    assert sub.zip_code == "95404"


def test_create_subscription_rejects_no_location(client):
    c, _ = client
    resp = c.post("/v1/subscriptions", json={"token": "tok-12345678"})
    assert resp.status_code == 422


def test_create_subscription_rejects_unknown_zip(client):
    c, _ = client
    resp = c.post("/v1/subscriptions", json={"token": "tok-12345678", "zip_code": "10001"})
    assert resp.status_code == 422


def test_subscription_lifecycle(client):
    c, deps = client
    sub_id = c.post(
        "/v1/subscriptions",
        json={"token": "tok-12345678", "lat": 38.44, "lon": -122.71, "radius_km": 2},
    ).json()["id"]
    listed = c.get("/v1/subscriptions", params={"token": "tok-12345678"}).json()
    assert [s["id"] for s in listed] == [sub_id]
    assert c.delete(f"/v1/subscriptions/{sub_id}").status_code == 204
    assert deps.subs.list_all() == []


def test_zip_lookup_endpoint(client):
    c, _ = client
    body = c.get("/v1/zips/95404").json()
    assert body["zip"] == "95404" and 38 < body["lat"] < 39
    assert c.get("/v1/zips/10001").status_code == 404


def test_internal_poll_hidden_by_default(client):
    c, _ = client
    assert c.post("/internal/poll").status_code == 404


def test_status_page_served_at_root(client):
    c, _ = client
    resp = c.get("/")
    assert resp.status_code == 200
    assert "OutageWatch" in resp.text
    assert "Not affiliated" in resp.text


def test_slo_summary_percentiles(client):
    c, deps = client
    deps.slo.latencies = [30.0, 60.0, 90.0, 120.0, 500.0]
    body = c.get("/v1/slo").json()
    assert body["count"] == 5
    assert body["within_target"] == 4
    assert body["p50_seconds"] == 90.0
    assert body["max_seconds"] == 500.0


def test_slo_summary_empty(client):
    c, _ = client
    assert c.get("/v1/slo").json() == {"count": 0, "target_seconds": 360}
