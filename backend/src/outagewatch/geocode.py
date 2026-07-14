"""Address autocomplete from two free, keyless sources, merged.

- Photon (photon.komoot.io), an OpenStreetMap type-ahead geocoder, handles
  partial input, place names, and businesses.
- The US Census geocoder (TIGER address ranges) covers house-numbered street
  addresses that OSM is missing, which is common for rural roads.

We proxy both so the app only ever talks to our own API, bias results toward
PG&E territory, keep them to California, and resolve each to a ZIP + territory
status up front so that picking one is instant (no second round trip, no
on-device geocoding).
"""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass

import httpx

from outagewatch import zipcodes
from outagewatch.territory import non_pge_utility

logger = logging.getLogger(__name__)

USER_AGENT = (
    "outagewatch/0.1 (unaffiliated outage alerts; "
    "contact: https://github.com/nicglazkov/outagewatch)"
)
PHOTON_URL = "https://photon.komoot.io/api/"
CENSUS_URL = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress"

# Bias toward the middle of PG&E territory when the caller gives no location.
_BIAS_LAT = 38.0
_BIAS_LON = -121.5

# A point with no California state label is accepted only if a ZCTA centroid is
# this close, which keeps out-of-state hits out of an app that only covers CA.
_MAX_SNAP_KM = 12.0


@dataclass(frozen=True)
class Suggestion:
    id: str
    title: str
    subtitle: str
    lat: float
    lon: float
    zip_code: str | None
    pge: bool
    served_by: str | None


class Photon:
    """Address suggestions from Photon + the Census geocoder, reusing one client."""

    def __init__(self, client: httpx.AsyncClient | None = None):
        self._client = client or httpx.AsyncClient(
            headers={"User-Agent": USER_AGENT}, timeout=6.0
        )

    async def autocomplete(
        self,
        query: str,
        lat: float | None = None,
        lon: float | None = None,
        limit: int = 6,
    ) -> list[Suggestion]:
        q = " ".join(query.split())
        if len(q) < 3:
            return []
        # Query both sources at once; Census exact street matches rank first so a
        # fully typed rural address OSM lacks lands on top, not buried under fuzz.
        photon_hits, census_hits = await asyncio.gather(
            self._photon(q, lat, lon, limit),
            self._census(q),
        )
        out: list[Suggestion] = []
        seen: set[tuple[str, str | None]] = set()
        for s in census_hits + photon_hits:
            key = _dedup_key(s)
            if key in seen:
                continue
            seen.add(key)
            out.append(s)
            if len(out) >= limit:
                break
        return out

    async def _photon(
        self, q: str, lat: float | None, lon: float | None, limit: int
    ) -> list[Suggestion]:
        params = {
            "q": q,
            "limit": str(limit * 3),  # over-fetch, then filter to CA
            "lat": str(lat if lat is not None else _BIAS_LAT),
            "lon": str(lon if lon is not None else _BIAS_LON),
            "lang": "en",
        }
        try:
            resp = await self._client.get(PHOTON_URL, params=params)
            resp.raise_for_status()
            features = resp.json().get("features", [])
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("photon autocomplete failed: %s", exc)
            return []
        out: list[Suggestion] = []
        for feat in features:
            s = to_suggestion(feat)
            if s is not None:
                out.append(s)
        return out

    async def _census(self, q: str) -> list[Suggestion]:
        # The Census oneline geocoder needs a house-numbered street address; it
        # doesn't do prefix/place matching, so skip anything not starting with a
        # number. Bias to CA (the app's territory) so a bare street resolves.
        first = q.split()[0] if q.split() else ""
        if not first[:1].isdigit():
            return []
        params = {
            "address": _census_query(q),
            "benchmark": "Public_AR_Current",
            "format": "json",
        }
        try:
            resp = await self._client.get(CENSUS_URL, params=params)
            resp.raise_for_status()
            matches = resp.json()["result"]["addressMatches"]
        except (httpx.HTTPError, ValueError, KeyError) as exc:
            logger.warning("census geocode failed: %s", exc)
            return []
        out: list[Suggestion] = []
        for match in matches[:4]:
            s = census_to_suggestion(match)
            if s is not None:
                out.append(s)
        return out

    async def aclose(self) -> None:
        await self._client.aclose()


def _dedup_key(s: Suggestion) -> tuple[str, str | None]:
    normalized = re.sub(r"[^a-z0-9 ]", "", s.title.lower()).strip()
    return normalized, s.zip_code


def _census_query(q: str) -> str:
    tokens = q.lower().replace(",", " ").split()
    if "ca" in tokens or "california" in tokens:
        return q
    return f"{q}, CA"


def _titlecase_street(text: str) -> str:
    titled = text.title()
    # .title() mangles ordinals: "23Rd" -> "23rd", "1St" -> "1st".
    return re.sub(r"(\d)(St|Nd|Rd|Th)\b", lambda m: m.group(1) + m.group(2).lower(), titled)


def census_to_suggestion(match: dict) -> Suggestion | None:
    """Normalize one Census addressMatch, snapping to a CA ZIP + territory."""
    coords = match.get("coordinates") or {}
    lat, lon = coords.get("y"), coords.get("x")
    matched = match.get("matchedAddress") or ""
    if lat is None or lon is None or not matched:
        return None
    lat, lon = float(lat), float(lon)
    # "123 MAIN ST, SANTA ROSA, CA, 95404" -> [street, city, state, zip]
    parts = [p.strip() for p in matched.split(",")]
    street = _titlecase_street(parts[0]) if parts else _titlecase_street(matched)
    city = parts[1].title() if len(parts) > 1 else ""
    zip_code = parts[-1][:5] if parts and parts[-1][:5].isdigit() else None
    if zip_code is None or zipcodes.lookup(zip_code) is None:
        area, km = zipcodes.nearest(lat, lon)
        # Reject a hit that snaps too far from any CA ZIP: it's out of coverage
        # (e.g. a Reno/Tahoe-border address that Census matched via ", CA").
        if area is None or km > _MAX_SNAP_KM:
            return None
        zip_code = area.zip_code
    utility = non_pge_utility(zip_code) if zip_code else None
    subtitle = ", ".join(p for p in (city, "California", zip_code) if p)
    return Suggestion(
        id=f"census:{lat:.5f},{lon:.5f}",
        title=street,
        subtitle=subtitle,
        lat=lat,
        lon=lon,
        zip_code=zip_code,
        pge=utility is None,
        served_by=utility,
    )


def to_suggestion(feature: dict) -> Suggestion | None:
    """Normalize one Photon GeoJSON feature, or None if it's outside CA coverage."""
    props = feature.get("properties") or {}
    geom = feature.get("geometry") or {}
    coords = geom.get("coordinates")
    if geom.get("type") != "Point" or not coords:
        return None
    lon, lat = float(coords[0]), float(coords[1])

    state = props.get("state")
    country = props.get("country")
    countrycode = props.get("countrycode")
    if state and state != "California":
        return None
    if country and country != "United States":
        return None
    if countrycode and countrycode != "US":
        return None

    # Snap to a ZIP so we can subscribe and run the territory check.
    zip_code = _clean_zip(props.get("postcode"))
    if zip_code is None or zipcodes.lookup(zip_code) is None:
        area, km = zipcodes.nearest(lat, lon)
        # Trust an explicit California label even if centroids are sparse (rural);
        # otherwise require a nearby centroid so out-of-state hits drop out.
        if area is not None and (state == "California" or km <= _MAX_SNAP_KM):
            zip_code = area.zip_code
        elif state != "California":
            return None

    utility = non_pge_utility(zip_code) if zip_code else None
    title, subtitle = _labels(props)
    osm = f"{props.get('osm_type', '')}{props.get('osm_id', '')}"
    return Suggestion(
        id=osm or f"{lat:.5f},{lon:.5f}",
        title=title,
        subtitle=subtitle,
        lat=lat,
        lon=lon,
        zip_code=zip_code,
        pge=utility is None,
        served_by=utility,
    )


def _clean_zip(postcode: str | None) -> str | None:
    if not postcode:
        return None
    five = postcode.split("-")[0].strip()[:5]
    return five if len(five) == 5 and five.isdigit() else None


def _labels(props: dict) -> tuple[str, str]:
    """A Google-Maps-style two-line label: street/place, then city/state/ZIP."""
    name = props.get("name")
    housenumber = props.get("housenumber")
    street = props.get("street")
    if housenumber and street:
        line1 = f"{housenumber} {street}"
    elif street and not name:
        line1 = street
    elif name:
        line1 = name
    else:
        line1 = props.get("city") or props.get("county") or "Result"

    parts: list[str] = []
    for key in ("city", "state"):
        value = props.get(key)
        if value and value != line1 and value not in parts:
            parts.append(value)
    postcode = _clean_zip(props.get("postcode"))
    if postcode:
        parts.append(postcode)
    return line1, ", ".join(parts)
