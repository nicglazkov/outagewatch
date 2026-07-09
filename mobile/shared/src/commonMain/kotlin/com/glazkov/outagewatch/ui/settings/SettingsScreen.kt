package com.glazkov.outagewatch.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glazkov.outagewatch.data.AlertPrefs
import com.glazkov.outagewatch.ui.AppGraph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = AppGraph.locations
    val prefs by repo.prefs.collectAsState()
    val locations by repo.locations.collectAsState()
    val scope = rememberCoroutineScope()

    var quietEnabled by remember { mutableStateOf(prefs.quietStart != null) }
    var quietStart by remember { mutableStateOf(prefs.quietStart ?: "22:00") }
    var quietEnd by remember { mutableStateOf(prefs.quietEnd ?: "07:00") }
    var psps by remember { mutableStateOf(prefs.pspsWarnings) }

    fun save() {
        scope.launch {
            repo.updatePrefs(
                AlertPrefs(
                    quietStart = if (quietEnabled) quietStart else null,
                    quietEnd = if (quietEnabled) quietEnd else null,
                    pspsWarnings = psps,
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Alerts", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quiet hours")
                    Text(
                        "No alerts overnight. PSPS warnings always come through.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = quietEnabled, onCheckedChange = { quietEnabled = it; save() })
            }
            if (quietEnabled) {
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = quietStart,
                        onValueChange = { quietStart = it.take(5) },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = quietEnd,
                        onValueChange = { quietEnd = it.take(5) },
                        label = { Text("Until") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "24-hour format, e.g. 22:00. Saved when you leave this screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PSPS warnings")
                    Text(
                        "Planned safety shutoff alerts for your areas.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = psps, onCheckedChange = { psps = it; save() })
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Watched locations", style = MaterialTheme.typography.titleMedium)
            locations.forEach { location ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(location.label)
                        Text("ZIP ${location.zip}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { scope.launch { repo.remove(location) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${location.label}")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "OutageWatch is free and account-less. Not affiliated with PG&E.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
