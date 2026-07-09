"""Item/Snapshot JSON serialization for state persistence."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from watcher.types import Item, Snapshot


def item_to_dict(item: Item) -> dict[str, Any]:
    return {
        "id": item.id,
        "eta": item.eta.isoformat() if item.eta else None,
        "updated_at": item.updated_at.isoformat() if item.updated_at else None,
        "lat": item.lat,
        "lon": item.lon,
        "geometry": item.geometry,
        "zips": sorted(item.zips),
        "payload": item.payload,
    }


def item_from_dict(data: dict[str, Any]) -> Item:
    return Item(
        id=data["id"],
        eta=_parse_dt(data.get("eta")),
        updated_at=_parse_dt(data.get("updated_at")),
        lat=data.get("lat"),
        lon=data.get("lon"),
        geometry=data.get("geometry"),
        zips=frozenset(data.get("zips") or ()),
        payload=data.get("payload") or {},
    )


def snapshot_to_dict(snapshot: Snapshot) -> dict[str, Any]:
    return {"items": [item_to_dict(i) for i in snapshot.values()]}


def snapshot_from_dict(data: dict[str, Any]) -> Snapshot:
    items = [item_from_dict(d) for d in data.get("items", [])]
    return {i.id: i for i in items}


def _parse_dt(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None
