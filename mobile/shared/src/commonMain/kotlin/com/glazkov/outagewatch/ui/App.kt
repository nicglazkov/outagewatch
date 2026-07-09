package com.glazkov.outagewatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.glazkov.outagewatch.api.OutageApi
import com.glazkov.outagewatch.data.LocationsRepository
import com.glazkov.outagewatch.ui.add.AddLocationScreen
import com.glazkov.outagewatch.ui.detail.OutageDetailScreen
import com.glazkov.outagewatch.ui.home.HomeScreen
import com.glazkov.outagewatch.ui.nearby.NearbyScreen
import com.glazkov.outagewatch.ui.settings.SettingsScreen
import com.glazkov.outagewatch.ui.zip.ZipDetailScreen
import kotlinx.serialization.Serializable

/** Process-wide dependencies. Deliberately tiny; no DI framework needed yet. */
object AppGraph {
    val api: OutageApi by lazy { OutageApi() }
    val locations: LocationsRepository by lazy { LocationsRepository(api) }
}

@Serializable object HomeRoute
@Serializable object AddLocationRoute
@Serializable object NearbyRoute
@Serializable object SettingsRoute
@Serializable data class DetailRoute(val outageId: String)
@Serializable data class ZipRoute(
    val zip: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
)

private val Amber = Color(0xFFFFC94A)

@Composable
fun App() {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Amber, secondary = Amber)
    } else {
        lightColorScheme(primary = Color(0xFFB07D1A), secondary = Color(0xFFB07D1A))
    }
    MaterialTheme(colorScheme = colors) {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onAddLocation = { nav.navigate(AddLocationRoute) },
                    onOpenZip = { loc ->
                        nav.navigate(
                            ZipRoute(loc.zip, loc.label, loc.lat, loc.lon, loc.radiusKm)
                        )
                    },
                    onOpenNearby = { nav.navigate(NearbyRoute) },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                )
            }
            composable<ZipRoute> { entry ->
                val route = entry.toRoute<ZipRoute>()
                ZipDetailScreen(
                    zip = route.zip,
                    label = route.label,
                    lat = route.lat,
                    lon = route.lon,
                    radiusKm = route.radiusKm,
                    onOpenOutage = { nav.navigate(DetailRoute(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<AddLocationRoute> {
                AddLocationScreen(onDone = { nav.popBackStack() })
            }
            composable<NearbyRoute> {
                NearbyScreen(
                    onOpenOutage = { nav.navigate(DetailRoute(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable<DetailRoute> { entry ->
                val route = entry.toRoute<DetailRoute>()
                OutageDetailScreen(outageId = route.outageId, onBack = { nav.popBackStack() })
            }
        }
    }
}
