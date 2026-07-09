package com.glazkov.outagewatch.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glazkov.outagewatch.data.AlertPrefs
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
                    Row(Modifier.padding(16.dp, 10.dp)) {
                        val fc = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = c.background, unfocusedContainerColor = c.background,
                            focusedBorderColor = c.accent, unfocusedBorderColor = c.separator,
                            focusedTextColor = c.label, unfocusedTextColor = c.label, cursorColor = c.accent,
                        )
                        OutlinedTextField(
                            quietStart, { quietStart = it.take(5) }, label = { Text("From") },
                            singleLine = true, colors = fc, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            quietEnd, { quietEnd = it.take(5) }, label = { Text("Until") },
                            singleLine = true, colors = fc, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
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
                GroupedFootnote("24-hour format, e.g. 22:00. Saved when you leave this screen.")
            }

            SectionHeader("Watched locations")
            GroupedSection {
                if (locations.isEmpty()) {
                    Cell(title = "No locations yet", showSeparator = false)
                } else {
                    locations.forEachIndexed { i, location ->
                        Cell(
                            title = location.label,
                            subtitle = "ZIP ${location.zip}",
                            trailing = "Remove",
                            trailingColor = c.outage,
                            showSeparator = i != locations.lastIndex,
                            onClick = { scope.launch { repo.remove(location) } },
                        )
                    }
                }
            }
            GroupedFootnote("OutageWatch is free and account-less. Not affiliated with PG&E.")
            Spacer(Modifier.height(24.dp))
        }
    }
}
