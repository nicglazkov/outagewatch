"""Notification rule tests: material ETA threshold, quiet hours, PSPS override."""

from datetime import UTC, datetime, time

from outagewatch.rules import Alert, _eta_phrase, alert_for_change, in_quiet_hours, should_send
from watcher.differ import Change, ChangeType
from watcher.types import Item


def _dt(hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 7, 9, hour, minute, tzinfo=UTC)


def _item(eta=None, **payload) -> Item:
    return Item(id="1", eta=eta, payload=payload)


def test_started_alert_includes_cause_and_customers():
    change = Change(ChangeType.STARTED, _item(OUTAGE_CAUSE="POLE FIRE", EST_CUSTOMERS=120))
    alert = alert_for_change(change)
    assert alert.kind == "started"
    assert "Pole fire" in alert.body
    assert "120" in alert.body


def test_psps_started_becomes_psps_warning():
    change = Change(ChangeType.STARTED, _item(is_psps=True))
    assert alert_for_change(change).kind == "psps_warning"


def test_small_eta_shift_is_silent():
    change = Change(
        ChangeType.ETA_CHANGED, _item(eta=_dt(18, 20)), _item(eta=_dt(18, 0)), frozenset({"eta"})
    )
    assert alert_for_change(change) is None


def test_material_eta_slip_notifies_later():
    change = Change(
        ChangeType.ETA_CHANGED, _item(eta=_dt(19, 1)), _item(eta=_dt(18, 0)), frozenset({"eta"})
    )
    alert = alert_for_change(change)
    assert alert.kind == "eta_changed"
    assert "later" in alert.title


def test_material_eta_improvement_notifies_earlier():
    change = Change(
        ChangeType.ETA_CHANGED, _item(eta=_dt(17, 0)), _item(eta=_dt(18, 0)), frozenset({"eta"})
    )
    assert "earlier" in alert_for_change(change).title


def test_eta_disappearing_is_silent():
    change = Change(ChangeType.ETA_CHANGED, _item(eta=None), _item(eta=_dt(18)), frozenset({"eta"}))
    assert alert_for_change(change) is None


def test_updated_never_notifies():
    change = Change(
        ChangeType.UPDATED,
        _item(CREW_CURRENT_STATUS="Crew On Site"),
        _item(),
        frozenset({"CREW_CURRENT_STATUS"}),
    )
    assert alert_for_change(change) is None


def test_restored_notifies():
    assert alert_for_change(Change(ChangeType.RESTORED, _item(), _item())).kind == "restored"


def test_eta_phrase_is_pacific_not_utc():
    now = datetime(2026, 7, 9, 12, 0, tzinfo=UTC)
    # 23:00 UTC is 4:00 PM Pacific (PDT), and there is no "UTC" label.
    assert _eta_phrase(_dt(23, 0), now) == "Estimated restoration Jul 9 4:00 PM"


def test_eta_phrase_flags_past_estimate():
    now = datetime(2026, 7, 9, 20, 0, tzinfo=UTC)
    phrase = _eta_phrase(_dt(18, 0), now)  # 18:00 is before now
    assert "passed" in phrase
    assert "PM" not in phrase  # no stale future-looking timestamp


def test_quiet_hours_same_day_window():
    assert in_quiet_hours(_dt(23), time(22), time(7)) is True
    assert in_quiet_hours(_dt(3), time(22), time(7)) is True
    assert in_quiet_hours(_dt(12), time(22), time(7)) is False


def test_quiet_hours_disabled_when_unset():
    assert in_quiet_hours(_dt(3), None, None) is False


def test_quiet_hours_suppress_normal_but_not_psps():
    normal = Alert("started", "1", "t", "b")
    psps = Alert("psps_warning", "1", "t", "b", is_psps=True)
    at_3am = _dt(3)
    assert should_send(normal, at_3am, time(22), time(7)) is False
    assert should_send(psps, at_3am, time(22), time(7)) is True
