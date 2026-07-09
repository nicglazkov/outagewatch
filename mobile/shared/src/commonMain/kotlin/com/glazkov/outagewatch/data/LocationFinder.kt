package com.glazkov.outagewatch.data

/** Result of a one-tap "use my current location" request. */
sealed interface LocationResult {
    data class Found(val zip: String) : LocationResult
    data object PermissionDenied : LocationResult
    data object Unavailable : LocationResult
}

/**
 * Turns the device's current position into a ZIP so a non-technical user can
 * add their area without knowing it. Android wires GPS + reverse geocoding;
 * iOS wiring lands with the iOS build. Null provider means "type a ZIP".
 */
interface LocationFinder {
    suspend fun currentZip(): LocationResult
}

object DeviceLocation {
    var finder: LocationFinder? = null

    val available: Boolean get() = finder != null

    suspend fun currentZip(): LocationResult =
        finder?.currentZip() ?: LocationResult.Unavailable
}
