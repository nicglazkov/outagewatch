package com.glazkov.outagewatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.data.SavedLocation
import com.glazkov.outagewatch.ui.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LocationStatus(
    val location: SavedLocation,
    val outages: List<Outage> = emptyList(),
    val error: Boolean = false,
) {
    /** The outage worth leading with: PSPS first, then most customers affected. */
    val worstOutage: Outage?
        get() = outages.sortedWith(
            compareByDescending<Outage> { it.isPsps }.thenByDescending { it.estCustomers ?: 0 }
        ).firstOrNull()

    val isOut: Boolean get() = outages.isNotEmpty()
}

data class HomeState(
    val loading: Boolean = true,
    val locations: List<LocationStatus> = emptyList(),
    // Map hero: primary area's center + nearby outages (with geometry).
    val mapCenter: SavedLocation? = null,
    val mapOutages: List<Outage> = emptyList(),
) {
    val affectedCount: Int get() = locations.count { it.isOut }
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.locations
    private val api = AppGraph.api

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            repo.locations.collect { saved -> refresh(saved) }
        }
    }

    private suspend fun refresh(saved: List<SavedLocation>) {
        if (saved.isEmpty()) {
            _state.value = HomeState(loading = false)
            return
        }
        val statuses = saved.map { location ->
            runCatching {
                // Coordinates work for both ZIP regions (centroid) and precise
                // addresses (exact point), so one path serves both.
                LocationStatus(
                    location,
                    api.outagesNear(location.lat, location.lon, location.radiusKm),
                )
            }.getOrElse { LocationStatus(location, error = true) }
        }
        // Center the hero map on the first area affected, else the first area.
        val primary = statuses.firstOrNull { it.isOut }?.location ?: saved.first()
        // Keep the hero local: the area plus a 10km ring, not the whole metro.
        val mapRadius = primary.radiusKm + 10.0
        val mapOutages = runCatching {
            api.outagesNear(primary.lat, primary.lon, mapRadius, includeGeometry = true)
        }.getOrDefault(emptyList())
        _state.value = HomeState(
            loading = false,
            locations = statuses,
            mapCenter = primary,
            mapOutages = mapOutages,
        )
    }
}
