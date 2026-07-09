"""Differ unit tests over synthetic outage sequences: new, grow, merge, ETA slip, restore."""

from datetime import UTC, datetime

from watcher.differ import ChangeType, diff_snapshots
from watcher.types import Item


def _dt(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 7, 9, hour, minute, tzinfo=UTC)


def _item(oid: str, eta_hour: int | None = 18, customers: int = 10, **payload) -> Item:
    return Item(
        id=oid,
        eta=_dt(eta_hour) if eta_hour is not None else None,
        lat=38.0,
        lon=-122.0,
        payload={"EST_CUSTOMERS": customers, **payload},
    )


def test_new_outage_emits_started():
    changes = diff_snapshots({}, {"1": _item("1")})
    assert [c.type for c in changes] == [ChangeType.STARTED]
    assert changes[0].item.id == "1"


def test_unchanged_outage_emits_nothing():
    a = {"1": _item("1")}
    assert diff_snapshots(a, {"1": _item("1")}) == []


def test_grow_emits_updated_with_field():
    old = {"1": _item("1", customers=10)}
    new = {"1": _item("1", customers=250)}
    changes = diff_snapshots(old, new)
    assert [c.type for c in changes] == [ChangeType.UPDATED]
    assert "EST_CUSTOMERS" in changes[0].changed_fields


def test_eta_slip_emits_eta_changed_with_delta():
    old = {"1": _item("1", eta_hour=18)}
    new = {"1": _item("1", eta_hour=20)}
    changes = diff_snapshots(old, new)
    assert [c.type for c in changes] == [ChangeType.ETA_CHANGED]
    assert changes[0].eta_delta_minutes == 120.0


def test_eta_appears_from_none():
    old = {"1": _item("1", eta_hour=None)}
    new = {"1": _item("1", eta_hour=18)}
    changes = diff_snapshots(old, new)
    assert [c.type for c in changes] == [ChangeType.ETA_CHANGED]
    assert changes[0].eta_delta_minutes is None


def test_restore_emits_restored():
    changes = diff_snapshots({"1": _item("1")}, {})
    assert [c.type for c in changes] == [ChangeType.RESTORED]
    assert changes[0].item.id == "1"


def test_merge_two_outages_into_one():
    """Feed merges outages 1+2 into new outage 3: v1 semantics = 2x RESTORED + 1x STARTED."""
    old = {"1": _item("1"), "2": _item("2")}
    new = {"3": _item("3", customers=500)}
    changes = diff_snapshots(old, new)
    kinds = [c.type for c in changes]
    assert kinds.count(ChangeType.RESTORED) == 2
    assert kinds.count(ChangeType.STARTED) == 1
    assert len(kinds) == 3


def test_eta_and_payload_change_in_same_cycle_emit_both():
    old = {"1": _item("1", eta_hour=18, customers=10)}
    new = {"1": _item("1", eta_hour=19, customers=99)}
    changes = diff_snapshots(old, new)
    assert {c.type for c in changes} == {ChangeType.ETA_CHANGED, ChangeType.UPDATED}
