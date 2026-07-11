"""Turn PG&E's terse cause codes into plain English a normal person understands.

PG&E's feed uses abbreviations like "BRKN UG EQUIPMNT" or "3RD PARTY" that are
meaningless to most people. We map the common ones to full phrases, and fall
back to expanding known abbreviations word by word so even unseen codes read
better than the raw string.
"""

from __future__ import annotations

# Full-phrase overrides for codes we've seen in the feed.
_PHRASES = {
    "PLNND SHUTDOWN": "Planned shutdown",
    "PLANNED SHUTDOWN": "Planned shutdown",
    "PLNND": "Planned work",
    "SCHEDULED": "Scheduled work",
    "BRKN UG EQUIPMNT": "Broken underground equipment",
    "BRKN POLE EQUIPMNT": "Broken equipment on a pole",
    "BRKN POLE EQUIP": "Broken equipment on a pole",
    "BRKN EQUIPMNT": "Broken equipment",
    "3RD PARTY": "Damage caused by someone else",
    "THRD PARTY": "Damage caused by someone else",
    "THIRD PARTY": "Damage caused by someone else",
    "EMERG REPAIRS": "Emergency repairs",
    "EMERGENCY REPAIRS": "Emergency repairs",
    "TREE CONTACT": "A tree hit a power line",
    "VEGETATION": "Trees or plants on a line",
    "POLE FIRE": "Pole fire",
    "WIRES DOWN": "Downed power lines",
    "WIRE DOWN": "A downed power line",
    "CAR HIT POLE": "A vehicle hit a pole",
    "VEHICLE": "A vehicle hit equipment",
    "ANIMAL": "An animal touched a line",
    "STORM": "Storm damage",
    "FIRE": "Fire in the area",
    "OVERLOAD": "The system was overloaded",
    "EQUIPMENT": "An equipment problem",
    "EQUIPMENT FAILURE": "Equipment failure",
    "EPSS": "A fast-trip safety shutoff (EPSS)",
    "FAST TRIP": "A fast-trip safety shutoff",
    "UNKNOWN": "Cause under investigation",
    "UNDER INVESTIGATION": "Cause under investigation",
    "AWAITING INVESTIGATION": "Cause under investigation",
    "PATROLLING": "Crews are locating the problem",
    "REPAIR WIRE DWN": "Crew repairing a downed wire",
    "BLOWN FUSE": "A blown fuse",
    "FUSE": "A blown fuse",
    "DIG IN": "Someone dug into an underground line",
    "CROSSARM": "A broken crossarm",
    "BROKEN CROSSARM": "A broken crossarm",
    "MYLAR BALLOON": "A metallic balloon hit a line",
    "BALLOON": "A metallic balloon hit a line",
    "LIGHTNING": "Lightning",
    "ANIMAL CONTACT": "An animal touched a line",
    "PLANNED": "Planned work",
    "EQUIP FAILURE": "Equipment failure",
    "EQPT FAIL": "Equipment failure",
    "PSPS": "A Public Safety Power Shutoff (PSPS)",
}

# Word-level expansions for the fallback path.
_WORDS = {
    "BRKN": "broken",
    "UG": "underground",
    "UNDRGRND": "underground",
    "OH": "overhead",
    "OVRHD": "overhead",
    "EQUIPMNT": "equipment",
    "EQUIP": "equipment",
    "EQPT": "equipment",
    "PLNND": "planned",
    "SCHD": "scheduled",
    "EMERG": "emergency",
    "REPAIRS": "repairs",
    "DAMGE": "damaged",
    "DAMG": "damaged",
    "DMG": "damaged",
    "DMGD": "damaged",
    "CABL": "cable",
    "CONDCTR": "conductor",
    "CNDCTR": "conductor",
    "SUBST": "substation",
    "3RD": "third",
    "THRD": "third",
    "PRTY": "party",
    "TEMP": "temporary",
    "MAINT": "maintenance",
    "TRANS": "transformer",
    "XFMR": "transformer",
    "XFRMR": "transformer",
    "TFMR": "transformer",
    "TRANSF": "transformer",
    "DISTR": "distribution",
    "CUST": "customer",
    "VEG": "vegetation",
    "WX": "weather",
    "DWN": "down",
    "SWTCH": "switch",
    "SUBSTN": "substation",
    "INSLTR": "insulator",
    "WND": "wind",
    "OPTD": "operated",
    "OPRTD": "operated",
    "FLR": "failure",
    "OVRLD": "overload",
    "LGHTNG": "lightning",
    "CRSSARM": "crossarm",
    "VEH": "vehicle",
}


def humanize_cause(raw: str | None) -> str | None:
    """Plain-English cause, or None if the feed gave us nothing."""
    if not raw:
        return None
    # Normalize separators so "DIG-IN" and "CAR/POLE" tokenize like spaced text.
    key = " ".join(raw.upper().replace("-", " ").replace("/", " ").split())
    if key in _PHRASES:
        return _PHRASES[key]
    words = [_WORDS.get(tok, tok.lower()) for tok in key.split()]
    phrase = " ".join(words)
    return phrase[:1].upper() + phrase[1:] if phrase else None
