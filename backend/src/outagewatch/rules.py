"""Notification discipline. This is the product.

Only four things ever notify:
- outage started at your location
- restoration ETA moved materially (more than 30 minutes either way)
- power restored
- PSPS warning for your area

Quiet hours are respected for everything except PSPS warnings.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time

from watcher.differ import Change, ChangeType

MATERIAL_ETA_SHIFT_MINUTES = 30.0


@dataclass(frozen=True)
class Alert:
    kind: str  # started | eta_changed | restored | psps_warning
    outage_id: str
    title: str
    body: str
    is_psps: bool = False


def alert_for_change(change: Change) -> Alert | None:
    """Map a feed change to an alert, or None if it does not meet the bar."""
    item = change.item
    is_psps = bool(item.payload.get("is_psps"))
    cause = _friendly_cause(item.payload.get("OUTAGE_CAUSE"))
    customers = item.payload.get("EST_CUSTOMERS")

    if change.type is ChangeType.STARTED:
        kind = "psps_warning" if is_psps else "started"
        title = "PSPS outage in your area" if is_psps else "Power outage in your area"
        body_bits = []
        if cause:
            body_bits.append(cause)
        if customers:
            body_bits.append(f"about {customers} customers affected")
        body_bits.append(_eta_phrase(item.eta))
        return Alert(kind, item.id, title, ". ".join(b for b in body_bits if b), is_psps)

    if change.type is ChangeType.ETA_CHANGED:
        delta = change.eta_delta_minutes
        if delta is None:
            # ETA appeared or disappeared; only notify when a real estimate appears.
            if item.eta is None:
                return None
            return Alert(
                "eta_changed",
                item.id,
                "Restoration estimate updated",
                _eta_phrase(item.eta),
                is_psps,
            )
        if abs(delta) <= MATERIAL_ETA_SHIFT_MINUTES:
            return None
        direction = "later" if delta > 0 else "earlier"
        return Alert(
            "eta_changed",
            item.id,
            f"Restoration estimate moved {direction}",
            _eta_phrase(item.eta),
            is_psps,
        )

    if change.type is ChangeType.RESTORED:
        return Alert(
            "restored", item.id, "Power restored", "PG&E reports this outage resolved.", is_psps
        )

    return None  # UPDATED (crew status etc.) never notifies in v1


def in_quiet_hours(now_local: datetime, start: time | None, end: time | None) -> bool:
    """True if now falls in the user's quiet window. Handles windows crossing midnight."""
    if start is None or end is None or start == end:
        return False
    now_t = now_local.time()
    if start < end:
        return start <= now_t < end
    return now_t >= start or now_t < end


def should_send(
    alert: Alert, now_local: datetime, quiet_start: time | None, quiet_end: time | None
) -> bool:
    if alert.is_psps or alert.kind == "psps_warning":
        return True
    return not in_quiet_hours(now_local, quiet_start, quiet_end)


def _eta_phrase(eta: datetime | None) -> str:
    if eta is None:
        return "No restoration estimate yet"
    return f"Estimated restoration {eta.strftime('%b %d %I:%M %p').replace(' 0', ' ')} UTC"


_CAUSE_MAP = {
    "PLNND SHUTDOWN": "Planned shutdown",
    "POLE FIRE": "Pole fire",
    "UNKNOWN": "Cause under investigation",
}


def _friendly_cause(raw: str | None) -> str | None:
    if not raw:
        return None
    return _CAUSE_MAP.get(raw, raw.capitalize())
