package com.glazkov.outagewatch.data

/** Result of a one-tap "use my current location" request. Carries the exact
 *  GPS point (not just the ZIP) so the current location can be saved as a
 *  precise address that alerts only when an outage actually covers it. */
sealed interface LocationResult {
    data class Found(val lat: Double, val lon: Double, val zip: String) : LocationResult
    data object PermissionDenied : LocationResult
    data object Unavailable : LocationResult
}

/** A geocoded address: a precise point plus the ZIP it falls in. */
data class GeoResult(val lat: Double, val lon: Double, val zip: String, val name: String)

/**
 * Turns the device's current position or a typed address into coordinates so a
 * non-technical user can add their area without knowing a ZIP. Android wires
 * GPS + geocoding; iOS wiring lands with the iOS build.
 */
interface LocationFinder {
    suspend fun currentZip(): LocationResult
    suspend fun geocodeAddress(query: String): GeoResult?
}

object DeviceLocation {
    var finder: LocationFinder? = null

    val available: Boolean get() = finder != null

    suspend fun currentZip(): LocationResult =
        finder?.currentZip() ?: LocationResult.Unavailable

    suspend fun geocode(query: String): GeoResult? = finder?.geocodeAddress(query)
}
