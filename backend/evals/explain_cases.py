"""20 outage states with expected key facts for the AI explain eval.

Each case: the outage item fields, the ETA history length, substrings that MUST
appear in the explanation (key facts), and regex patterns that MUST NOT appear
(hallucination guards - times or causes that are not in the facts).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime

from watcher.types import Item

# 2026-07-09 18:00 UTC == 11:00 AM PDT
BASE = datetime(2026, 7, 9, 18, 0, tzinfo=UTC)

# Any clock time is a hallucinated ETA when the facts contain none.
CLOCK_TIME = r"\d{1,2}:\d{2}\s*(AM|PM|am|pm)"


@dataclass
class Case:
    name: str
    item: Item
    eta_history_len: int = 0
    must_contain: list[str] = field(default_factory=list)
    must_not_match: list[str] = field(default_factory=list)


def _item(oid: str, eta: datetime | None, **payload) -> Item:
    return Item(id=oid, eta=eta, updated_at=BASE, lat=38.4, lon=-122.7, payload=payload)


CASES: list[Case] = [
    Case(
        name="typical outage with eta",
        item=_item(
            "c1", BASE.replace(hour=23), OUTAGE_CAUSE="POLE FIRE",
            CREW_CURRENT_STATUS="Crew On Site", EST_CUSTOMERS=120, CITY="Santa Rosa",
        ),
        must_contain=["4:00 PM", "120"],
    ),
    Case(
        name="no eta yet",
        item=_item("c2", None, OUTAGE_CAUSE="UNKNOWN", EST_CUSTOMERS=45),
        must_not_match=[CLOCK_TIME],
    ),
    Case(
        name="psps planned shutoff",
        item=_item("c3", BASE.replace(day=10, hour=2), is_psps=True, EST_CUSTOMERS=3000),
        must_contain=["PSPS"],
    ),
    Case(
        name="fast trip epss",
        item=_item(
            "c4", BASE.replace(hour=20), fts_flag=1, OUTAGE_CAUSE="EPSS", EST_CUSTOMERS=200
        ),
        must_contain=["1:00 PM"],
    ),
    Case(
        name="eta slipped three times",
        item=_item("c5", BASE.replace(hour=22), OUTAGE_CAUSE="EQUIPMENT", EST_CUSTOMERS=80),
        eta_history_len=4,
        must_contain=["3:00 PM"],
    ),
    Case(
        name="unknown cause no crew",
        item=_item("c6", None, EST_CUSTOMERS=12),
        must_not_match=[CLOCK_TIME, r"(?i)pole fire", r"(?i)vegetation"],
    ),
    Case(
        name="large outage",
        item=_item(
            "c7", BASE.replace(day=10, hour=1), OUTAGE_CAUSE="STORM",
            CREW_CURRENT_STATUS="Awaiting Crew", EST_CUSTOMERS=15000, CITY="Oakland",
        ),
        must_contain=["15000", "6:00 PM"],
    ),
    Case(
        name="single customer",
        item=_item("c8", BASE.replace(hour=19), OUTAGE_CAUSE="ANIMAL", EST_CUSTOMERS=1),
        must_contain=["12:00 PM"],
    ),
    Case(
        name="vegetation cause",
        item=_item(
            "c9", BASE.replace(hour=21), OUTAGE_CAUSE="VEGETATION",
            CREW_CURRENT_STATUS="Crew Assigned", EST_CUSTOMERS=340, CITY="San Jose",
        ),
        must_contain=["2:00 PM"],
    ),
    Case(
        name="planned maintenance",
        item=_item(
            "c10", BASE.replace(hour=23, minute=30), OUTAGE_CAUSE="PLNND SHUTDOWN",
            EST_CUSTOMERS=25,
        ),
        must_contain=["4:30 PM"],
        must_not_match=[r"(?i)\bPSPS\b"],  # planned work is not a PSPS
    ),
    Case(
        name="car hit pole",
        item=_item(
            "c11", BASE.replace(hour=20, minute=15), OUTAGE_CAUSE="3RD PARTY",
            CREW_CURRENT_STATUS="Crew On Site", EST_CUSTOMERS=560,
        ),
        must_contain=["1:15 PM"],
    ),
    Case(
        name="psps without eta",
        item=_item("c12", None, is_psps=True, EST_CUSTOMERS=8000, CITY="Napa"),
        must_contain=["PSPS"],
        must_not_match=[CLOCK_TIME],
    ),
    Case(
        name="eta improved",
        item=_item("c13", BASE.replace(hour=19, minute=45), OUTAGE_CAUSE="EQUIPMENT",
                   EST_CUSTOMERS=95),
        eta_history_len=2,
        must_contain=["12:45 PM"],
    ),
    Case(
        name="fast trip no eta",
        item=_item("c14", None, fts_flag=1, EST_CUSTOMERS=430, CITY="Grass Valley"),
        must_not_match=[CLOCK_TIME],
    ),
    Case(
        name="wildfire cause",
        item=_item(
            "c15", BASE.replace(day=10, hour=4), OUTAGE_CAUSE="FIRE",
            CREW_CURRENT_STATUS="Hazard Assessment", EST_CUSTOMERS=2200, CITY="Chico",
        ),
        must_contain=["9:00 PM"],
    ),
    Case(
        name="zero customers device outage",
        item=_item("c16", BASE.replace(hour=18, minute=30), OUTAGE_CAUSE="PLNND SHUTDOWN",
                   EST_CUSTOMERS=0),
        must_contain=["11:30 AM"],
    ),
    Case(
        name="overnight restoration",
        item=_item(
            "c17", BASE.replace(day=10, hour=9), OUTAGE_CAUSE="STORM", EST_CUSTOMERS=670,
            CREW_CURRENT_STATUS="Awaiting Crew", CITY="Eureka",
        ),
        must_contain=["2:00 AM"],
    ),
    Case(
        name="many eta changes untrustworthy",
        item=_item("c18", BASE.replace(day=10, hour=0), OUTAGE_CAUSE="EQUIPMENT",
                   EST_CUSTOMERS=51),
        eta_history_len=6,
        must_contain=["5:00 PM"],
    ),
    Case(
        name="crew en route",
        item=_item(
            "c19", BASE.replace(hour=21, minute=15), OUTAGE_CAUSE="UNKNOWN",
            CREW_CURRENT_STATUS="Crew Dispatched", EST_CUSTOMERS=310, CITY="Fresno",
        ),
        must_contain=["2:15 PM"],
    ),
    Case(
        name="psps fast trip combo",
        item=_item(
            "c20", BASE.replace(day=10, hour=3), is_psps=True, fts_flag=1,
            EST_CUSTOMERS=1200, CITY="Auburn",
        ),
        must_contain=["PSPS", "8:00 PM"],
    ),
]
