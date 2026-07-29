package com.glazkov.outagewatch.location

import com.glazkov.outagewatch.data.GeoResult
import com.glazkov.outagewatch.data.LocationFinder
import com.glazkov.outagewatch.data.LocationResult
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * GPS + geocoding on iOS, mirroring `AndroidLocationFinder`. CoreLocation is
 * main-thread-affine, so every call hops to the main dispatcher and bridges the
 * delegate callbacks back into `suspend` with continuations.
 *
 * Requires `NSLocationWhenInUseUsageDescription` in Info.plist; without it iOS
 * silently refuses the permission prompt and this reports PermissionDenied.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationFinder : LocationFinder {

    private val manager = CLLocationManager()
    private val delegate = Delegate()

    init {
        manager.delegate = delegate
    }

    override suspend fun currentZip(): LocationResult = withContext(Dispatchers.Main) {
        if (!ensurePermission()) return@withContext LocationResult.PermissionDenied
        val location = lastLocation() ?: return@withContext LocationResult.Unavailable
        val zip = reverseGeocodeZip(location) ?: return@withContext LocationResult.Unavailable
        location.coordinate.useContents {
            LocationResult.Found(latitude, longitude, zip)
        }
    }

    override suspend fun geocodeAddress(query: String): GeoResult? {
        val placemark = suspendCancellableCoroutine<CLPlacemark?> { cont ->
            CLGeocoder().geocodeAddressString(query) { placemarks, _ ->
                cont.resume(placemarks?.firstOrNull() as? CLPlacemark)
            }
        } ?: return null
        val zip = placemark.postalCode?.take(5) ?: return null
        val coordinate = placemark.location?.coordinate ?: return null
        return coordinate.useContents {
            GeoResult(latitude, longitude, zip, placemark.addressLine() ?: query)
        }
    }

    /** True once we hold when-in-use (or always) authorization, prompting once
     *  if the user has not been asked yet. */
    private suspend fun ensurePermission(): Boolean {
        val status = manager.authorizationStatus
        if (status.granted()) return true
        if (status != kCLAuthorizationStatusNotDetermined) return false
        return suspendCancellableCoroutine { cont ->
            delegate.onAuthorization = { cont.resume(it.granted()) }
            manager.requestWhenInUseAuthorization()
        }
    }

    /** The cached fix when there is one (instant, like Android's last-known
     *  location), otherwise a single fresh reading. */
    private suspend fun lastLocation(): CLLocation? {
        manager.location?.let { return it }
        return suspendCancellableCoroutine { cont ->
            delegate.onLocation = { cont.resume(it) }
            manager.requestLocation()
        }
    }

    private suspend fun reverseGeocodeZip(location: CLLocation): String? =
        suspendCancellableCoroutine { cont ->
            CLGeocoder().reverseGeocodeLocation(location) { placemarks, _ ->
                val placemark = placemarks?.firstOrNull() as? CLPlacemark
                cont.resume(placemark?.postalCode?.take(5))
            }
        }

    /** Delivers one-shot CoreLocation callbacks to whoever is waiting. Each
     *  handler fires at most once, so it is cleared as it is invoked. */
    private class Delegate : NSObject(), CLLocationManagerDelegateProtocol {
        var onAuthorization: ((CLAuthorizationStatus) -> Unit)? = null
        var onLocation: ((CLLocation?) -> Unit)? = null

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            // Fires once on delegate attach with the current status; only a
            // decision (anything but "not determined") answers the prompt.
            if (status == kCLAuthorizationStatusNotDetermined) return
            onAuthorization?.also { onAuthorization = null }?.invoke(status)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation
            onLocation?.also { onLocation = null }?.invoke(location)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            onLocation?.also { onLocation = null }?.invoke(null)
        }
    }
}

private fun CLAuthorizationStatus.granted(): Boolean =
    this == kCLAuthorizationStatusAuthorizedWhenInUse ||
        this == kCLAuthorizationStatusAuthorizedAlways

/** A one-line street address, the closest match to Android's
 *  `Address.getAddressLine(0)`, which is what a saved place is labelled with. */
private fun CLPlacemark.addressLine(): String? {
    val street = listOfNotNull(subThoroughfare, thoroughfare).joinToString(" ").ifBlank { name }
    val region = listOfNotNull(administrativeArea, postalCode).joinToString(" ").ifBlank { null }
    return listOfNotNull(street?.ifBlank { null }, locality, region)
        .joinToString(", ")
        .ifBlank { null }
}
