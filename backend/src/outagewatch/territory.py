"""Is a ZIP served by PG&E, or by a different (municipal) electric utility?

Large parts of Northern/Central California sit inside PG&E's geographic
footprint but buy power from a city-owned utility instead: Santa Clara runs on
Silicon Valley Power, Sacramento on SMUD, and so on. OutageWatch only sees
PG&E's outage feed, so we warn people in those areas that we can't help them.

This is a curated list of the well-known non-PG&E electric utilities by ZIP.
It is not exhaustive and ZIP boundaries are fuzzy, so it drives a soft warning,
not a hard block.
"""

from __future__ import annotations

# utility display name -> ZIPs it serves (electric).
_NON_PGE: dict[str, set[str]] = {
    "Silicon Valley Power (Santa Clara)": {
        "95050", "95051", "95052", "95053", "95054", "95055", "95056",
    },
    "City of Palo Alto Utilities": {"94301", "94302", "94303", "94304", "94305", "94306"},
    "Alameda Municipal Power": {"94501", "94502"},
    "SMUD (Sacramento)": {
        "95811", "95814", "95815", "95816", "95817", "95818", "95819", "95820",
        "95821", "95822", "95823", "95824", "95825", "95826", "95827", "95828",
        "95829", "95830", "95831", "95832", "95833", "95834", "95835", "95838",
        "95841", "95842", "95843", "95864", "95605", "95608", "95610", "95621",
        "95624", "95626", "95628", "95630", "95655", "95660", "95662", "95670",
        "95673", "95683", "95742", "95757", "95758",
    },
    "Roseville Electric": {"95661", "95678", "95746", "95747"},
    "Modesto Irrigation District": {
        "95350", "95351", "95352", "95354", "95355", "95356", "95357", "95358",
    },
    "Turlock Irrigation District": {"95380", "95381", "95382"},
    "Redding Electric Utility": {"96001", "96002", "96003"},
    "Lodi Electric Utility": {"95240", "95241", "95242"},
    "City of Healdsburg": {"95448"},
    "City of Ukiah": {"95482"},
    "Lompoc": {"93436", "93438"},
    "Truckee Donner PUD": {"96160", "96161", "96162"},
    "Gridley / Biggs Municipal": {"95948", "95917"},
}

_ZIP_TO_UTILITY: dict[str, str] = {
    zip_code: name for name, zips in _NON_PGE.items() for zip_code in zips
}


def non_pge_utility(zip_code: str) -> str | None:
    """Name of the non-PG&E utility for this ZIP, or None if PG&E (or unknown)."""
    return _ZIP_TO_UTILITY.get(zip_code)
