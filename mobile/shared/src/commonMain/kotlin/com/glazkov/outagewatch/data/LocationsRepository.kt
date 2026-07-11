package com.glazkov.outagewatch.data

import com.glazkov.outagewatch.api.AddressSuggestion
import com.glazkov.outagewatch.api.OutageApi
import com.glazkov.outagewatch.api.SubscriptionRequest
import com.glazkov.outagewatch.push.PushTokens
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

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
    // Serializes every mutation so concurrent add/remove/resubscribe can't clobber
    // each other (e.g. resubscribe resurrecting a just-removed area).
    private val mutex = Mutex()

    private val _locations = MutableStateFlow(load())
    val locations: StateFlow<List<SavedLocation>> = _locations

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<AlertPrefs> = _prefs

    /** Add a whole ZIP (a region). Pass force = true to add despite a non-PG&E warning. */
    suspend fun addZip(zip: String, label: String, force: Boolean = false): AddOutcome {
        val info = try {
            api.zipInfo(zip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AddOutcome.Failed(NETWORK_ERROR)
        } ?: return AddOutcome.Failed("That isn't a California ZIP we cover.")
        info.servedBy?.let { if (!force) return AddOutcome.NotServed(it) }
        return commit(
            SavedLocation(
                zip = info.zip,
                label = cleanLabel(label, "ZIP ${info.zip}"),
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
        // The address geocoded on-device, so the point is valid; the territory
        // check is best-effort (skipped when the lookup can't reach the server).
        val info = runCatching { api.zipInfo(geo.zip) }.getOrNull()
        info?.servedBy?.let { if (!force) return AddOutcome.NotServed(it) }
        return commit(
            SavedLocation(
                zip = geo.zip,
                label = cleanLabel(label.ifBlank { geo.name.substringBefore(",") }, "ZIP ${geo.zip}"),
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
                label = cleanLabel(
                    label.ifBlank { suggestion.title },
                    suggestion.zip?.let { "ZIP $it" } ?: "Saved address",
                ),
                lat = suggestion.lat,
                lon = suggestion.lon,
                radiusKm = 2.0,
                precise = true,
            )
        )
    }

    private suspend fun commit(base: SavedLocation): AddOutcome = mutex.withLock {
        // Re-adding the same area must release its old subscription first, or the
        // backend keeps both and the user gets duplicate notifications.
        _locations.value.firstOrNull { it.id == base.id }?.subscriptionId
            ?.let { runCatching { api.unsubscribe(it) } }
        val location = base.copy(subscriptionId = subscribeFor(base))
        _locations.value = _locations.value.filter { it.id != location.id } + location
        persist()
        AddOutcome.Added(location)
    }

    suspend fun remove(location: SavedLocation) = mutex.withLock {
        _locations.value.firstOrNull { it.id == location.id }?.subscriptionId
            ?.let { runCatching { api.unsubscribe(it) } }
        _locations.value = _locations.value.filter { it.id != location.id }
        persist()
    }

    /**
     * Re-attempt any subscription that failed earlier (added offline, or before a
     * push token existed). Called on app start so alerts self-heal.
     */
    suspend fun retryMissingSubscriptions() = mutex.withLock {
        if (_locations.value.none { it.subscriptionId == null }) return@withLock
        _locations.value = _locations.value.map { loc ->
            if (loc.subscriptionId != null) loc else loc.copy(subscriptionId = subscribeFor(loc))
        }
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

    private suspend fun resubscribeAll() = mutex.withLock {
        _locations.value = _locations.value.map { location ->
            location.subscriptionId?.let { runCatching { api.unsubscribe(it) } }
            location.copy(subscriptionId = subscribeFor(location))
        }
        persist()
    }

    /** A label is never blank or a bare number (e.g. a stray geocoder house number). */
    private fun cleanLabel(raw: String, fallback: String): String {
        val trimmed = raw.trim().take(40)
        return if (trimmed.isEmpty() || trimmed.all { it.isDigit() }) fallback else trimmed
    }

    private fun persist() {
        val encoded = json.encodeToString(
            ListSerializer(SavedLocation.serializer()), _locations.value
        )
        settings.putString(LOCATIONS_KEY, encoded)
    }

    private fun load(): List<SavedLocation> {
        val raw = settings.getStringOrNull(LOCATIONS_KEY) ?: return emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(SavedLocation.serializer()), raw)
        }.onSuccess { return it }
        // Salvage element by element so one bad/edited entry can't wipe them all.
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(SavedLocation.serializer(), el) }.getOrNull()
        }
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
        private const val NETWORK_ERROR =
            "Couldn't reach OutageWatch. Check your connection and try again."
    }
}

expect fun platformName(): String
