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
