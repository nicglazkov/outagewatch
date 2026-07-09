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
}

data class HomeState(
    val loading: Boolean = true,
    val locations: List<LocationStatus> = emptyList(),
)

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.locations
    private val api = AppGraph.api

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            repo.locations.collect { saved ->
                refresh(saved)
            }
        }
    }

    private suspend fun refresh(saved: List<SavedLocation>) {
        if (saved.isEmpty()) {
            _state.value = HomeState(loading = false)
            return
        }
        val statuses = saved.map { location ->
            runCatching {
                LocationStatus(location, api.outagesForZip(location.zip))
            }.getOrElse { LocationStatus(location, error = true) }
        }
        _state.value = HomeState(loading = false, locations = statuses)
    }
}
