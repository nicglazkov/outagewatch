"""Snapshot serialization round-trip."""

from datetime import UTC, datetime

from watcher.serde import snapshot_from_dict, snapshot_to_dict
from watcher.types import Item


def test_round_trip_preserves_everything():
    snap = {
        "a": Item(
            id="a",
            eta=datetime(2026, 7, 9, 18, tzinfo=UTC),
            updated_at=datetime(2026, 7, 9, 10, tzinfo=UTC),
            lat=38.4,
            lon=-122.7,
            geometry={"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]},
            zips=frozenset({"95404", "95405"}),
            payload={"OUTAGE_CAUSE": "POLE FIRE", "is_psps": False},
        ),
        "b": Item(id="b"),
    }
    assert snapshot_from_dict(snapshot_to_dict(snap)) == snap
