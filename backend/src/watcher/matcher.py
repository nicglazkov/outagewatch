"""Subscription matcher: which subscriptions does an item affect?

Matching precedence per subscription:
1. Point-in-polygon: subscription point inside the item's polygon (buffered by
   the subscription radius).
2. Distance: subscription point within radius_km of the item's point.
3. ZIP containment: subscription ZIP appears in the item's ZIP set.

A subscription matches if any applicable rule matches. Subscriptions with only
a ZIP (no point) can only match by ZIP.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from shapely.geometry import Point, shape

from watcher.types import Item

EARTH_RADIUS_KM = 6371.0088


@dataclass(frozen=True)
class Subscription:
    id: str
    token: str
    zip_code: str | None = None
    lat: float | None = None
    lon: float | None = None
    radius_km: float = 1.0

    @property
    def has_point(self) -> bool:
        return self.lat is not None and self.lon is not None


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def item_matches(item: Item, sub: Subscription) -> bool:
    if sub.has_point:
        if item.geometry is not None and _point_in_geometry(sub, item):
            return True
        if (
            item.lat is not None
            and item.lon is not None
            and haversine_km(sub.lat, sub.lon, item.lat, item.lon) <= sub.radius_km
        ):
            return True
    return bool(sub.zip_code and sub.zip_code in item.zips)


def match_subscriptions(item: Item, subs: list[Subscription]) -> list[Subscription]:
    return [s for s in subs if item_matches(item, s)]


def _point_in_geometry(sub: Subscription, item: Item) -> bool:
    geom = shape(item.geometry)
    if not geom.is_valid:
        geom = geom.buffer(0)
    pt = Point(sub.lon, sub.lat)
    if geom.covers(pt):
        return True
    # Respect the alert radius against the polygon edge too: degrees are a poor
    # distance unit, so approximate km -> degrees at the subscription latitude.
    if sub.radius_km > 0:
        deg = sub.radius_km / 111.32
        return geom.distance(pt) <= deg
    return False
