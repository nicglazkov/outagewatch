package com.glazkov.outagewatch.data

import com.glazkov.outagewatch.api.Outage
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Does an outage actually reach a precise point? Mirrors the backend matcher and
 * the web app: a polygon must contain the point; a coordinate-only outage counts
 * only within ~250 m. Used to show an address as "out" only when it truly is,
 * matching what the address-only alerts fire on.
 */
object Coverage {
    private const val PRECISE_POINT_KM = 0.25

    fun impacts(o: Outage, lat: Double, lon: Double): Boolean {
        val g = o.geometry
        if (g != null && covers(g, lat, lon)) return true
        // Fall back to the point only when there is no polygon to trust.
        if (g == null && o.lat != null && o.lon != null) {
            return haversineKm(lat, lon, o.lat, o.lon) <= PRECISE_POINT_KM
        }
        return false
    }

    private fun covers(geometry: JsonObject, lat: Double, lon: Double): Boolean {
        val type = geometry["type"]?.jsonPrimitive?.content
        val coords = (geometry["coordinates"] as? JsonArray) ?: return false
        return when (type) {
            "Polygon" -> polyCovers(coords, lon, lat)
            "MultiPolygon" -> coords.any { poly -> polyCovers(poly as JsonArray, lon, lat) }
            else -> false
        }
    }

    // A polygon covers the point when its outer ring contains it and no hole does.
    private fun polyCovers(rings: JsonArray, lon: Double, lat: Double): Boolean {
        if (rings.isEmpty()) return false
        if (!ringContains(rings[0] as JsonArray, lon, lat)) return false
        for (i in 1 until rings.size) {
            if (ringContains(rings[i] as JsonArray, lon, lat)) return false
        }
        return true
    }

    // Ray casting. Ring points are [lon, lat] per GeoJSON.
    private fun ringContains(ring: JsonArray, lon: Double, lat: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val pi = ring[i] as JsonArray
            val pj = ring[j] as JsonArray
            val xi = (pi[0].jsonPrimitive.doubleOrNull) ?: return inside
            val yi = (pi[1].jsonPrimitive.doubleOrNull) ?: return inside
            val xj = (pj[0].jsonPrimitive.doubleOrNull) ?: return inside
            val yj = (pj[1].jsonPrimitive.doubleOrNull) ?: return inside
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val rad = PI / 180.0
        val dLat = (bLat - aLat) * rad
        val dLon = (bLon - aLon) * rad
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(aLat * rad) * cos(bLat * rad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(s)))
    }
}
