"""Matcher tests: polygon containment edge cases, radius distance, ZIP fallback."""

from watcher.matcher import Subscription, haversine_km, item_matches
from watcher.types import Item

# A 0.02 x 0.02 degree square near Santa Rosa (roughly 2.2km x 1.8km).
SQUARE = {
    "type": "Polygon",
    "coordinates": [
        [
            [-122.72, 38.44],
            [-122.70, 38.44],
            [-122.70, 38.46],
            [-122.72, 38.46],
            [-122.72, 38.44],
        ]
    ],
}


def _sub(lat=None, lon=None, zip_code=None, radius_km=1.0):
    return Subscription(id="s", token="t", lat=lat, lon=lon, zip_code=zip_code, radius_km=radius_km)


def _poly_item(**kwargs):
    return Item(id="o", lat=38.45, lon=-122.71, geometry=SQUARE, **kwargs)


def test_point_inside_polygon_matches():
    assert item_matches(_poly_item(), _sub(lat=38.45, lon=-122.71))


def test_point_on_polygon_edge_matches():
    assert item_matches(_poly_item(), _sub(lat=38.45, lon=-122.72))


def test_point_on_polygon_vertex_matches():
    assert item_matches(_poly_item(), _sub(lat=38.44, lon=-122.72))


def test_point_outside_polygon_beyond_radius_does_not_match():
    assert not item_matches(_poly_item(), _sub(lat=38.60, lon=-122.71, radius_km=1.0))


def test_point_outside_polygon_within_radius_matches():
    # ~1.1km east of the square's east edge; radius 2km should catch it.
    assert item_matches(_poly_item(), _sub(lat=38.45, lon=-122.687, radius_km=2.0))


def test_no_polygon_falls_back_to_distance():
    item = Item(id="o", lat=38.45, lon=-122.71)
    assert item_matches(item, _sub(lat=38.4501, lon=-122.7101, radius_km=1.0))
    assert not item_matches(item, _sub(lat=39.45, lon=-122.71, radius_km=1.0))


def test_zip_fallback_matches_without_point():
    item = Item(id="o", zips=frozenset({"95404"}))
    assert item_matches(item, _sub(zip_code="95404"))
    assert not item_matches(item, _sub(zip_code="95405"))


def test_zip_sub_does_not_match_item_without_zip():
    assert not item_matches(Item(id="o", lat=38.45, lon=-122.71), _sub(zip_code="95404"))


def test_haversine_sanity():
    # SF to Oakland is roughly 13km.
    assert 10 < haversine_km(37.7749, -122.4194, 37.8044, -122.2712) < 16


# --- precise (exact address) matching: only inside the footprint, never nearby ---

def _precise(lat, lon, zip_code=None, radius_km=5.0):
    return Subscription(
        id="s", token="t", lat=lat, lon=lon, zip_code=zip_code, radius_km=radius_km, precise=True
    )


def test_precise_address_inside_polygon_matches():
    assert item_matches(_poly_item(), _precise(38.45, -122.71))


def test_precise_address_nearby_but_outside_does_not_match():
    # ~3.3km north of the square (top edge 38.46). Well within a 5km radius, but
    # not inside the footprint: an area sub matches here, a precise one must not.
    assert item_matches(_poly_item(), _sub(lat=38.48, lon=-122.71, radius_km=5.0))
    assert not item_matches(_poly_item(), _precise(38.48, -122.71, radius_km=5.0))


def test_precise_address_never_matches_on_zip():
    item = Item(id="o", lat=38.45, lon=-122.71, geometry=SQUARE, zips=frozenset({"95404"}))
    # Far away but shares the ZIP: area sub matches by ZIP, precise sub must not.
    assert item_matches(item, _sub(lat=40.0, lon=-121.0, zip_code="95404"))
    assert not item_matches(item, _precise(40.0, -121.0, zip_code="95404"))


def test_precise_matches_point_only_outage_only_when_at_the_address():
    item = Item(id="o", lat=38.4500, lon=-122.7100)  # no polygon footprint
    assert item_matches(item, _precise(38.4504, -122.7100))  # ~44m, at the address
    # ~1.1km away: even with a big radius, a precise address ignores nearby.
    assert not item_matches(item, _precise(38.46, -122.71, radius_km=5.0))
