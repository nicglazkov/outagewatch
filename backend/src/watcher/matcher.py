"""Subscription matcher: which subscriptions does an item affect?

Two matching modes, chosen per subscription:

- **Area** (a ZIP or region, `precise=False`): notify when the outage is nearby.
  Point-in-polygon buffered by the radius, distance within radius, or the
  subscription's ZIP appearing in the item's ZIP set.
- **Precise** (an exact address, `precise=True`): notify only when the outage's
  footprint actually covers the address, not when it is merely nearby. A
  point-only outage (no polygon) counts only if it sits essentially on the
  address.

A subscription with only a ZIP (no point) can only match by ZIP, and is always
treated as an area subscription.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from shapely.geometry import Point, shape

from watcher.types import Item

EARTH_RADIUS_KM = 6371.0088

# A point-only outage (no polygon footprint) this close to a precise address
# counts as impacting it. Roughly 200 m: a device- or service-level outage.
PRECISE_POINT_KM = 0.2


@dataclass(frozen=True)
class Subscription:
    id: str
    token: str
    zip_code: str | None = None
    lat: float | None = None
    lon: float | None = None
    radius_km: float = 1.0
    precise: bool = False  # an exact address (match only inside the footprint)

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
    if sub.precise and sub.has_point:
        # Address subscription: only when the outage footprint covers the point.
        if item.geometry is not None and _covers_point(item.geometry, sub.lat, sub.lon):
            return True
        # A point-only outage covers no area; count it only if it is essentially
        # at the address. Never match a precise address on nearby distance or ZIP.
        if item.lat is not None and item.lon is not None:
            return haversine_km(sub.lat, sub.lon, item.lat, item.lon) <= PRECISE_POINT_KM
        return False

    # Area subscription (ZIP/region): notify when the outage is nearby.
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


def _covers_point(geometry: dict, lat: float, lon: float) -> bool:
    """True only if the geometry actually contains the point (boundary counts).
    A Point geometry covers nothing but itself, so address matches on real
    outage polygons, not on single-point outages."""
    geom = shape(geometry)
    if not geom.is_valid:
        geom = geom.buffer(0)
    return bool(geom.covers(Point(lon, lat)))


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
