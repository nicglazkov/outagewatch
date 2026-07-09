package com.glazkov.outagewatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.glazkov.outagewatch.ui.theme.CompassTheme
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.glazkov.outagewatch.api.OutageApi
import com.glazkov.outagewatch.data.LocationsRepository
import com.glazkov.outagewatch.ui.add.AddLocationScreen
import com.glazkov.outagewatch.ui.detail.OutageDetailScreen
import com.glazkov.outagewatch.ui.home.HomeScreen
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
@Serializable object SettingsRoute
@Serializable data class DetailRoute(val outageId: String)
@Serializable data class ZipRoute(
    val zip: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
)

@Composable
fun App() {
    // Material colors still back a few M3 controls (switches, text fields);
    // the Compass palette drives the app's look via LocalCompass.
    val accent = if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = accent, secondary = accent)
    } else {
        lightColorScheme(primary = accent, secondary = accent)
    }
    CompassTheme {
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
}
