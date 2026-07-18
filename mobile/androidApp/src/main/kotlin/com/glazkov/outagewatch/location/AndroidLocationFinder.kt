package com.glazkov.outagewatch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.glazkov.outagewatch.data.GeoResult
import com.glazkov.outagewatch.data.LocationFinder
import com.glazkov.outagewatch.data.LocationResult
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * GPS + reverse-geocode to a ZIP. Registers a permission launcher at
 * construction (in the activity's onCreate), then on request: ensures
 * permission, reads the last/current fix, and geocodes to a postal code.
 */
class AndroidLocationFinder(private val activity: ComponentActivity) : LocationFinder {

    private var pending: ((Boolean) -> Unit)? = null
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> pending?.invoke(granted); pending = null }

    override suspend fun currentZip(): LocationResult {
        if (!hasPermission()) {
            val granted = requestPermission()
            if (!granted) return LocationResult.PermissionDenied
        }
        val location = lastLocation() ?: return LocationResult.Unavailable
        val zip = reverseGeocodeZip(location) ?: return LocationResult.Unavailable
        return LocationResult.Found(location.latitude, location.longitude, zip)
    }

    override suspend fun geocodeAddress(query: String): GeoResult? {
        val geocoder = Geocoder(activity)
        val address = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 1) { results -> cont.resume(results.firstOrNull()) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
        } ?: return null
        val zip = address.postalCode?.take(5) ?: return null
        val name = address.getAddressLine(0) ?: query
        return GeoResult(address.latitude, address.longitude, zip, name)
    }

    private fun hasPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val fine = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        )
        return coarse == PackageManager.PERMISSION_GRANTED ||
            fine == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestPermission(): Boolean = suspendCoroutine { cont ->
        pending = { granted -> cont.resume(granted) }
        permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun lastLocation(): Location? {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return try {
            providers.mapNotNull { p ->
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            }.maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun reverseGeocodeZip(location: Location): String? {
        val geocoder = Geocoder(activity)
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { results ->
                    cont.resume(results.firstOrNull()?.postalCode?.take(5))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.postalCode?.take(5)
            }.getOrNull()
        }
    }
}
