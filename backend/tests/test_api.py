"""API tests with faked dependencies."""

from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from outagewatch.api import main as api_main
from tests.fakes import FakeEtaHistory, FakeSlo, FakeStateStore, FakeSubs
from watcher.types import Item

# A realistic-length FCM token (the API now rejects short/junk tokens).
TOKEN = "d" * 152


class FakeDeps:
    def __init__(self):
        self.state = FakeStateStore()
        self.subs = FakeSubs()
        self.eta_history = FakeEtaHistory()
        self.slo = FakeSlo()


@pytest.fixture
def client():
    # Reset the module-level rate limiters so tests don't share buckets.
    for lim in (
        api_main._SUBSCRIBE_LIMIT, api_main._EXPLAIN_LIMIT, api_main._GEOCODE_LIMIT,
        api_main._READ_LIMIT, api_main._DELETE_LIMIT,
    ):
        lim._hits.clear()
    api_main._slo_cache = None  # don't leak a cached summary across tests
    api_main._snapshot_cache = None  # nor a cached snapshot
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


class FakeGeocoder:
    def __init__(self, hits):
        self.hits = hits
        self.calls = []

    async def autocomplete(self, q, lat=None, lon=None, limit=6):
        self.calls.append((q, lat, lon))
        return self.hits


def test_healthz(client):
    c, _ = client
    assert c.get("/healthz").json() == {"status": "ok"}


def test_autocomplete_endpoint_returns_suggestions(client):
    c, deps = client
    from outagewatch.geocode import Suggestion

    deps.geocoder = FakeGeocoder(
        [
            Suggestion(
                id="N1",
                title="1017 Pacific Avenue",
                subtitle="Santa Rosa, California, 95404",
                lat=38.44,
                lon=-122.71,
                zip_code="95404",
                pge=True,
                served_by=None,
            )
        ]
    )
    resp = c.get(
        "/v1/geocode/autocomplete", params={"q": "1017 pac", "lat": 38.4, "lon": -122.7}
    )
    body = resp.json()
    assert len(body) == 1
    assert body[0]["title"] == "1017 Pacific Avenue"
    assert body[0]["zip"] == "95404"
    assert body[0]["pge"] is True
    assert deps.geocoder.calls == [("1017 pac", 38.4, -122.7)]


def test_list_outages_near_point(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage(), "far": _outage(oid="far", lat=41.5, lon=-120.5)}
    resp = c.get("/v1/outages", params={"lat": 38.45, "lon": -122.71, "radius_km": 10})
    ids = [o["id"] for o in resp.json()]
    assert ids == ["o1"]
    body = resp.json()[0]
    assert body["cause"] == "Pole fire"  # humanized from "POLE FIRE"
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
        json={"token": TOKEN, "zip_code": "95404", "platform": "android"},
    )
    assert resp.status_code == 201
    sub = deps.subs.list_all()[0]
    assert sub.lat is not None and sub.radius_km >= 9
    assert sub.zip_code == "95404"


def test_create_subscription_rejects_no_location(client):
    c, _ = client
    resp = c.post("/v1/subscriptions", json={"token": TOKEN})
    assert resp.status_code == 422


def test_create_subscription_rejects_unknown_zip(client):
    c, _ = client
    resp = c.post("/v1/subscriptions", json={"token": TOKEN, "zip_code": "10001"})
    assert resp.status_code == 422


def test_subscription_lifecycle(client):
    c, deps = client
    sub_id = c.post(
        "/v1/subscriptions",
        json={"token": TOKEN, "lat": 38.44, "lon": -122.71, "radius_km": 2},
    ).json()["id"]
    assert [s.id for s in deps.subs.list_all()] == [sub_id]
    r = c.delete(f"/v1/subscriptions/{sub_id}", headers={"X-Device-Token": TOKEN})
    assert r.status_code == 204
    assert deps.subs.list_all() == []


def test_delete_requires_owning_token(client):
    c, deps = client
    sub_id = c.post(
        "/v1/subscriptions",
        json={"token": TOKEN, "lat": 38.44, "lon": -122.71, "radius_km": 2},
    ).json()["id"]
    # No token, or a different device's token, must not delete someone's sub.
    assert c.delete(f"/v1/subscriptions/{sub_id}").status_code == 404
    assert c.delete(
        f"/v1/subscriptions/{sub_id}", headers={"X-Device-Token": "e" * 152}
    ).status_code == 404
    assert deps.subs.list_all() != []  # still there
    # The owning token succeeds.
    assert c.delete(
        f"/v1/subscriptions/{sub_id}", headers={"X-Device-Token": TOKEN}
    ).status_code == 204


def test_snapshot_is_cached_across_reads(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage()}
    assert c.get("/v1/outages", params={"zip": "95404"}).status_code == 200
    assert c.get("/v1/outages/o1").status_code == 200
    # Two reads, but the statewide blob is downloaded from "GCS" only once.
    assert deps.state.load_calls == 1


def test_outages_requires_a_filter(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage()}
    # No lat/lon and no zip must NOT dump the whole snapshot.
    assert c.get("/v1/outages").json() == []


def test_outages_rejects_partial_coords(client):
    c, _ = client
    assert c.get("/v1/outages", params={"lat": 38.4}).status_code == 400


def test_outages_rejects_out_of_range_coords(client):
    c, _ = client
    # Out-of-CA / non-finite coords are rejected by validation, not a 500.
    assert c.get("/v1/outages", params={"lat": 99, "lon": -121}).status_code == 422
    assert c.get("/v1/outages", params={"lat": "inf", "lon": -121}).status_code == 422


def test_subscription_cap_per_token(client):
    c, _ = client
    for _ in range(api_main.MAX_SUBS_PER_TOKEN):
        r = c.post("/v1/subscriptions", json={"token": TOKEN, "zip_code": "95404"})
        assert r.status_code == 201
    over = c.post("/v1/subscriptions", json={"token": TOKEN, "zip_code": "95404"})
    assert over.status_code == 429


def test_short_token_rejected(client):
    c, _ = client
    resp = c.post("/v1/subscriptions", json={"token": "short", "zip_code": "95404"})
    assert resp.status_code == 422


def test_delete_rejects_malformed_id(client):
    c, _ = client
    assert c.delete("/v1/subscriptions/not-a-uuid").status_code == 404
    assert c.delete("/v1/subscriptions/__reserved__").status_code == 404


def test_list_subscriptions_endpoint_removed(client):
    c, _ = client
    # The list endpoint is gone; only POST remains, so GET is 405 (not readable).
    assert c.get("/v1/subscriptions", params={"token": TOKEN}).status_code == 405


def test_openapi_docs_disabled(client):
    c, _ = client
    assert c.get("/openapi.json").status_code == 404
    assert c.get("/docs").status_code == 404


def test_zip_lookup_endpoint(client):
    c, _ = client
    body = c.get("/v1/zips/95404").json()
    assert body["zip"] == "95404" and 38 < body["lat"] < 39
    assert body["pge"] is True and body["served_by"] is None
    assert c.get("/v1/zips/10001").status_code == 404


def test_zip_lookup_flags_non_pge_territory(client):
    c, _ = client
    body = c.get("/v1/zips/95050").json()  # Santa Clara / SVP
    assert body["pge"] is False
    assert "Silicon Valley Power" in body["served_by"]


def test_outage_cause_is_humanized(client):
    c, deps = client
    item = _outage()
    item.payload["OUTAGE_CAUSE"] = "BRKN UG EQUIPMNT"
    deps.state.snapshot = {"o1": item}
    body = c.get("/v1/outages", params={"lat": 38.45, "lon": -122.71}).json()
    assert body[0]["cause"] == "Broken underground equipment"


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


def test_statewide_returns_all_outages(client):
    c, deps = client
    deps.state.snapshot = {"o1": _outage(), "o2": _outage(oid="o2", lat=39.5, lon=-121.5)}
    body = c.get("/v1/statewide").json()
    assert {o["id"] for o in body} == {"o1", "o2"}
    assert body[0]["geometry"] is not None  # include_geometry defaults to true


def test_web_pages_and_workers_served(client):
    c, _ = client
    for path in ("/", "/statewide", "/privacy", "/terms", "/widget"):
        assert c.get(path).status_code == 200, path
    sw = c.get("/sw.js")
    assert sw.status_code == 200 and "javascript" in sw.headers["content-type"]
    assert c.get("/firebase-messaging-sw.js").headers.get("service-worker-allowed") == "/"
    assert c.get("/static/js/app.js").status_code == 200
    assert c.get("/static/manifest.webmanifest").status_code == 200


def test_slo_is_rate_limited(client):
    c, _ = client
    # /v1/slo hits Firestore per call, so it must be throttled like other reads.
    codes = {c.get("/v1/slo").status_code for _ in range(api_main._READ_LIMIT.max + 5)}
    assert 429 in codes


def test_slo_caches_firestore_reads(client):
    c, deps = client
    deps.slo.latencies = [30.0, 60.0]
    first = c.get("/v1/slo").json()
    # A second call within the TTL must serve from cache, not re-read the store.
    deps.slo.latencies = [999.0]  # would change the answer if it were re-read
    second = c.get("/v1/slo").json()
    assert first == second
    assert deps.slo.read_calls == 1


def test_zip_lookup_is_rate_limited(client):
    c, _ = client
    codes = {c.get("/v1/zips/95404").status_code for _ in range(api_main._READ_LIMIT.max + 5)}
    assert 429 in codes
