package com.glazkov.outagewatch.ui.zip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.haversineKm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AreaOutage(val outage: Outage, val distanceKm: Double?, val inZip: Boolean)

data class ZipState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: Boolean = false,
    val contextRadiusKm: Double = 0.0,
    val areaOutages: List<AreaOutage> = emptyList(),
)

class ZipViewModel(
    private val zip: String,
    private val lat: Double,
    private val lon: Double,
    private val radiusKm: Double,
) : ViewModel() {

    private val _state = MutableStateFlow(ZipState())
    val state: StateFlow<ZipState> = _state

    init {
        viewModelScope.launch { load() }
    }

    /** Retry after a load error (shows the full-screen spinner). */
    fun reload() {
        _state.value = ZipState(loading = true)
        viewModelScope.launch { load() }
    }

    /** Re-fetch while keeping the current list visible. [silent] hides the
     *  pull indicator, for automatic refreshes. */
    fun refresh(silent: Boolean = false) {
        if (!silent) _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch { load(isRefresh = true) }
    }

    private suspend fun load(isRefresh: Boolean = false) {
        // "Nearby" scales with the area's own size: your area plus an 8km ring,
        // so a small ZIP like Los Altos doesn't surface outages a metro away.
        val contextRadius = radiusKm + 8.0
        val outages = runCatching {
            AppGraph.api.outagesNear(lat, lon, contextRadius, includeGeometry = true)
        }.getOrNull()
        if (outages == null) {
            // On a refresh, keep the last-good list rather than blanking to an
            // error. On the first load, distinguish a failure from a quiet area
            // so we never say "no outages" when we could not reach the feed.
            _state.value = if (isRefresh) {
                _state.value.copy(loading = false, refreshing = false)
            } else {
                ZipState(loading = false, error = true, contextRadiusKm = contextRadius)
            }
            return
        }
        val entries = outages
            .map { o ->
                // A coordinate-less outage has unknown distance; don't coerce it to
                // 0km, which would falsely label it "here / in your area".
                val d = if (o.lat != null && o.lon != null) {
                    haversineKm(lat, lon, o.lat, o.lon)
                } else {
                    null
                }
                AreaOutage(o, d, inZip = d != null && d <= radiusKm)
            }
            .sortedWith(
                compareByDescending<AreaOutage> { it.inZip }
                    .thenBy { it.distanceKm ?: Double.MAX_VALUE }
            )
        _state.value = ZipState(
            loading = false,
            contextRadiusKm = contextRadius,
            areaOutages = entries,
        )
    }
}
