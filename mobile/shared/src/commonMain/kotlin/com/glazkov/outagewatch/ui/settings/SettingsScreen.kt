package com.glazkov.outagewatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glazkov.outagewatch.data.AlertPrefs
import com.glazkov.outagewatch.data.SavedLocation
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.detail.NavBar
import com.glazkov.outagewatch.ui.theme.Cell
import com.glazkov.outagewatch.ui.theme.GroupedFootnote
import com.glazkov.outagewatch.ui.theme.GroupedSection
import com.glazkov.outagewatch.ui.theme.LocalCompass
import com.glazkov.outagewatch.ui.theme.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalCompass.current
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

    var pendingRemove by remember { mutableStateOf<SavedLocation?>(null) }

    val switchColors = SwitchDefaults.colors(
        checkedTrackColor = c.clear, checkedThumbColor = Color.White,
        uncheckedTrackColor = c.separator, uncheckedThumbColor = Color.White,
        uncheckedBorderColor = Color.Transparent,
    )

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Alerts")
            GroupedSection {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Quiet hours", color = c.label, fontSize = 16.sp)
                        Text(
                            "No alerts overnight. PSPS warnings always come through.",
                            color = c.secondary, fontSize = 13.sp,
                        )
                    }
                    Switch(quietEnabled, { quietEnabled = it; save() }, colors = switchColors)
                }
                if (quietEnabled) {
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.separator))
                    Row(Modifier.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TimeField("From", quietStart, Modifier.weight(1f)) { quietStart = it; save() }
                        Spacer(Modifier.width(10.dp))
                        TimeField("Until", quietEnd, Modifier.weight(1f)) { quietEnd = it; save() }
                    }
                }
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.separator))
                Row(
                    Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("PSPS warnings", color = c.label, fontSize = 16.sp)
                        Text(
                            "Planned safety shutoff alerts for your areas.",
                            color = c.secondary, fontSize = 13.sp,
                        )
                    }
                    Switch(psps, { psps = it; save() }, colors = switchColors)
                }
            }
            if (quietEnabled) {
                GroupedFootnote("Tap a time to change it. Saved automatically.")
            }

            SectionHeader("Watched locations")
            GroupedSection {
                if (locations.isEmpty()) {
                    Cell(title = "No locations yet", showSeparator = false)
                } else {
                    locations.forEachIndexed { i, location ->
                        Cell(
                            title = location.label,
                            subtitle = when {
                                location.zip.length != 5 -> "Saved address"
                                location.label == "ZIP ${location.zip}" -> null
                                else -> "ZIP ${location.zip}"
                            },
                            trailing = "Remove",
                            trailingColor = c.outage,
                            showSeparator = i != locations.lastIndex,
                            onClick = { pendingRemove = location },
                        )
                    }
                }
            }
            GroupedFootnote("OutageWatch is free and account-less. Not affiliated with PG&E.")
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    // Confirm before deleting so a mis-tap doesn't silently drop a watched area.
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove ${target.label}?") },
            text = { Text("You'll stop getting outage alerts for this area.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch { repo.remove(target) }
                }) { Text("Remove", color = c.outage) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Keep") }
            },
        )
    }
}

/**
 * A tappable time chip that opens a wheel time picker. Typing HH:MM by hand
 * scrambled on reformat, and the number pad has no colon key, so this is both
 * more robust and easier for a non-technical user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onPick: (String) -> Unit) {
    val c = LocalCompass.current
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.background)
            .clickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = c.secondary, fontSize = 12.sp)
        Text(value.ifBlank { "--:--" }, color = c.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
    if (open) {
        val parts = value.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    val hh = state.hour.toString().padStart(2, '0')
                    val mm = state.minute.toString().padStart(2, '0')
                    onPick("$hh:$mm")
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
