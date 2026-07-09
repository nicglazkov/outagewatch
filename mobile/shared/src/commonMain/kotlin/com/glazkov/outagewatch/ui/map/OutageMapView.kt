package com.glazkov.outagewatch.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.glazkov.outagewatch.api.Outage

/**
 * Interactive outage map: OSM tiles, outage markers and polygons, ZIP radius
 * circle. Rendered in a platform WebView; popup "Details" links call back
 * into [onOutageTap].
 */
@Composable
expect fun OutageMapView(
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
    outages: List<Outage>,
    dark: Boolean,
    onOutageTap: (String) -> Unit,
    modifier: Modifier,
)
