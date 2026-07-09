package com.glazkov.outagewatch.ui.nearby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onOpenOutage: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: NearbyViewModel = viewModel { NearbyViewModel() },
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outages near you") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.noReference -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text("Add a location first - nearby outages are shown around your saved areas.")
            }

            state.outages.isEmpty() -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text("No outages reported within ${state.radiusKm.roundToInt()} km right now.")
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.outages, key = { it.outage.id }) { entry ->
                    Card(
                        onClick = { onOpenOutage(entry.outage.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.outage.city ?: entry.outage.cause ?: "Outage",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${entry.distanceKm.roundToInt()} km",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                listOfNotNull(
                                    if (entry.outage.isPsps) "PSPS" else null,
                                    entry.outage.cause,
                                    entry.outage.estCustomers?.let { "$it customers" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(formatEta(entry.outage.eta), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
