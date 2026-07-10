"""Photon + Census suggestion normalization + nearest-ZIP snapping (no network)."""

from outagewatch import zipcodes
from outagewatch.geocode import (
    _census_query,
    _titlecase_street,
    census_to_suggestion,
    to_suggestion,
)


def _feature(lat, lon, **props):
    return {"geometry": {"type": "Point", "coordinates": [lon, lat]}, "properties": props}


def test_ca_house_becomes_two_line_suggestion():
    s = to_suggestion(
        _feature(
            38.44,
            -122.71,
            osm_type="N",
            osm_id=1,
            country="United States",
            countrycode="US",
            state="California",
            city="Santa Rosa",
            street="Pacific Avenue",
            housenumber="1017",
            postcode="95404",
        )
    )
    assert s is not None
    assert s.title == "1017 Pacific Avenue"
    assert s.subtitle == "Santa Rosa, California, 95404"
    assert s.zip_code == "95404"
    assert s.pge is True
    assert s.served_by is None


def test_non_pge_address_flagged():
    s = to_suggestion(
        _feature(
            37.35,
            -121.95,
            osm_type="N",
            osm_id=2,
            country="United States",
            state="California",
            city="Santa Clara",
            street="Main Street",
            housenumber="500",
            postcode="95050",
        )
    )
    assert s.zip_code == "95050"
    assert s.pge is False
    assert s.served_by == "Silicon Valley Power (Santa Clara)"


def test_out_of_state_dropped():
    assert (
        to_suggestion(
            _feature(
                39.16,
                -119.77,
                country="United States",
                state="Nevada",
                city="Carson City",
                postcode="89701",
            )
        )
        is None
    )


def test_missing_postcode_snaps_to_nearest_ca_zip():
    area = zipcodes.lookup("95404")
    s = to_suggestion(
        _feature(
            area.lat,
            area.lon,
            osm_type="W",
            osm_id=9,
            state="California",
            city="Santa Rosa",
            street="Some Road",
        )
    )
    assert s is not None
    assert s.zip_code == "95404"


def test_nearest_returns_zip_and_distance():
    area = zipcodes.lookup("95404")
    got, km = zipcodes.nearest(area.lat, area.lon)
    assert got is not None
    assert got.zip_code == "95404"
    assert km < 0.001


# --- Census geocoder (covers house numbers OSM/Photon lacks) ---


def _census_match(matched, lat, lon):
    return {"matchedAddress": matched, "coordinates": {"x": lon, "y": lat}}


def test_census_match_parses_to_suggestion():
    # Generic public example (Santa Rosa City Hall block), never a private address.
    area = zipcodes.lookup("95404")
    s = census_to_suggestion(
        _census_match("100 SANTA ROSA AVE, SANTA ROSA, CA, 95404", area.lat, area.lon)
    )
    assert s is not None
    assert s.title == "100 Santa Rosa Ave"
    assert s.subtitle == "Santa Rosa, California, 95404"
    assert s.zip_code == "95404"
    assert s.pge is True
    assert s.id.startswith("census:")  # id is coordinate-based, holds no address text


def test_census_titlecase_keeps_ordinals():
    assert _titlecase_street("175 SOUTH 23RD STREET") == "175 South 23rd Street"


def test_census_query_appends_state_only_when_missing():
    assert _census_query("123 main st").endswith(", CA")
    assert _census_query("123 main st, santa rosa ca") == "123 main st, santa rosa ca"
