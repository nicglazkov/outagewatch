"""Core data types for the watcher engine."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class Item:
    """One watched thing at one point in time (e.g. a power outage).

    `payload` holds normalized feed fields. `eta` is tracked as a first-class
    field because ETA changes are a distinct event type. `geometry` is an
    optional GeoJSON geometry dict (Point or Polygon/MultiPolygon, WGS84).
    `zips` lets feeds without geometry still support area matching.
    """

    id: str
    eta: datetime | None = None
    updated_at: datetime | None = None
    lat: float | None = None
    lon: float | None = None
    geometry: dict[str, Any] | None = None
    zips: frozenset[str] = frozenset()
    payload: dict[str, Any] = field(default_factory=dict)


Snapshot = dict[str, Item]
