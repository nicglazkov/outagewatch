"""AI explain card: plain-language answers from structured outage data.

Renders "why is my power out / when is it coming back / is this a PSPS?" via
Claude Haiku. The model never invents anything: it only restates the structured
facts we hand it, plus fixed background context (what EPSS means, what PSPS is).
Responses are cached per outage update, so cost stays near zero.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from watcher.types import Item

logger = logging.getLogger(__name__)

EXPLAIN_MODEL = "claude-haiku-4-5"
MAX_TOKENS = 400

SYSTEM_PROMPT = """You write short, calm, plain-language explanations of power \
outages for a consumer app in PG&E territory (Northern/Central California).

Hard rules:
- Use ONLY the facts in the provided JSON. Never invent, estimate, or adjust \
restoration times, causes, locations, or customer counts. If a fact is missing, \
say it is not available yet.
- Times in the facts are already formatted in local Pacific time; repeat them \
exactly as given.
- 2 short paragraphs maximum, no headings, no bullet lists, no markdown.
- Never use em dashes or en dashes. Use a period, comma, or the word "to" \
for ranges. Plain sentences only.
- Do not speculate about what PG&E will do. Do not give safety instructions \
beyond: downed lines are emergencies, call 911.
- The app is not affiliated with PG&E; never speak as PG&E.

Background you may use to add context (not as facts about this outage):
- PSPS (Public Safety Power Shutoff) is a planned shutoff PG&E performs during \
dangerous fire weather.
- EPSS ("enhanced powerline safety settings", also called fast-trip) makes \
lines shut off at the first sign of trouble during fire season, which causes \
more frequent surprise outages that are usually shorter.
- "Crew On Site"/"Crew Assigned" style statuses describe PG&E's repair progress.
"""

PACIFIC = ZoneInfo("America/Los_Angeles")


class LlmClient(Protocol):
    def generate(self, system: str, prompt: str) -> str: ...


class AnthropicClient:
    def __init__(self, model: str = EXPLAIN_MODEL):
        import anthropic

        self._client = anthropic.Anthropic()
        self._model = model

    def generate(self, system: str, prompt: str) -> str:
        response = self._client.messages.create(
            model=self._model,
            max_tokens=MAX_TOKENS,
            system=system,
            messages=[{"role": "user", "content": prompt}],
        )
        return next(b.text for b in response.content if b.type == "text")


class ExplainCache(Protocol):
    def get(self, key: str) -> str | None: ...
    def put(self, key: str, text: str) -> None: ...


class FirestoreExplainCache:
    COLLECTION = "explains"

    def __init__(self, project_id: str):
        from google.cloud import firestore

        self._db = firestore.Client(project=project_id)

    def get(self, key: str) -> str | None:
        doc = self._db.collection(self.COLLECTION).document(key).get()
        return (doc.to_dict() or {}).get("text") if doc.exists else None

    def put(self, key: str, text: str) -> None:
        self._db.collection(self.COLLECTION).document(key).set(
            {"text": text, "created_at": datetime.now(UTC).isoformat()}
        )


@dataclass(frozen=True)
class ExplainFacts:
    """The complete, closed set of facts the model may restate."""

    outage_id: str
    cause: str | None
    crew_status: str | None
    est_customers: int | None
    city: str | None
    started_local: str | None
    eta_local: str | None
    is_psps: bool
    is_fast_trip: bool
    eta_changes: int  # times the restoration estimate has moved

    def to_prompt(self) -> str:
        payload = {
            "cause_from_pge_feed": self.cause or "not available yet",
            "crew_status": self.crew_status or "not available yet",
            "estimated_customers_affected": self.est_customers,
            "city": self.city,
            "outage_started": self.started_local or "not available yet",
            "estimated_restoration": self.eta_local or "not available yet",
            "is_planned_psps_shutoff": self.is_psps,
            "is_epss_fast_trip_circuit": self.is_fast_trip,
            "restoration_estimate_change_count": self.eta_changes,
        }
        return (
            "Explain this power outage to an affected resident. Answer: why is the "
            "power out, when is it estimated back, and whether this is a planned "
            "PSPS shutoff. Mention how reliable the estimate has been if it has "
            "changed. Facts (the only facts you may use):\n"
            + json.dumps(payload, indent=1)
        )


def facts_from_item(item: Item, eta_history: list[dict[str, Any]] | None = None) -> ExplainFacts:
    from outagewatch.causes import humanize_cause

    p = item.payload
    eta_changes = max(0, len(eta_history or []) - 1)
    return ExplainFacts(
        outage_id=item.id,
        cause=humanize_cause(p.get("OUTAGE_CAUSE")),
        crew_status=p.get("CREW_CURRENT_STATUS"),
        est_customers=p.get("EST_CUSTOMERS"),
        city=p.get("CITY"),
        started_local=_local(_parse_iso(p.get("OUTAGE_START_TEXT"))),
        eta_local=_local(item.eta),
        is_psps=bool(p.get("is_psps")),
        is_fast_trip=bool(p.get("fts_flag")),
        eta_changes=eta_changes,
    )


def cache_key(item: Item) -> str:
    stamp = item.updated_at.isoformat() if item.updated_at else "none"
    return f"{item.id}:{stamp}"


def explain_outage(
    item: Item,
    llm: LlmClient,
    cache: ExplainCache,
    eta_history: list[dict[str, Any]] | None = None,
) -> str:
    key = cache_key(item)
    cached = cache.get(key)
    if cached is not None:
        return cached
    facts = facts_from_item(item, eta_history)
    text = _strip_dashes(llm.generate(SYSTEM_PROMPT, facts.to_prompt()).strip())
    cache.put(key, text)
    return text


def _strip_dashes(text: str) -> str:
    """Belt-and-suspenders: never let an em/en dash reach the user.

    A stray em dash becomes ", " and an en dash between numbers becomes " to ".
    """
    text = text.replace(" — ", ", ").replace("—", ", ")
    text = text.replace("–", " to ")
    return text


def _parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _local(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    local = dt.astimezone(PACIFIC)
    return local.strftime("%A %b %d at %I:%M %p").replace(" 0", " ")
