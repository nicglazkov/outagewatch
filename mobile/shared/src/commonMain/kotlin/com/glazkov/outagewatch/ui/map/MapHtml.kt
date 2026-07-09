package com.glazkov.outagewatch.ui.map

import com.glazkov.outagewatch.api.Outage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Self-contained Leaflet map page. Tiles from OpenStreetMap, outage markers
 * and polygons injected as JSON. Tapping "Details" in a popup navigates to
 * ow://outage/{id}, which the host WebView intercepts.
 */
fun buildMapHtml(
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
    outages: List<Outage>,
    dark: Boolean,
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
    val tileFilter = if (dark) "filter: brightness(0.7) invert(1) hue-rotate(180deg);" else ""

    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html, body { margin:0; padding:0; background:$bg; }
  /* Height is set from window.innerHeight in JS: both height:100% (parent
     chain collapses) and 100dvh (not resolving on this WebView) yield 0. */
  #map { width:100vw; }
  .leaflet-tile { $tileFilter }
  .popup b { font-size: 14px; }
  .popup a { display:inline-block; margin-top:6px; font-weight:600; }
</style>
</head>
<body>
<div id="map"></div>
<script>
var outages = $data;
var mapEl = document.getElementById('map');
function sizeMap() { mapEl.style.height = window.innerHeight + 'px'; }
sizeMap();
var map = L.map('map', { zoomControl: true }).setView([$centerLat, $centerLon], 11);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 18,
  attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);

L.circle([$centerLat, $centerLon], {
  radius: ${radiusKm * 1000},
  color: '#888', weight: 1, fillOpacity: 0.05, dashArray: '6 6'
}).addTo(map);
L.circleMarker([$centerLat, $centerLon], { radius: 4, color: '#888' }).addTo(map);

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

var bounds = [];
outages.forEach(function (o) {
  var color = o.psps ? '#c62828' : '#e65100';
  var popup = popupHtml(o);
  if (o.geometry) {
    // The affected area itself: draw the polygon as the primary shape.
    var poly = L.geoJSON({ type: 'Feature', geometry: o.geometry }, {
      style: { color: color, weight: 2, fillColor: color, fillOpacity: 0.35 }
    }).addTo(map).bindPopup(popup);
    poly.eachLayer(function (l) { bounds.push(l.getBounds().getNorthEast(), l.getBounds().getSouthWest()); });
    // A small dot keeps a tiny device-level polygon findable when zoomed out.
    if (o.lat != null && o.lon != null) {
      L.circleMarker([o.lat, o.lon], {
        radius: 4, color: color, weight: 1, fillColor: color, fillOpacity: 0.9
      }).addTo(map).bindPopup(popup);
    }
  } else if (o.lat != null && o.lon != null) {
    // No polygon in the feed for this outage: fall back to a point marker.
    L.circleMarker([o.lat, o.lon], {
      radius: 9, color: color, weight: 2, fillColor: color, fillOpacity: 0.6
    }).addTo(map).bindPopup(popup);
    bounds.push([o.lat, o.lon]);
  }
});
bounds.push([$centerLat, $centerLon]);
if (bounds.length > 1) { map.fitBounds(bounds, { padding: [40, 40], maxZoom: 13 }); }

// The WebView often reports its final height after Leaflet initializes;
// resize the map element and recompute tile layout once layout settles.
setTimeout(function () { sizeMap(); map.invalidateSize(); }, 250);
window.addEventListener('resize', function () { sizeMap(); map.invalidateSize(); });
</script>
</body>
</html>
""".trimIndent()
}
