package com.glazkov.outagewatch.data

import com.glazkov.outagewatch.api.AddressSuggestion
import com.glazkov.outagewatch.api.OutageApi
import com.glazkov.outagewatch.api.SubscriptionRequest
import com.glazkov.outagewatch.push.PushTokens
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SavedLocation(
    val zip: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val precise: Boolean = false, // true = an individual address, not a ZIP region
    val subscriptionId: String? = null,
) {
    // Stable key; two precise addresses can share a ZIP, so include the point.
    val id: String get() = if (precise) "$zip@$lat,$lon" else zip
}

/** Outcome of trying to add an area. */
sealed interface AddOutcome {
    data class Added(val location: SavedLocation) : AddOutcome
    data class NotServed(val utility: String) : AddOutcome
    data class Failed(val message: String) : AddOutcome
}

@Serializable
data class AlertPrefs(
    val quietStart: String? = null, // "22:00"
    val quietEnd: String? = null,
    val pspsWarnings: Boolean = true,
)

/**
 * Saved locations + alert preferences, persisted locally. No account: the
 * backend only ever sees an anonymous push token per subscription.
 */
class LocationsRepository(
    private val api: OutageApi,
    private val settings: Settings = Settings(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _locations = MutableStateFlow(load())
    val locations: StateFlow<List<SavedLocation>> = _locations

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<AlertPrefs> = _prefs

    /** Add a whole ZIP (a region). Pass force = true to add despite a non-PG&E warning. */
    suspend fun addZip(zip: String, label: String, force: Boolean = false): AddOutcome {
        val info = api.zipInfo(zip)
            ?: return AddOutcome.Failed("That isn't a California ZIP we cover.")
        info.servedBy?.let { if (!force) return AddOutcome.NotServed(it) }
        return commit(
            SavedLocation(
                zip = info.zip,
                label = label.ifBlank { "ZIP ${info.zip}" },
                lat = info.lat,
                lon = info.lon,
                radiusKm = info.radiusKm,
                precise = false,
            )
        )
    }

    /** Add a single address (a precise point). Geocoded to coordinates on-device. */
    suspend fun addAddress(query: String, label: String, force: Boolean = false): AddOutcome {
        val geo = DeviceLocation.geocode(query)
            ?: return AddOutcome.Failed("Couldn't find that address. Try adding the city or ZIP.")
        val info = api.zipInfo(geo.zip)
        info?.servedBy?.let { if (!force) return AddOutcome.NotServed(it) }
        return commit(
            SavedLocation(
                zip = geo.zip,
                label = label.ifBlank { geo.name.substringBefore(",").take(40) },
                lat = geo.lat,
                lon = geo.lon,
                radiusKm = 2.0, // address-level: polygon match handles precision
                precise = true,
            )
        )
    }

    /**
     * Add a suggestion the user picked from autocomplete. It's already resolved
     * to a point + ZIP + territory status, so this skips geocoding entirely.
     */
    suspend fun addSuggestion(
        suggestion: AddressSuggestion,
        label: String,
        force: Boolean = false,
    ): AddOutcome {
        suggestion.servedBy?.let { if (!force) return AddOutcome.NotServed(it) }
        return commit(
            SavedLocation(
                zip = suggestion.zip.orEmpty(),
                label = label.ifBlank { suggestion.title },
                lat = suggestion.lat,
                lon = suggestion.lon,
                radiusKm = 2.0,
                precise = true,
            )
        )
    }

    private suspend fun commit(base: SavedLocation): AddOutcome {
        val location = base.copy(subscriptionId = subscribeFor(base))
        _locations.value = _locations.value.filter { it.id != location.id } + location
        persist()
        return AddOutcome.Added(location)
    }

    suspend fun remove(location: SavedLocation) {
        location.subscriptionId?.let { runCatching { api.unsubscribe(it) } }
        _locations.value = _locations.value.filter { it.id != location.id }
        persist()
    }

    suspend fun updatePrefs(prefs: AlertPrefs) {
        _prefs.value = prefs
        settings.putString(PREFS_KEY, json.encodeToString(AlertPrefs.serializer(), prefs))
        resubscribeAll()
    }

    /** Push registration is best-effort: without a token the app stays read-only. */
    private suspend fun subscribeFor(location: SavedLocation): String? {
        val token = PushTokens.current() ?: return null
        val p = _prefs.value
        return runCatching {
            api.subscribe(
                SubscriptionRequest(
                    token = token,
                    platform = platformName(),
                    zipCode = location.zip.takeIf { it.length == 5 },
                    lat = location.lat,
                    lon = location.lon,
                    radiusKm = location.radiusKm,
                    label = location.label,
                    quietStart = p.quietStart,
                    quietEnd = p.quietEnd,
                    pspsWarnings = p.pspsWarnings,
                )
            ).id
        }.getOrNull()
    }

    private suspend fun resubscribeAll() {
        _locations.value = _locations.value.map { location ->
            location.subscriptionId?.let { runCatching { api.unsubscribe(it) } }
            location.copy(subscriptionId = subscribeFor(location))
        }
        persist()
    }

    private fun persist() {
        val encoded = json.encodeToString(
            ListSerializer(SavedLocation.serializer()), _locations.value
        )
        settings.putString(LOCATIONS_KEY, encoded)
    }

    private fun load(): List<SavedLocation> {
        val raw = settings.getStringOrNull(LOCATIONS_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedLocation.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun loadPrefs(): AlertPrefs {
        val raw = settings.getStringOrNull(PREFS_KEY) ?: return AlertPrefs()
        return runCatching {
            json.decodeFromString(AlertPrefs.serializer(), raw)
        }.getOrDefault(AlertPrefs())
    }

    companion object {
        private const val LOCATIONS_KEY = "saved_locations"
        private const val PREFS_KEY = "alert_prefs"
    }
}

expect fun platformName(): String
