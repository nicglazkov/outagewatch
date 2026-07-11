"""Client-agnostic HTTP API. Android, iOS, and web all consume this.

Two Cloud Run services run this same app:
- the public API (ENABLE_POLL=0): read endpoints + subscription management
- the private poller (ENABLE_POLL=1): Cloud Scheduler invokes POST /internal/poll
  with IAM enforcement; the endpoint 404s on the public service.
"""

from __future__ import annotations

import logging
import os
import uuid
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, field_validator, model_validator

from outagewatch import zipcodes
from outagewatch.causes import humanize_cause
from outagewatch.config import settings
from outagewatch.store import StoredSubscription
from watcher.matcher import Subscription as MatcherSub
from watcher.matcher import item_matches
from watcher.types import Item

logger = logging.getLogger(__name__)

app = FastAPI(
    title="OutageWatch API",
    description="Power outage alerts for PG&E territory. Not affiliated with PG&E.",
    version="0.1.0",
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
    token: str = Field(min_length=8)
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


@app.get("/", include_in_schema=False)
async def status_page() -> FileResponse:
    return FileResponse(Path(__file__).parent / "static" / "index.html")


@app.get("/v1/slo")
async def slo_summary(deps: Deps = Depends(get_deps)) -> dict[str, Any]:
    """Alert-latency SLO: feed change observed to push sent, last 24h.

    Target: under 6 minutes (360s). Latency here is measured from the feed's
    own LAST_UPDATE stamp, so it includes PG&E's publish delay plus our
    5-minute poll interval.
    """
    latencies = sorted(deps.slo.recent_latencies(hours=24))
    if not latencies:
        return {"count": 0, "target_seconds": 360}

    def pct(p: float) -> float:
        return latencies[min(len(latencies) - 1, int(p * len(latencies)))]

    return {
        "count": len(latencies),
        "target_seconds": 360,
        "p50_seconds": round(pct(0.5), 1),
        "p95_seconds": round(pct(0.95), 1),
        "max_seconds": round(latencies[-1], 1),
        "within_target": sum(1 for v in latencies if v <= 360),
    }


@app.get("/v1/outages", response_model=list[OutageOut])
async def list_outages(
    zip_code: str | None = Query(default=None, alias="zip", pattern=r"^\d{5}$"),
    lat: float | None = None,
    lon: float | None = None,
    radius_km: float = Query(default=10.0, gt=0, le=100),
    include_geometry: bool = False,
    deps: Deps = Depends(get_deps),
) -> list[OutageOut]:
    snapshot = await deps.state.load() or {}
    items = list(snapshot.values())
    if lat is not None and lon is not None:
        probe = MatcherSub(id="q", token="q", lat=lat, lon=lon, radius_km=radius_km)
        items = [i for i in items if item_matches(i, probe)]
    elif zip_code:
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
async def get_outage(outage_id: str, deps: Deps = Depends(get_deps)) -> dict[str, Any]:
    snapshot = await deps.state.load() or {}
    item = snapshot.get(outage_id)
    if item is None:
        raise HTTPException(status_code=404, detail="outage not found or restored")
    return {
        "outage": _to_out(item, include_geometry=True).model_dump(),
        "eta_history": deps.eta_history.get(outage_id),
    }


@app.get("/v1/outages/{outage_id}/explain")
async def explain_outage_endpoint(
    outage_id: str, deps: Deps = Depends(get_deps)
) -> dict[str, str]:
    import asyncio

    from outagewatch.explain import explain_outage, fallback_explanation

    snapshot = await deps.state.load() or {}
    item = snapshot.get(outage_id)
    if item is None:
        raise HTTPException(status_code=404, detail="outage not found or restored")
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
    body: SubscriptionIn, deps: Deps = Depends(get_deps)
) -> dict[str, str]:
    data = body.model_dump()
    # A ZIP-only subscription matches by centroid + area-derived radius, the
    # same way an address subscription matches by its point.
    if data["zip_code"] and data["lat"] is None:
        area = zipcodes.lookup(data["zip_code"])
        if area is None:
            raise HTTPException(status_code=422, detail="unknown California ZIP")
        data["lat"], data["lon"] = area.lat, area.lon
        data["radius_km"] = max(data["radius_km"], area.radius_km)
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
    q: str = Query(min_length=1, max_length=120),
    lat: float | None = Query(default=None, ge=32.0, le=42.5),
    lon: float | None = Query(default=None, ge=-125.0, le=-114.0),
    deps: Deps = Depends(get_deps),
) -> list[SuggestionOut]:
    """Google-Maps-style address suggestions, biased to the caller's location and
    kept to California. Each hit is pre-resolved to a ZIP + territory status."""
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
async def get_zip(zip_code: str) -> dict[str, Any]:
    from outagewatch.territory import non_pge_utility

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


@app.get("/v1/subscriptions")
async def list_subscriptions(
    token: str = Query(min_length=8), deps: Deps = Depends(get_deps)
) -> list[dict[str, Any]]:
    from dataclasses import asdict

    return [asdict(s) for s in deps.subs.list_for_device(token)]


@app.delete("/v1/subscriptions/{sub_id}", status_code=204)
async def delete_subscription(sub_id: str, deps: Deps = Depends(get_deps)) -> None:
    deps.subs.delete(sub_id)


@app.post("/internal/poll")
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
