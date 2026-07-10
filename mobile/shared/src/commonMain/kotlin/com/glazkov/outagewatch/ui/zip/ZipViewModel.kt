package com.glazkov.outagewatch.ui.zip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.nearby.haversineKm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AreaOutage(val outage: Outage, val distanceKm: Double, val inZip: Boolean)

data class ZipState(
    val loading: Boolean = true,
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

    private suspend fun load() {
        // "Nearby" scales with the area's own size: your area plus an 8km ring,
        // so a small ZIP like Los Altos doesn't surface outages a metro away.
        val contextRadius = radiusKm + 8.0
        val outages = runCatching {
            AppGraph.api.outagesNear(lat, lon, contextRadius, includeGeometry = true)
        }.getOrDefault(emptyList())
        val entries = outages
            .map { o ->
                val d = haversineKm(lat, lon, o.lat ?: lat, o.lon ?: lon)
                AreaOutage(o, d, inZip = d <= radiusKm)
            }
            .sortedWith(compareByDescending<AreaOutage> { it.inZip }.thenBy { it.distanceKm })
        _state.value = ZipState(
            loading = false,
            contextRadiusKm = contextRadius,
            areaOutages = entries,
        )
    }
}
