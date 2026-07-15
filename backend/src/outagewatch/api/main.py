"""Client-agnostic HTTP API. Android, iOS, and web all consume this.

Two Cloud Run services run this same app:
- the public API (ENABLE_POLL=0): read endpoints + subscription management
- the private poller (ENABLE_POLL=1): Cloud Scheduler invokes POST /internal/poll
  with IAM enforcement; the endpoint 404s on the public service.
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, field_validator, model_validator

from outagewatch import zipcodes
from outagewatch.causes import humanize_cause
from outagewatch.config import settings
from outagewatch.ratelimit import RateLimiter
from outagewatch.store import StoredSubscription
from watcher.matcher import Subscription as MatcherSub
from watcher.matcher import item_matches
from watcher.types import Item

logger = logging.getLogger(__name__)

# Per-IP limits. Generous on purpose (mobile users share carrier IPs); these stop
# a runaway script, not real usage. Subscriptions is the one that must be capped
# (uncapped Firestore writes); nobody legitimately adds 30 areas a minute.
_SUBSCRIBE_LIMIT = RateLimiter(max_requests=30, window_seconds=60)
_EXPLAIN_LIMIT = RateLimiter(max_requests=60, window_seconds=60)
_GEOCODE_LIMIT = RateLimiter(max_requests=240, window_seconds=60)
_READ_LIMIT = RateLimiter(max_requests=120, window_seconds=60)
_DELETE_LIMIT = RateLimiter(max_requests=30, window_seconds=60)

# A device never legitimately watches this many areas; caps runaway Firestore
# growth that would slow the poller (which loads every subscription each cycle).
MAX_SUBS_PER_TOKEN = 25

# /v1/slo runs a Firestore query per call; cache the computed summary briefly so
# a flood of unauthenticated hits can't amplify into unbounded Firestore reads.
# The data only moves once per poll cycle (~5 min), so a short TTL loses nothing.
_SLO_TTL = 60.0
_slo_cache: tuple[float, dict[str, Any]] | None = None

# The statewide snapshot is otherwise re-downloaded from GCS on every read; cache
# it briefly so unauthenticated reads can't amplify into a GCS download per call.
# The poller (separate service) always loads fresh state, so only reads see this.
_SNAPSHOT_TTL = 30.0
_snapshot_cache: tuple[float, dict[str, Item]] | None = None


async def _load_snapshot(deps: Deps) -> dict[str, Item]:
    global _snapshot_cache
    now = time.monotonic()
    if _snapshot_cache is not None and now - _snapshot_cache[0] < _SNAPSHOT_TTL:
        return _snapshot_cache[1]
    snapshot = await deps.state.load() or {}
    _snapshot_cache = (now, snapshot)
    return snapshot


def _client_ip(request: Request) -> str:
    # On Cloud Run (*.run.app) the platform APPENDS the real client IP to the
    # right of any client-supplied X-Forwarded-For, so the last entry is the
    # trustworthy one. Keying off the leftmost value (which the caller controls)
    # would let an attacker rotate it to get a fresh rate-limit bucket per request.
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        parts = [p.strip() for p in forwarded.split(",") if p.strip()]
        if parts:
            return parts[-1]
    return request.client.host if request.client else "unknown"


def _rate_limit(limiter: RateLimiter, request: Request) -> None:
    if not limiter.allow(_client_ip(request)):
        raise HTTPException(status_code=429, detail="Too many requests. Please slow down.")


app = FastAPI(
    title="OutageWatch API",
    description="Power outage alerts for PG&E territory. Not affiliated with PG&E.",
    version="0.1.0",
    # Don't publish an interactive schema on the public service; it would
    # advertise the internal endpoints and the full request surface.
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


class Deps:
    """Lazily constructed production dependencies. Tests replace this object."""

    def __init__(self):
        cfg = settings()
        from outagewatch.explain import AnthropicClient, FirestoreExplainCache
        from outagewatch.feeds.pge import PgeFeed
        from outagewatch.push import FcmSender
        from outagewatch.store import (
            FirestoreEtaHistory,
            FirestoreSloLog,
            FirestoreSubscriptionStore,
            GcsStateStore,
        )

        self.state = GcsStateStore(cfg.data_bucket)
        self.subs = FirestoreSubscriptionStore(cfg.project_id)
        self.eta_history = FirestoreEtaHistory(cfg.project_id)
        self.slo = FirestoreSloLog(cfg.project_id)
        self.feed = PgeFeed()
        self.sender = FcmSender(cfg.project_id)
        self.llm = AnthropicClient(cfg.anthropic_model)
        self.explain_cache = FirestoreExplainCache(cfg.project_id)

        from outagewatch.geocode import Photon

        self.geocoder = Photon()


@lru_cache(maxsize=1)
def get_deps() -> Deps:
    return Deps()


class SubscriptionIn(BaseModel):
    # Real FCM registration tokens are ~150+ chars; this rejects trivial junk.
    token: str = Field(min_length=64, max_length=4096)
    platform: str = Field(default="android", pattern="^(android|ios|web)$")
    zip_code: str | None = Field(default=None, pattern=r"^\d{5}$")
    lat: float | None = Field(default=None, ge=32.0, le=42.5)
    lon: float | None = Field(default=None, ge=-125.0, le=-114.0)
    radius_km: float = Field(default=1.0, gt=0, le=50)
    label: str | None = Field(default=None, max_length=60)
    quiet_start: str | None = Field(default=None, pattern=r"^\d{2}:\d{2}$")
    quiet_end: str | None = Field(default=None, pattern=r"^\d{2}:\d{2}$")
    tz: str = "America/Los_Angeles"
    psps_warnings: bool = True
    # True for an exact address: alert only when the outage covers the point, not
    # when it is merely nearby. ZIP/region subscriptions leave this False.
    precise: bool = False

    @field_validator("tz")
    @classmethod
    def _known_tz(cls, value: str) -> str:
        # A bad tz would later crash the poller for everyone; coerce unknowns.
        from zoneinfo import available_timezones

        return value if value in available_timezones() else "America/Los_Angeles"

    @model_validator(mode="after")
    def _location_required(self):
        has_lat, has_lon = self.lat is not None, self.lon is not None
        if has_lat != has_lon:
            raise ValueError("lat and lon must be provided together")
        if not (has_lat and has_lon) and not self.zip_code:
            raise ValueError("provide zip_code or lat+lon")
        return self


class OutageOut(BaseModel):
    id: str
    cause: str | None = None
    crew_status: str | None = None
    est_customers: int | None = None
    city: str | None = None
    started_at: str | None = None
    eta: str | None = None
    last_update: str | None = None
    lat: float | None = None
    lon: float | None = None
    is_psps: bool = False
    geometry: dict[str, Any] | None = None


def _to_out(item: Item, include_geometry: bool = False) -> OutageOut:
    p = item.payload
    return OutageOut(
        id=item.id,
        cause=humanize_cause(p.get("OUTAGE_CAUSE")),
        crew_status=p.get("CREW_CURRENT_STATUS"),
        est_customers=p.get("EST_CUSTOMERS"),
        city=p.get("CITY"),
        started_at=p.get("OUTAGE_START_TEXT"),
        eta=item.eta.isoformat() if item.eta else None,
        last_update=item.updated_at.isoformat() if item.updated_at else None,
        lat=item.lat,
        lon=item.lon,
        is_psps=bool(p.get("is_psps")),
        geometry=item.geometry if include_geometry else None,
    )


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


_STATIC = Path(__file__).parent / "static"


def _page(name: str) -> FileResponse:
    return FileResponse(_STATIC / name)


def _worker(name: str) -> FileResponse:
    # Service workers are served from the site root so their scope is "/".
    return FileResponse(
        _STATIC / name,
        media_type="application/javascript",
        headers={"Service-Worker-Allowed": "/", "Cache-Control": "no-cache"},
    )


@app.get("/", include_in_schema=False)
async def status_page() -> FileResponse:
    return _page("index.html")


@app.get("/statewide", include_in_schema=False)
async def statewide_page() -> FileResponse:
    return _page("statewide.html")


@app.get("/widget", include_in_schema=False)
async def widget_page() -> FileResponse:
    return _page("widget.html")


@app.get("/privacy", include_in_schema=False)
async def privacy_page() -> FileResponse:
    return _page("privacy.html")


@app.get("/terms", include_in_schema=False)
async def terms_page() -> FileResponse:
    return _page("terms.html")


@app.get("/sw.js", include_in_schema=False)
async def sw_js() -> FileResponse:
    return _worker("sw.js")


@app.get("/firebase-messaging-sw.js", include_in_schema=False)
async def fcm_sw_js() -> FileResponse:
    return _worker("firebase-messaging-sw.js")


@app.get("/v1/statewide", response_model=list[OutageOut])
async def list_statewide(
    request: Request,
    include_geometry: bool = True,
    deps: Deps = Depends(get_deps),
) -> list[OutageOut]:
    """Every current outage, for the statewide map. Reads the in-process snapshot
    cache (no per-request GCS fetch) and is rate limited like other reads."""
    _rate_limit(_READ_LIMIT, request)
    snapshot = await _load_snapshot(deps)
    return [_to_out(i, include_geometry) for i in snapshot.values()]


@app.get("/v1/slo")
async def slo_summary(request: Request, deps: Deps = Depends(get_deps)) -> dict[str, Any]:
    """Alert-latency SLO: feed change observed to push sent, last 24h.

    Target: under 6 minutes (360s). Latency here is measured from the feed's
    own LAST_UPDATE stamp, so it includes PG&E's publish delay plus our
    5-minute poll interval.
    """
    _rate_limit(_READ_LIMIT, request)
    global _slo_cache
    now = time.monotonic()
    if _slo_cache is not None and now - _slo_cache[0] < _SLO_TTL:
        return _slo_cache[1]

    latencies = sorted(deps.slo.recent_latencies(hours=24))
    if not latencies:
        summary: dict[str, Any] = {"count": 0, "target_seconds": 360}
    else:

        def pct(p: float) -> float:
            return latencies[min(len(latencies) - 1, int(p * len(latencies)))]

        summary = {
            "count": len(latencies),
            "target_seconds": 360,
            "p50_seconds": round(pct(0.5), 1),
            "p95_seconds": round(pct(0.95), 1),
            "max_seconds": round(latencies[-1], 1),
            "within_target": sum(1 for v in latencies if v <= 360),
        }
    _slo_cache = (now, summary)
    return summary


@app.get("/v1/outages", response_model=list[OutageOut])
async def list_outages(
    request: Request,
    zip_code: str | None = Query(default=None, alias="zip", pattern=r"^\d{5}$"),
    lat: float | None = Query(default=None, ge=32.0, le=42.5),
    lon: float | None = Query(default=None, ge=-125.0, le=-114.0),
    radius_km: float = Query(default=10.0, gt=0, le=100),
    include_geometry: bool = False,
    deps: Deps = Depends(get_deps),
) -> list[OutageOut]:
    _rate_limit(_READ_LIMIT, request)
    has_point = lat is not None and lon is not None
    if (lat is None) != (lon is None):
        raise HTTPException(status_code=400, detail="lat and lon must be provided together")
    # Require a location filter: never return the entire statewide snapshot (which
    # with include_geometry is multi-MB and re-downloads the whole state per call).
    if not has_point and not zip_code:
        return []
    snapshot = await _load_snapshot(deps)
    items = list(snapshot.values())
    if has_point:
        probe = MatcherSub(id="q", token="q", lat=lat, lon=lon, radius_km=radius_km)
        items = [i for i in items if item_matches(i, probe)]
    else:
        area = zipcodes.lookup(zip_code)
        if area is None:
            raise HTTPException(status_code=404, detail="unknown California ZIP")
        probe = MatcherSub(
            id="q",
            token="q",
            zip_code=zip_code,
            lat=area.lat,
            lon=area.lon,
            radius_km=area.radius_km,
        )
        items = [i for i in items if item_matches(i, probe)]
    return [_to_out(i, include_geometry) for i in items]


@app.get("/v1/outages/{outage_id}")
async def get_outage(
    request: Request, outage_id: str, deps: Deps = Depends(get_deps)
) -> dict[str, Any]:
    _rate_limit(_READ_LIMIT, request)
    snapshot = await _load_snapshot(deps)
    item = snapshot.get(outage_id)
    if item is None:
        raise HTTPException(status_code=404, detail="outage not found or restored")
    return {
        "outage": _to_out(item, include_geometry=True).model_dump(),
        "eta_history": deps.eta_history.get(outage_id),
    }


@app.get("/v1/outages/{outage_id}/explain")
async def explain_outage_endpoint(
    request: Request, outage_id: str, deps: Deps = Depends(get_deps)
) -> dict[str, str]:
    import asyncio

    from outagewatch.explain import cache_key, explain_outage, fallback_explanation

    _rate_limit(_EXPLAIN_LIMIT, request)
    snapshot = await _load_snapshot(deps)
    item = snapshot.get(outage_id)
    if item is None:
        raise HTTPException(status_code=404, detail="outage not found or restored")
    # Serve a cached explanation before doing any further work (no LLM call, no
    # extra Firestore read), so repeated views of the same outage stay free.
    cached = deps.explain_cache.get(cache_key(item))
    if cached is not None:
        return {"outage_id": outage_id, "explanation": cached}
    history = deps.eta_history.get(outage_id)
    try:
        # The LLM SDK call is blocking; run it off the event loop, and never let
        # an LLM error 500 the endpoint. Fall back to a plain factual summary.
        text = await asyncio.to_thread(
            explain_outage, item, deps.llm, deps.explain_cache, history
        )
    except Exception:
        logger.exception("explain failed for %s, using fallback", outage_id)
        text = fallback_explanation(item, history)
    return {"outage_id": outage_id, "explanation": text}


@app.post("/v1/subscriptions", status_code=201)
async def create_subscription(
    request: Request, body: SubscriptionIn, deps: Deps = Depends(get_deps)
) -> dict[str, str]:
    _rate_limit(_SUBSCRIBE_LIMIT, request)
    # Cap watched areas per device so runaway/junk creation can't bloat Firestore
    # and slow the poller (which loads every subscription each cycle).
    if len(deps.subs.list_for_device(body.token)) >= MAX_SUBS_PER_TOKEN:
        raise HTTPException(status_code=429, detail="This device is watching too many areas.")
    data = body.model_dump()
    # A ZIP-only subscription matches by centroid + area-derived radius, the
    # same way an address subscription matches by its point.
    if data["zip_code"] and data["lat"] is None:
        area = zipcodes.lookup(data["zip_code"])
        if area is None:
            raise HTTPException(status_code=422, detail="unknown California ZIP")
        data["lat"], data["lon"] = area.lat, area.lon
        data["radius_km"] = max(data["radius_km"], area.radius_km)
        data["precise"] = False  # a ZIP centroid is an area, never an exact point
    sub = StoredSubscription(id=uuid.uuid4().hex, **data)
    deps.subs.upsert(sub)
    return {"id": sub.id}


class SuggestionOut(BaseModel):
    id: str
    title: str
    subtitle: str
    lat: float
    lon: float
    zip: str | None = None
    pge: bool = True
    served_by: str | None = None


@app.get("/v1/geocode/autocomplete", response_model=list[SuggestionOut])
async def geocode_autocomplete(
    request: Request,
    q: str = Query(min_length=1, max_length=120),
    lat: float | None = Query(default=None, ge=32.0, le=42.5),
    lon: float | None = Query(default=None, ge=-125.0, le=-114.0),
    deps: Deps = Depends(get_deps),
) -> list[SuggestionOut]:
    """Google-Maps-style address suggestions, biased to the caller's location and
    kept to California. Each hit is pre-resolved to a ZIP + territory status."""
    _rate_limit(_GEOCODE_LIMIT, request)
    hits = await deps.geocoder.autocomplete(q, lat, lon)
    return [
        SuggestionOut(
            id=s.id,
            title=s.title,
            subtitle=s.subtitle,
            lat=s.lat,
            lon=s.lon,
            zip=s.zip_code,
            pge=s.pge,
            served_by=s.served_by,
        )
        for s in hits
    ]


@app.get("/v1/zips/{zip_code}")
async def get_zip(request: Request, zip_code: str) -> dict[str, Any]:
    from outagewatch.territory import non_pge_utility

    _rate_limit(_READ_LIMIT, request)
    area = zipcodes.lookup(zip_code)
    if area is None:
        raise HTTPException(status_code=404, detail="unknown California ZIP")
    utility = non_pge_utility(area.zip_code)
    return {
        "zip": area.zip_code,
        "lat": area.lat,
        "lon": area.lon,
        "radius_km": area.radius_km,
        "pge": utility is None,
        "served_by": utility,  # null when PG&E (or unknown); a name when not
    }


def _is_sub_id(sub_id: str) -> bool:
    # Subscription ids are uuid4().hex: exactly 32 lowercase hex characters.
    return len(sub_id) == 32 and all(c in "0123456789abcdef" for c in sub_id)


@app.delete("/v1/subscriptions/{sub_id}", status_code=204)
async def delete_subscription(
    request: Request,
    sub_id: str,
    deps: Deps = Depends(get_deps),
    x_device_token: str | None = Header(default=None),
) -> None:
    _rate_limit(_DELETE_LIMIT, request)
    # Reject malformed ids before they reach Firestore (a reserved id would 500).
    if not _is_sub_id(sub_id):
        raise HTTPException(status_code=404, detail="subscription not found")
    # Ownership: only delete a subscription that belongs to the caller's own
    # device token. Without the owning token, behave as if it doesn't exist so
    # a guessed id leaks nothing about whether it's real.
    owned = x_device_token and any(
        s.id == sub_id for s in deps.subs.list_for_device(x_device_token)
    )
    if not owned:
        raise HTTPException(status_code=404, detail="subscription not found")
    deps.subs.delete(sub_id)


@app.post("/internal/poll", include_in_schema=False)
async def internal_poll(deps: Deps = Depends(get_deps)) -> dict[str, Any]:
    if os.environ.get("ENABLE_POLL", "0") != "1":
        raise HTTPException(status_code=404, detail="not found")
    from dataclasses import asdict

    from outagewatch.service import poll_once

    outcome = await poll_once(
        feed=deps.feed,
        state=deps.state,
        subs=deps.subs,
        sender=deps.sender,
        eta_history=deps.eta_history,
        slo=deps.slo,
    )
    return asdict(outcome)


def _parse_dt(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None


# Serve the website assets (css, js, icons, manifest, embeddable widget). Mounted
# last so it never shadows the API routes above.
app.mount("/static", StaticFiles(directory=_STATIC), name="static")
