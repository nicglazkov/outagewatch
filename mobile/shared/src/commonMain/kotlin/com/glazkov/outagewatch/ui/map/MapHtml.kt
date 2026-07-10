package com.glazkov.outagewatch.ui.map

import com.glazkov.outagewatch.api.Outage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Self-contained MapLibre GL page. Vector tiles from OpenFreeMap (free, no key,
 * real light/dark styles), outage markers and polygons drawn as GeoJSON layers.
 * Tapping "Details" in a popup navigates to ow://outage/{id}, which the host
 * WebView intercepts. window.focusOutage(id) flies to an outage on a list tap.
 */
fun buildMapHtml(
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
    outages: List<Outage>,
    dark: Boolean,
    zoomControl: Boolean = true,
): String {
    val features: JsonArray = buildJsonArray {
        outages.forEach { o ->
            add(
                buildJsonObject {
                    put("id", o.id)
                    put("lat", o.lat)
                    put("lon", o.lon)
                    put("psps", o.isPsps)
                    put("cause", o.cause ?: "Cause under investigation")
                    put("customers", o.estCustomers)
                    put("eta", o.eta)
                    if (o.geometry != null) put("geometry", o.geometry)
                }
            )
        }
    }
    val data = Json.encodeToString(JsonArray.serializer(), features)
    val bg = if (dark) "#111" else "#fff"
    val styleUrl = if (dark) {
        "https://tiles.openfreemap.org/styles/dark"
    } else {
        "https://tiles.openfreemap.org/styles/positron"
    }

    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet">
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<style>
  html, body { margin:0; padding:0; background:$bg; }
  /* Height is set from window.innerHeight in JS: both height:100% (parent
     chain collapses) and 100dvh (not resolving on this WebView) yield 0. */
  #map { width:100vw; }
  .maplibregl-popup-content { padding:10px 12px; border-radius:10px; font: 13px -apple-system, system-ui, sans-serif; }
  .popup b { font-size: 14px; }
  .popup a { display:inline-block; margin-top:6px; font-weight:600; color:#0a84ff; text-decoration:none; }
</style>
</head>
<body>
<div id="map"></div>
<script>
var outages = $data;
var mapEl = document.getElementById('map');
function sizeMap() { mapEl.style.height = window.innerHeight + 'px'; }
sizeMap();

var map = new maplibregl.Map({
  container: 'map',
  style: '$styleUrl',
  center: [$centerLon, $centerLat],
  zoom: 11,
  attributionControl: false
});
// OpenFreeMap's style carries its own attribution; compact keeps it a small "i".
map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
if ($zoomControl) { map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-left'); }

function popupHtml(o) {
  var eta = o.eta ? new Date(o.eta).toLocaleString('en-US', {
    timeZone: 'America/Los_Angeles', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit' }) : 'no estimate yet';
  return '<div class="popup"><b>' + (o.psps ? 'PSPS shutoff' : 'Power outage') + '</b><br>'
    + o.cause + '<br>'
    + (o.customers != null ? o.customers + ' customers<br>' : '')
    + 'Restoration: ' + eta
    + '<br><a href="ow://outage/' + o.id + '">Details &rarr;</a></div>';
}

// Walk every [lon,lat] of a GeoJSON geometry (Polygon/MultiPolygon/Point/Line).
function geomWalk(g, cb) {
  var c = g.coordinates;
  function ring(r) { r.forEach(function (pt) { cb(pt[0], pt[1]); }); }
  if (g.type === 'Polygon') { c.forEach(ring); }
  else if (g.type === 'MultiPolygon') { c.forEach(function (poly) { poly.forEach(ring); }); }
  else if (g.type === 'LineString') { ring(c); }
  else if (g.type === 'Point') { cb(c[0], c[1]); }
}

// A geographic circle as a GeoJSON polygon (MapLibre has no native circle).
function circlePolygon(lon, lat, km, n) {
  n = n || 64;
  var coords = [];
  var latDeg = km / 111.32;
  var lonDeg = km / (111.32 * Math.cos(lat * Math.PI / 180));
  for (var i = 0; i <= n; i++) {
    var t = (i / n) * 2 * Math.PI;
    coords.push([lon + lonDeg * Math.cos(t), lat + latDeg * Math.sin(t)]);
  }
  return { type: 'Feature', geometry: { type: 'Polygon', coordinates: [coords] } };
}

var byId = {};          // outage id -> { bounds?, lngLat, html } for focus + popup
var polyFeatures = [];
var pointFeatures = [];
outages.forEach(function (o) {
  var color = o.psps ? '#c62828' : '#e65100';
  var html = popupHtml(o);
  if (o.geometry) {
    polyFeatures.push({ type: 'Feature', properties: { id: o.id, color: color }, geometry: o.geometry });
    var b = new maplibregl.LngLatBounds();
    geomWalk(o.geometry, function (lon, lat) { b.extend([lon, lat]); });
    var ctr = b.getCenter();
    byId[o.id] = { bounds: b, lngLat: [ctr.lng, ctr.lat], html: html };
    // A small dot keeps a tiny device-level polygon findable when zoomed out.
    if (o.lat != null && o.lon != null) {
      pointFeatures.push({ type: 'Feature', properties: { id: o.id, color: color, r: 4 }, geometry: { type: 'Point', coordinates: [o.lon, o.lat] } });
    }
  } else if (o.lat != null && o.lon != null) {
    pointFeatures.push({ type: 'Feature', properties: { id: o.id, color: color, r: 9 }, geometry: { type: 'Point', coordinates: [o.lon, o.lat] } });
    byId[o.id] = { lngLat: [o.lon, o.lat], html: html };
  }
});

var activePopup = null;
function openPopupFor(id) {
  var e = byId[id];
  if (!e) return;
  if (activePopup) activePopup.remove();
  activePopup = new maplibregl.Popup({ maxWidth: '260px' })
    .setLngLat(e.lngLat).setHTML(e.html).addTo(map);
}

// Called from the host (list tap): fly to an outage and open its popup.
window.focusOutage = function (id) {
  var e = byId[id];
  if (!e) return;
  if (e.bounds) { map.fitBounds(e.bounds, { padding: 60, maxZoom: 16, duration: 700 }); }
  else { map.flyTo({ center: e.lngLat, zoom: 15, duration: 700 }); }
  setTimeout(function () { openPopupFor(id); }, 750);
};

map.on('load', function () {
  map.addSource('ow-circle', { type: 'geojson', data: circlePolygon($centerLon, $centerLat, $radiusKm) });
  map.addLayer({ id: 'ow-circle-line', type: 'line', source: 'ow-circle',
    paint: { 'line-color': '#888', 'line-width': 1, 'line-dasharray': [2, 2] } });

  map.addSource('ow-poly', { type: 'geojson', data: { type: 'FeatureCollection', features: polyFeatures } });
  map.addLayer({ id: 'ow-poly-fill', type: 'fill', source: 'ow-poly',
    paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.35 } });
  map.addLayer({ id: 'ow-poly-line', type: 'line', source: 'ow-poly',
    paint: { 'line-color': ['get', 'color'], 'line-width': 2 } });

  map.addSource('ow-pt', { type: 'geojson', data: { type: 'FeatureCollection', features: pointFeatures } });
  map.addLayer({ id: 'ow-pt-circle', type: 'circle', source: 'ow-pt',
    paint: { 'circle-radius': ['get', 'r'], 'circle-color': ['get', 'color'], 'circle-opacity': 0.65,
      'circle-stroke-color': ['get', 'color'], 'circle-stroke-width': 1.5 } });

  map.addSource('ow-center', { type: 'geojson', data: { type: 'Feature', geometry: { type: 'Point', coordinates: [$centerLon, $centerLat] } } });
  map.addLayer({ id: 'ow-center-dot', type: 'circle', source: 'ow-center',
    paint: { 'circle-radius': 4, 'circle-color': '#888' } });

  map.on('click', 'ow-poly-fill', function (e) { openPopupFor(e.features[0].properties.id); });
  map.on('click', 'ow-pt-circle', function (e) { openPopupFor(e.features[0].properties.id); });

  // Frame all outages plus the watch center on first paint.
  var fb = new maplibregl.LngLatBounds();
  fb.extend([$centerLon, $centerLat]);
  pointFeatures.forEach(function (f) { fb.extend(f.geometry.coordinates); });
  polyFeatures.forEach(function (f) { geomWalk(f.geometry, function (lon, lat) { fb.extend([lon, lat]); }); });
  if (pointFeatures.length || polyFeatures.length) {
    map.fitBounds(fb, { padding: 40, maxZoom: 13, duration: 0 });
  }
  setTimeout(function () { sizeMap(); map.resize(); }, 250);
});

window.addEventListener('resize', function () { sizeMap(); map.resize(); });
</script>
</body>
</html>
""".trimIndent()
}
