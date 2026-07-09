package com.glazkov.outagewatch.ui.home

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glazkov.outagewatch.ui.formatEta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddLocation: () -> Unit,
    onOpenOutage: (String) -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel { HomeViewModel() },
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OutageWatch") },
                actions = {
                    IconButton(onClick = onOpenNearby) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Nearby outages")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddLocation) {
                Icon(Icons.Default.Add, contentDescription = "Add location")
            }
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.locations.isEmpty() -> EmptyState(Modifier.padding(padding), onAddLocation)

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.locations, key = { it.location.zip }) { status ->
                    LocationCard(status, onOpenOutage)
                }
                item { Disclaimer() }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAddLocation: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Is your power out?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add your ZIP code to see live PG&E outage status and get a push " +
                "notification the moment an outage hits your area.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAddLocation) { Text("Add your ZIP") }
        Spacer(Modifier.height(24.dp))
        Disclaimer()
    }
}

@Composable
private fun LocationCard(status: LocationStatus, onOpenOutage: (String) -> Unit) {
    Card(
        onClick = { status.worstOutage?.let { onOpenOutage(it.id) } },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.location.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                StatusBadge(status)
            }
            Spacer(Modifier.height(4.dp))
            val outage = status.worstOutage
            when {
                status.error -> Text(
                    "Couldn't reach the outage feed. Pull to refresh soon.",
                    style = MaterialTheme.typography.bodySmall,
                )
                outage == null -> Text(
                    "No outages reported in this area.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Column {
                    Text(
                        listOfNotNull(
                            outage.cause?.lowercase()?.replaceFirstChar { it.uppercase() },
                            outage.estCustomers?.let { "$it customers" },
                        ).joinToString(" · ").ifEmpty { "Details pending" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        formatEta(outage.eta),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (status.outages.size > 1) {
                        Text(
                            "+${status.outages.size - 1} more nearby",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: LocationStatus) {
    val (text, color) = when {
        status.error -> "?" to MaterialTheme.colorScheme.outline
        status.worstOutage?.isPsps == true -> "PSPS" to MaterialTheme.colorScheme.error
        status.worstOutage != null -> "OUTAGE" to MaterialTheme.colorScheme.error
        else -> "OK" to MaterialTheme.colorScheme.primary
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun Disclaimer() {
    Text(
        "Not affiliated with PG&E. Data comes from PG&E's public outage map and " +
            "can lag. For emergencies call 911; report downed lines to PG&E at " +
            "1-800-743-5000.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
