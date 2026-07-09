package com.glazkov.outagewatch.data

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
    val subscriptionId: String? = null,
)

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

    suspend fun addZip(zip: String, label: String): Result<SavedLocation> {
        val info = api.zipInfo(zip)
            ?: return Result.failure(IllegalArgumentException("Not a California ZIP we cover"))
        var location = SavedLocation(
            zip = info.zip,
            label = label.ifBlank { "ZIP ${info.zip}" },
            lat = info.lat,
            lon = info.lon,
            radiusKm = info.radiusKm,
        )
        location = location.copy(subscriptionId = subscribeFor(location))
        _locations.value = _locations.value.filter { it.zip != location.zip } + location
        persist()
        return Result.success(location)
    }

    suspend fun remove(location: SavedLocation) {
        location.subscriptionId?.let { runCatching { api.unsubscribe(it) } }
        _locations.value = _locations.value - location
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
                    zipCode = location.zip,
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
