"""Fixture-replay tests using real recorded PG&E feed snapshots (2026-07-09)."""

import copy
import json
from pathlib import Path

from outagewatch.feeds.pge import normalize
from watcher.differ import ChangeType, diff_snapshots

FIXTURES = Path(__file__).parent / "fixtures" / "20260709"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text())


def _features(name: str) -> list[dict]:
    return _load(name).get("features", [])


def _real_snapshot():
    return normalize(
        points=_features("outages_l4.geojson"),
        points_no_poly=[],
        polygons=_features("outages_l8.geojson"),
    )


def test_normalize_real_snapshot():
    snap = _real_snapshot()
    assert len(snap) == 25
    with_polygons = [i for i in snap.values() if i.geometry is not None]
    assert len(with_polygons) >= 20
    sample = next(iter(snap.values()))
    assert sample.lat is not None and 32 < sample.lat < 42
    assert sample.lon is not None and -125 < sample.lon < -114
    assert "OUTAGE_CAUSE" in sample.payload or "CREW_CURRENT_STATUS" in sample.payload


def test_normalize_ids_are_outage_ids_not_objectids():
    snap = _real_snapshot()
    raw_ids = {
        str(f["properties"]["OUTAGE_ID"]) for f in _features("outages_l4.geojson")
    }
    assert set(snap.keys()) == raw_ids


def test_replay_identical_snapshots_no_changes():
    assert diff_snapshots(_real_snapshot(), _real_snapshot()) == []


def test_replay_mutated_snapshot_produces_expected_events():
    old = _real_snapshot()
    features = copy.deepcopy(_features("outages_l4.geojson"))

    removed = features.pop(0)  # -> RESTORED
    features[1]["properties"]["CURRENT_ETOR"] += 2 * 3600 * 1000  # ETA slips 2h
    features[2]["properties"]["EST_CUSTOMERS"] = 9999  # -> UPDATED
    features.append(  # -> STARTED
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-121.5, 38.5]},
            "properties": {"OUTAGE_ID": "test-new-1", "OUTAGE_CAUSE": "UNKNOWN"},
        }
    )

    new = normalize(points=features, points_no_poly=[], polygons=_features("outages_l8.geojson"))
    changes = diff_snapshots(old, new)
    by_type = {}
    for c in changes:
        by_type.setdefault(c.type, []).append(c)

    assert [c.item.id for c in by_type[ChangeType.STARTED]] == ["test-new-1"]
    restored_ids = [c.item.id for c in by_type[ChangeType.RESTORED]]
    assert restored_ids == [str(removed["properties"]["OUTAGE_ID"])]
    eta_changes = by_type[ChangeType.ETA_CHANGED]
    assert len(eta_changes) == 1 and eta_changes[0].eta_delta_minutes == 120.0
    assert any("EST_CUSTOMERS" in c.changed_fields for c in by_type[ChangeType.UPDATED])


def test_layer5_field_variants_normalize():
    """Layer 5 uses F_OUTAGE_ID and OUTAGE_LATITUDE/LONGITUDE + ZIP."""
    raw = _load("outages_l5.json")
    features = [
        {"type": "Feature", "geometry": None, "properties": f["attributes"]}
        for f in raw.get("features", [])
    ]
    snap = normalize(points=[], points_no_poly=features, polygons=[])
    assert len(snap) == len(features)
    item = next(iter(snap.values()))
    assert item.lat is not None and item.lon is not None


def test_psps_flag_marks_items():
    features = _features("outages_l4.geojson")[:2]
    psps_ids = {str(features[0]["properties"]["OUTAGE_ID"])}
    snap = normalize(points=features, points_no_poly=[], polygons=[], psps_ids=psps_ids)
    flags = {i.id: i.payload["is_psps"] for i in snap.values()}
    assert flags[next(iter(psps_ids))] is True
    assert sum(flags.values()) == 1
