"""End-to-end pipeline tests: feed change -> diff -> match -> push, with fakes."""

from datetime import UTC, datetime

import pytest

from outagewatch.service import poll_once
from outagewatch.store import StoredSubscription
from tests.fakes import FakeEtaHistory, FakeFeed, FakeSender, FakeSlo, FakeStateStore, FakeSubs
from watcher.types import Item

SANTA_ROSA = (38.44, -122.71)


def _outage(oid="o1", eta_hour=18, lat=SANTA_ROSA[0], lon=SANTA_ROSA[1], **payload) -> Item:
    return Item(
        id=oid,
        eta=datetime(2026, 7, 9, eta_hour, tzinfo=UTC) if eta_hour else None,
        updated_at=datetime(2026, 7, 9, 10, tzinfo=UTC),
        lat=lat,
        lon=lon,
        payload={"OUTAGE_CAUSE": "POLE FIRE", "EST_CUSTOMERS": 40, **payload},
    )


def _sub(sub_id="s1", token="token-abc-123", lat=SANTA_ROSA[0], lon=SANTA_ROSA[1], **kw):
    return StoredSubscription(id=sub_id, token=token, lat=lat, lon=lon, radius_km=2.0, **kw)


@pytest.fixture
def world():
    state = FakeStateStore()
    subs = FakeSubs([_sub()])
    sender = FakeSender()
    history = FakeEtaHistory()
    slo = FakeSlo()
    return state, subs, sender, history, slo


async def _run(feed, world, now=None):
    state, subs, sender, history, slo = world
    return await poll_once(
        feed=feed, state=state, subs=subs, sender=sender, eta_history=history, slo=slo, now=now
    )


@pytest.mark.asyncio
async def test_first_run_stores_but_never_notifies(world):
    outcome = await _run(FakeFeed({"o1": _outage()}), world)
    assert outcome.first_run is True
    assert outcome.pushes_sent == 0
    assert world[0].snapshot is not None


@pytest.mark.asyncio
async def test_new_outage_pushes_to_nearby_subscriber(world):
    state, subs, sender, history, slo = world
    await _run(FakeFeed({}, version="v1"), world)
    outcome = await _run(FakeFeed({"o1": _outage()}, version="v2"), world)
    assert outcome.pushes_sent == 1
    assert sender.sent[0].title == "Power outage in your area"
    assert sender.sent[0].data["outage_id"] == "o1"
    assert slo.events[0]["kind"] == "started"


@pytest.mark.asyncio
async def test_far_away_outage_does_not_push(world):
    await _run(FakeFeed({}, version="v1"), world)
    tahoe = _outage(lat=39.09, lon=-120.03)
    outcome = await _run(FakeFeed({"o1": tahoe}, version="v2"), world)
    assert outcome.alerts == 1
    assert outcome.pushes_sent == 0


@pytest.mark.asyncio
async def test_unchanged_version_skips_fetch(world):
    await _run(FakeFeed({"o1": _outage()}, version="v1"), world)
    outcome = await _run(FakeFeed({"o1": _outage()}, version="v1"), world)
    assert outcome.skipped_unchanged is True


@pytest.mark.asyncio
async def test_eta_slip_records_history_and_pushes(world):
    state, subs, sender, history, slo = world
    await _run(FakeFeed({"o1": _outage(eta_hour=18)}, version="v1"), world)
    outcome = await _run(FakeFeed({"o1": _outage(eta_hour=20)}, version="v2"), world)
    assert outcome.pushes_sent == 1
    assert "later" in sender.sent[0].title
    assert len(history.get("o1")) == 1


@pytest.mark.asyncio
async def test_restore_pushes(world):
    state, subs, sender, history, slo = world
    await _run(FakeFeed({"o1": _outage()}, version="v1"), world)
    outcome = await _run(FakeFeed({}, version="v2"), world)
    assert outcome.pushes_sent == 1
    assert sender.sent[0].title == "Power restored"


@pytest.mark.asyncio
async def test_quiet_hours_suppress_push_but_not_psps():
    state = FakeStateStore()
    subs = FakeSubs(
        [
            _sub(sub_id="s1", token="tok-normal-1", quiet_start="22:00", quiet_end="07:00"),
            _sub(sub_id="s2", token="tok-psps-2", quiet_start="22:00", quiet_end="07:00"),
        ]
    )
    sender = FakeSender()
    world = (state, subs, sender, FakeEtaHistory(), FakeSlo())
    # 3am Pacific = 10:00 UTC in July (PDT)
    night = datetime(2026, 7, 9, 10, 0, tzinfo=UTC)

    await _run(FakeFeed({}, version="v1"), world, now=night)
    normal, psps = _outage(oid="normal"), _outage(oid="psps", is_psps=True)
    outcome = await _run(FakeFeed({"normal": normal, "psps": psps}, version="v2"), world, now=night)
    # each sub matches both outages; only the PSPS one may break quiet hours
    assert outcome.pushes_sent == 2
    assert all(m.data["kind"] == "psps_warning" for m in sender.sent)


@pytest.mark.asyncio
async def test_dead_tokens_are_pruned(world):
    state, subs, sender, history, slo = world
    dead_sender = FakeSender(dead_tokens={"token-abc-123"})
    world = (state, subs, dead_sender, history, slo)
    await _run(FakeFeed({}, version="v1"), world)
    outcome = await _run(FakeFeed({"o1": _outage()}, version="v2"), world)
    assert outcome.pushes_sent == 0
    assert subs.list_all() == []


@pytest.mark.asyncio
async def test_precise_subscriber_only_pushes_when_covered(world):
    """An address-only (precise) sub is the app's default: it ignores nearby
    outages and fires only when the outage is essentially at the address."""
    state, subs, sender, history, slo = world
    subs.subs.clear()
    subs.upsert(_sub(sub_id="a1", precise=True))
    await _run(FakeFeed({}, version="v1"), world)

    # ~1.3 km away: inside the 2 km radius, but not covering the address.
    nearby = _outage(lat=38.452, lon=-122.71)
    out1 = await _run(FakeFeed({"o1": nearby}, version="v2"), world)
    assert out1.pushes_sent == 0

    # A new outage at the address itself: covered, so it does push.
    at_home = _outage(oid="o2", lat=SANTA_ROSA[0], lon=SANTA_ROSA[1])
    out2 = await _run(FakeFeed({"o1": nearby, "o2": at_home}, version="v3"), world)
    assert out2.pushes_sent == 1


@pytest.mark.asyncio
async def test_zip_only_subscriber_matches_via_resolved_point(world):
    """A ZIP sub stored with centroid lat/lon (as the API creates it) matches nearby outages."""
    state, subs, sender, history, slo = world
    subs.subs.clear()
    subs.upsert(
        StoredSubscription(
            id="z1",
            token="tok-zip-1",
            zip_code="95404",
            lat=38.52573,
            lon=-122.69044,
            radius_km=9.88,
        )
    )
    await _run(FakeFeed({}, version="v1"), world)
    outcome = await _run(FakeFeed({"o1": _outage()}, version="v2"), world)
    assert outcome.pushes_sent == 1
