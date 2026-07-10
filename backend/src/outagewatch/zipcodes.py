"""CA ZIP (ZCTA) centroid lookup, bundled as a small CSV (Census gazetteer, public domain).

ZIP-only subscriptions get resolved to a centroid point plus an area-derived
radius so they match outages the same way address subscriptions do, instead of
relying on the feed's sparse ZIP fields.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from math import asin, cos, radians, sin, sqrt


@dataclass(frozen=True)
class ZipArea:
    zip_code: str
    lat: float
    lon: float
    radius_km: float


@lru_cache(maxsize=1)
def _table() -> dict[str, ZipArea]:
    table: dict[str, ZipArea] = {}
    ref = resources.files("outagewatch.data").joinpath("ca_zips.csv")
    with ref.open("r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            table[row["zip"]] = ZipArea(
                zip_code=row["zip"],
                lat=float(row["lat"]),
                lon=float(row["lon"]),
                radius_km=float(row["radius_km"]),
            )
    return table


def lookup(zip_code: str) -> ZipArea | None:
    return _table().get(zip_code)


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    return 2 * 6371.0 * asin(sqrt(a))


def nearest(lat: float, lon: float) -> tuple[ZipArea | None, float]:
    """Closest CA ZCTA centroid to a point, with its distance in km.

    Used to place an arbitrary geocoded point (e.g. an autocomplete pick with no
    postcode) into a ZIP so it can subscribe and get a territory check. Distance
    is returned so callers can reject points that fall outside CA coverage.
    """
    best: ZipArea | None = None
    best_km = float("inf")
    for area in _table().values():
        km = _haversine_km(lat, lon, area.lat, area.lon)
        if km < best_km:
            best_km, best = km, area
    return best, best_km
