"""PG&E outage feed adapter.

PG&E's public outage map is an Esri ArcGIS stack. We query the MapServer for
point and polygon features as GeoJSON in WGS84 and normalize them into watcher
Items. A cheap status.json is checked first so unchanged feeds cost one tiny
request instead of several layer queries.

This reads public, read-only data. OutageWatch is not affiliated with PG&E.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx

from watcher.types import Item, Snapshot

logger = logging.getLogger(__name__)

USER_AGENT = "outagewatch/0.1 (unaffiliated outage alerts; contact: nic@glazkov.com)"

AGS_BASE = "https://ags.pge.esriemcs.com/arcgis/rest/services/43/outages/MapServer"
STATUS_URL = "https://outages.pge.esriemcs.com/arcgis/rest/services/43/outages/status.json"
PSPS_EVENTS_URL = "https://psps.pge.esriemcs.com/arcgis/rest/services/43/public/psps/events.json"

LAYER_POINTS = 4  # Outage Locations (points linked to polygons)
LAYER_POINTS_NO_POLY = 5  # Outage Locations without Polygons (has CITY/COUNTY/ZIP)
LAYER_POLYGONS = 8  # Outage Polygon
LAYER_PSPS_POINTS = 2  # PSPS Outage Locations
LAYER_PSPS_POINTS_NO_POLY = 3
LAYER_PSPS_POLYGONS = 7  # Public Safety Outage Polygon

# Feed fields worth keeping on the normalized item, in feed spelling.
_PAYLOAD_FIELDS = (
    "OUTAGE_CAUSE",
    "CREW_CURRENT_STATUS",
    "EST_CUSTOMERS",
    "OUTAGE_START_TEXT",
    "CITY",
    "COUNTY",
    "OUTAGE_EXTENT",
    "OUTAGE_DEVICE_ID",
    "OUTAGE_CIRCUIT_ID",
    "CREW_ETA",
    "fts_flag",
)


def _query_url(layer: int) -> str:
    cache_buster = uuid.uuid4().hex
    return (
        f"{AGS_BASE}/{layer}/query"
        f"?where=1%3D1&outFields=*&outSR=4326&f=geojson&_={cache_buster}"
    )


class PgeFeed:
    """Fetches and normalizes PG&E outage data."""

    def __init__(self, client: httpx.AsyncClient | None = None):
        self._client = client or httpx.AsyncClient(
            headers={"User-Agent": USER_AGENT}, timeout=30.0
        )

    async def fetch_status_version(self) -> str | None:
        """Feed version stamp; changes whenever outage data updates."""
        try:
            resp = await self._client.get(STATUS_URL)
            resp.raise_for_status()
            return resp.json().get("lastUpdated")
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("status.json fetch failed: %s", exc)
            return None

    async def fetch_snapshot(self) -> Snapshot:
        """Fetch all outage layers and return normalized items keyed by outage id."""
        points = await self._fetch_layer(LAYER_POINTS)
        points_no_poly = await self._fetch_layer(LAYER_POINTS_NO_POLY)
        polygons = await self._fetch_layer(LAYER_POLYGONS)
        psps_points = await self._fetch_layer(LAYER_PSPS_POINTS)
        psps_no_poly = await self._fetch_layer(LAYER_PSPS_POINTS_NO_POLY)
        psps_polygons = await self._fetch_layer(LAYER_PSPS_POLYGONS)
        return normalize(
            points=points + psps_points,
            points_no_poly=points_no_poly + psps_no_poly,
            polygons=polygons + psps_polygons,
            psps_ids=_feature_ids(psps_points) | _feature_ids(psps_no_poly),
        )

    async def fetch_psps_events(self) -> list[dict[str, Any]]:
        """PSPS planned-shutoff events. Empty outside PSPS season.

        The active-event shape is unknown until one occurs; log loudly when
        non-empty so the first real event gets recorded as a fixture.
        """
        resp = await self._client.get(PSPS_EVENTS_URL)
        resp.raise_for_status()
        events = resp.json().get("events", [])
        if events:
            logger.warning("PSPS events feed is non-empty (%d events): capture this!", len(events))
        return events

    async def _fetch_layer(self, layer: int) -> list[dict[str, Any]]:
        resp = await self._client.get(_query_url(layer))
        resp.raise_for_status()
        data = resp.json()
        return data.get("features", [])

    async def aclose(self) -> None:
        await self._client.aclose()


def normalize(
    points: list[dict[str, Any]],
    points_no_poly: list[dict[str, Any]],
    polygons: list[dict[str, Any]],
    psps_ids: frozenset[str] | set[str] = frozenset(),
) -> Snapshot:
    """Merge point layers with their polygons into a Snapshot keyed by outage id."""
    poly_by_id: dict[str, dict[str, Any]] = {}
    for feat in polygons:
        oid = _outage_id(feat)
        if oid:
            poly_by_id[oid] = feat.get("geometry")

    snapshot: Snapshot = {}
    for feat in points + points_no_poly:
        oid = _outage_id(feat)
        if not oid:
            continue
        props = feat.get("properties") or {}
        geom = feat.get("geometry") or {}
        lon, lat = None, None
        if geom.get("type") == "Point":
            lon, lat = geom["coordinates"][0], geom["coordinates"][1]
        elif props.get("OUTAGE_LATITUDE") is not None:
            lat, lon = props["OUTAGE_LATITUDE"], props["OUTAGE_LONGITUDE"]

        payload = {k: props.get(k) for k in _PAYLOAD_FIELDS if props.get(k) is not None}
        payload["is_psps"] = oid in psps_ids
        zips = frozenset({str(props["ZIP"])} if props.get("ZIP") else set())

        snapshot[oid] = Item(
            id=oid,
            eta=_epoch_ms(props.get("CURRENT_ETOR")),
            updated_at=_epoch_ms(props.get("LAST_UPDATE")),
            lat=lat,
            lon=lon,
            geometry=poly_by_id.get(oid),
            zips=zips,
            payload=payload,
        )
    return snapshot


def _outage_id(feature: dict[str, Any]) -> str | None:
    props = feature.get("properties") or {}
    oid = props.get("OUTAGE_ID") or props.get("F_OUTAGE_ID")
    return str(oid) if oid is not None else None


def _feature_ids(features: list[dict[str, Any]]) -> frozenset[str]:
    return frozenset(oid for f in features if (oid := _outage_id(f)))


def _epoch_ms(value: Any) -> datetime | None:
    if value is None:
        return None
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=UTC)
    except (ValueError, TypeError, OSError):
        return None
