package com.glazkov.outagewatch.ui.zip

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glazkov.outagewatch.ui.formatEta
import com.glazkov.outagewatch.ui.map.OutageMapView
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipDetailScreen(
    zip: String,
    label: String,
    lat: Double,
    lon: Double,
    radiusKm: Double,
    onOpenOutage: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ZipViewModel = viewModel(key = zip) { ZipViewModel(zip, lat, lon, radiusKm) },
) {
    val state by viewModel.state.collectAsState()
    val dark = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                summaryLine(state),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutageMapView(
                centerLat = lat,
                centerLon = lon,
                radiusKm = radiusKm,
                outages = state.areaOutages.map { it.outage },
                dark = dark,
                onOutageTap = onOpenOutage,
                modifier = Modifier.fillMaxWidth().weight(1.1f),
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.areaOutages.isEmpty()) {
                    item {
                        Text(
                            "Nothing reported within ${state.contextRadiusKm.roundToInt()} km. " +
                                "You'll get a push the moment that changes.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.areaOutages, key = { it.outage.id }) { entry ->
                    Card(
                        onClick = { onOpenOutage(entry.outage.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (entry.outage.isPsps) "PSPS shutoff" else "Power outage",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (entry.inZip) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (entry.inZip) "in your area"
                                    else "${entry.distanceKm.roundToInt()} km away",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Text(
                                listOfNotNull(
                                    entry.outage.cause,
                                    entry.outage.city,
                                    entry.outage.estCustomers?.let { "$it customers" },
                                ).joinToString(" · ").ifEmpty { "Details pending" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(formatEta(entry.outage.eta), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun summaryLine(state: ZipState): String {
    val inZip = state.areaOutages.count { it.inZip }
    val nearby = state.areaOutages.size - inZip
    return when {
        inZip > 0 && nearby > 0 -> "$inZip in your area · $nearby more nearby"
        inZip > 0 -> "$inZip outage${if (inZip > 1) "s" else ""} in your area"
        nearby > 0 -> "Your area is clear · $nearby outage${if (nearby > 1) "s" else ""} nearby"
        else -> "No outages in or near your area"
    }
}
