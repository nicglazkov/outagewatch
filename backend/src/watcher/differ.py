"""State differ: compare two snapshots and emit change events.

Merge/split semantics: when a utility merges two outages into one, the old ids
disappear and a new id appears. v1 reports that as RESTORED + STARTED. That is
honest about what the feed says, and downstream notification rules can suppress
a RESTORED immediately followed by a STARTED covering the same area if it ever
proves confusing in practice.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from watcher.types import Item, Snapshot


class ChangeType(StrEnum):
    STARTED = "started"
    UPDATED = "updated"
    ETA_CHANGED = "eta_changed"
    RESTORED = "restored"


@dataclass(frozen=True)
class Change:
    type: ChangeType
    item: Item
    previous: Item | None = None
    changed_fields: frozenset[str] = frozenset()

    @property
    def eta_delta_minutes(self) -> float | None:
        """Signed ETA shift in minutes (positive = slipped later). None unless both ETAs known."""
        if self.previous is None or self.previous.eta is None or self.item.eta is None:
            return None
        return (self.item.eta - self.previous.eta).total_seconds() / 60.0


def diff_snapshots(old: Snapshot, new: Snapshot) -> list[Change]:
    """Compute ordered change events between two snapshots.

    Emits at most one UPDATED and one ETA_CHANGED per item per cycle:
    ETA movement is its own event type; any other payload/geometry change is
    a single UPDATED with the changed field names.
    """
    changes: list[Change] = []

    for item_id, item in new.items():
        prev = old.get(item_id)
        if prev is None:
            changes.append(Change(ChangeType.STARTED, item))
            continue
        if item.eta != prev.eta:
            changes.append(
                Change(ChangeType.ETA_CHANGED, item, prev, changed_fields=frozenset({"eta"}))
            )
        changed = _changed_fields(prev, item)
        if changed:
            changes.append(Change(ChangeType.UPDATED, item, prev, changed_fields=changed))

    for item_id, prev in old.items():
        if item_id not in new:
            changes.append(Change(ChangeType.RESTORED, prev, prev))

    return changes


def _changed_fields(prev: Item, curr: Item) -> frozenset[str]:
    changed: set[str] = set()
    keys = prev.payload.keys() | curr.payload.keys()
    changed.update(k for k in keys if prev.payload.get(k) != curr.payload.get(k))
    if (prev.lat, prev.lon) != (curr.lat, curr.lon):
        changed.add("location")
    if prev.geometry != curr.geometry:
        changed.add("geometry")
    if prev.zips != curr.zips:
        changed.add("zips")
    return frozenset(changed)
