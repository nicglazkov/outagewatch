"""Cause humanization and non-PG&E territory checks."""

from outagewatch.causes import humanize_cause
from outagewatch.territory import non_pge_utility


def test_known_codes_become_plain_english():
    assert humanize_cause("BRKN UG EQUIPMNT") == "Broken underground equipment"
    assert humanize_cause("PLNND SHUTDOWN") == "Planned shutdown"
    assert humanize_cause("3RD PARTY") == "Damage caused by someone else"
    assert humanize_cause("EMERG REPAIRS") == "Emergency repairs"
    assert humanize_cause("UNKNOWN") == "Cause under investigation"


def test_unknown_code_expands_abbreviations():
    # Not in the phrase table; falls back to word expansion + sentence case.
    out = humanize_cause("BRKN XFMR EQUIP")
    assert out == "Broken transformer equipment"


def test_whitespace_and_case_normalized():
    assert humanize_cause("  plnnd   shutdown ") == "Planned shutdown"


def test_none_and_empty():
    assert humanize_cause(None) is None
    assert humanize_cause("") is None


def test_non_pge_utility_flags_santa_clara():
    assert non_pge_utility("95050") == "Silicon Valley Power (Santa Clara)"
    assert non_pge_utility("95814") == "SMUD (Sacramento)"
    assert non_pge_utility("94301") == "City of Palo Alto Utilities"


def test_pge_zip_returns_none():
    assert non_pge_utility("95404") is None  # Santa Rosa, PG&E
    assert non_pge_utility("94110") is None  # SF, PG&E
