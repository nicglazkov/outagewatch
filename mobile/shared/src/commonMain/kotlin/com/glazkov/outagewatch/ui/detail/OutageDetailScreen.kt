package com.glazkov.outagewatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.glazkov.outagewatch.ui.formatIso
import com.glazkov.outagewatch.ui.home.Disclaimer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutageDetailScreen(
    outageId: String,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel(key = outageId) { DetailViewModel(outageId) },
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outage details") },
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

            state.notFound -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text("This outage is no longer in PG&E's feed - power is likely restored.")
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val outage = state.detail?.outage ?: return@LazyColumn
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (outage.isPsps) "Public Safety Power Shutoff" else "Power outage",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Fact("Cause", outage.cause)
                            Fact("Crew status", outage.crewStatus)
                            Fact("Customers affected", outage.estCustomers?.toString())
                            Fact("City", outage.city)
                            Fact("Started", outage.startedAt?.let(::formatIso))
                            Text(
                                formatEta(outage.eta),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            EtaTrust(state.etaChanges)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("What's going on?", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            when {
                                state.explanation != null -> Text(
                                    state.explanation!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                state.explainFailed -> Text(
                                    "Explanation unavailable right now.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                else -> CircularProgressIndicator(Modifier.height(20.dp))
                            }
                        }
                    }
                }
                item { Disclaimer() }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun EtaTrust(changes: Int) {
    val text = when {
        changes <= 0 -> return
        changes == 1 -> "The estimate has changed once."
        else -> "The estimate has changed $changes times - treat it loosely."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}
