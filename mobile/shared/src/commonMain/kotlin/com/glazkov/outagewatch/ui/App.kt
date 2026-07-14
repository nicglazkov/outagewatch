package com.glazkov.outagewatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.glazkov.outagewatch.update.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * An outage id a notification tap wants to open. The platform layer (Android
 * MainActivity) writes it from the launch/new intent; [App] observes it and
 * navigates, so tapping a push actually opens the outage.
 */
object PendingOutage {
    val id = MutableStateFlow<String?>(null)
}

/** Opens a URL (or mailto:) in the platform browser/mail app. Set by the host. */
object ExternalLinks {
    var opener: ((String) -> Unit)? = null

    fun open(url: String) {
        opener?.invoke(url)
    }
}

/** App metadata surfaced in Settings. Set by the host from its build config. */
object AppInfo {
    var version: String = ""
}

/** Where the legal docs and feedback go. Update if you move to a custom domain. */
object Links {
    const val PRIVACY = "https://github.com/nicglazkov/outagewatch/blob/main/PRIVACY.md"
    const val TERMS = "https://github.com/nicglazkov/outagewatch/blob/main/TERMS.md"
    const val FEEDBACK = "https://github.com/nicglazkov/outagewatch/issues"
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

        // Once per launch, ask GitHub for a newer release. The dialog is shown
        // once and only once per new version (tracked in settings).
        var update by remember { mutableStateOf<AppUpdate.Available?>(null) }
        LaunchedEffect(Unit) {
            update = AppUpdate.check(AppInfo.version, AppGraph.api)
        }

        // A notification tap sets PendingOutage.id; open that outage, then clear it.
        val pendingOutage by PendingOutage.id.collectAsState()
        LaunchedEffect(pendingOutage) {
            val id = pendingOutage ?: return@LaunchedEffect
            PendingOutage.id.value = null
            nav.navigate(DetailRoute(id))
        }
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

        update?.let { available ->
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("Update available") },
                text = { Text("OutageWatch ${available.version} is available. You have ${AppInfo.version}.") },
                confirmButton = {
                    TextButton(onClick = {
                        ExternalLinks.open(available.url)
                        update = null
                    }) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = { update = null }) { Text("Not now") }
                },
            )
        }
    }
    }
}
