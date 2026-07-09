package com.glazkov.outagewatch.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.ui.AppGraph
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NearbyOutage(val outage: Outage, val distanceKm: Double)

data class NearbyState(
    val loading: Boolean = true,
    val noReference: Boolean = false,
    val radiusKm: Double = 50.0,
    val outages: List<NearbyOutage> = emptyList(),
)

class NearbyViewModel : ViewModel() {

    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val reference = AppGraph.locations.locations.value.firstOrNull()
        if (reference == null) {
            _state.value = NearbyState(loading = false, noReference = true)
            return
        }
        val radius = 50.0
        val outages = runCatching {
            AppGraph.api.outagesNear(reference.lat, reference.lon, radius)
        }.getOrDefault(emptyList())
        val sorted = outages
            .map {
                NearbyOutage(
                    it,
                    haversineKm(reference.lat, reference.lon, it.lat ?: reference.lat, it.lon ?: reference.lon),
                )
            }
            .sortedBy { it.distanceKm }
        _state.value = NearbyState(loading = false, radiusKm = radius, outages = sorted)
    }
}

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0088
    val p1 = lat1 * PI / 180
    val p2 = lat2 * PI / 180
    val dp = (lat2 - lat1) * PI / 180
    val dl = (lon2 - lon1) * PI / 180
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
