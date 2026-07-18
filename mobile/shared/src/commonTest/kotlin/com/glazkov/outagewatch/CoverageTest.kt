package com.glazkov.outagewatch

import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.data.Coverage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageTest {

    // A unit square polygon from (0,0) to (1,1), GeoJSON [lon, lat] order.
    private fun square(): JsonObject = Json.decodeFromString(
        JsonObject.serializer(),
        """{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}""",
    )

    private fun outageWith(geometry: JsonObject? = null, lat: Double? = null, lon: Double? = null) =
        Outage(id = "o1", geometry = geometry, lat = lat, lon = lon)

    @Test
    fun pointInsidePolygonIsCovered() {
        assertTrue(Coverage.impacts(outageWith(square()), lat = 0.5, lon = 0.5))
    }

    @Test
    fun pointOutsidePolygonIsNotCovered() {
        assertFalse(Coverage.impacts(outageWith(square()), lat = 2.0, lon = 2.0))
    }

    @Test
    fun pointOnlyOutageMatchesOnlyWhenAtTheAddress() {
        // ~11m north of the point counts; a few km away does not.
        val near = outageWith(lat = 37.0001, lon = -122.0)
        val far = outageWith(lat = 37.05, lon = -122.0)
        assertTrue(Coverage.impacts(near, lat = 37.0, lon = -122.0))
        assertFalse(Coverage.impacts(far, lat = 37.0, lon = -122.0))
    }

    @Test
    fun noGeometryAndNoPointIsNotCovered() {
        assertFalse(Coverage.impacts(outageWith(), lat = 1.0, lon = 1.0))
    }
}
