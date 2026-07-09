"""Client-agnostic HTTP API. Android, iOS, and web all consume this.

Two Cloud Run services run this same app:
- the public API (ENABLE_POLL=0): read endpoints + subscription management
- the private poller (ENABLE_POLL=1): Cloud Scheduler invokes POST /internal/poll
  with IAM enforcement; the endpoint 404s on the public service.
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime
from functools import lru_cache
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel, Field, model_validator

from outagewatch import zipcodes
from outagewatch.config import settings
from outagewatch.store import StoredSubscription
from watcher.matcher import Subscription as MatcherSub
from watcher.matcher import item_matches
from watcher.types import Item

app = FastAPI(
    title="OutageWatch API",
    description="Power outage alerts for PG&E territory. Not affiliated with PG&E.",
    version="0.1.0",
)


class Deps:
    """Lazily constructed production dependencies. Tests replace this object."""

    def __init__(self):
        cfg = settings()
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

    @model_validator(mode="after")
    def _location_required(self):
        has_point = self.lat is not None and self.lon is not None
        if not has_point and not self.zip_code:
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
        cause=p.get("OUTAGE_CAUSE"),
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


@app.get("/v1/zips/{zip_code}")
async def get_zip(zip_code: str) -> dict[str, Any]:
    area = zipcodes.lookup(zip_code)
    if area is None:
        raise HTTPException(status_code=404, detail="unknown California ZIP")
    return {
        "zip": area.zip_code,
        "lat": area.lat,
        "lon": area.lon,
        "radius_km": area.radius_km,
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
