package com.glazkov.outagewatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glazkov.outagewatch.data.SavedLocation
import com.glazkov.outagewatch.ui.formatEta
import com.glazkov.outagewatch.ui.map.OutageMapView
import com.glazkov.outagewatch.ui.theme.Cell
import com.glazkov.outagewatch.ui.theme.GroupedFootnote
import com.glazkov.outagewatch.ui.theme.GroupedSection
import com.glazkov.outagewatch.ui.theme.LargeTitle
import com.glazkov.outagewatch.ui.theme.LocalCompass
import com.glazkov.outagewatch.ui.theme.SectionHeader

private const val DISCLAIMER =
    "Not affiliated with PG&E. Data comes from PG&E's public outage map and can " +
        "lag. For emergencies call 911; report downed lines to PG&E at 1-800-743-5000."

@Composable
fun HomeScreen(
    onAddLocation: () -> Unit,
    onOpenZip: (SavedLocation) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel { HomeViewModel() },
) {
    val c = LocalCompass.current
    val state by viewModel.state.collectAsState()
    val dark = isSystemInDarkTheme()

    Box(Modifier.fillMaxSize().background(c.background)) {
        when {
            state.loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator(color = c.accent) }

            state.locations.isEmpty() -> EmptyHome(onAddLocation)

            else -> Column(Modifier.fillMaxSize()) {
                // Map hero: a tappable preview centered on the primary area.
                val center = state.mapCenter
                if (center != null) {
                    Box(Modifier.fillMaxWidth().height(250.dp)) {
                        OutageMapView(
                            centerLat = center.lat,
                            centerLon = center.lon,
                            radiusKm = center.radiusKm,
                            outages = state.mapOutages,
                            dark = dark,
                            onOutageTap = { onOpenZip(center) },
                            modifier = Modifier.fillMaxSize(),
                            zoomControl = false,
                        )
                        // Transparent overlay: home map is a preview; tap opens full map.
                        Box(Modifier.fillMaxSize().clickable { onOpenZip(center) })
                        // Legibility scrim so title + gear read over any map tile.
                        Box(
                            Modifier.fillMaxWidth().height(96.dp).background(
                                Brush.verticalGradient(
                                    listOf(Color(0x66000000), Color(0x00000000))
                                )
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                                .padding(start = 18.dp, end = 16.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "OutageWatch", color = Color.White, fontSize = 26.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.Settings, contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp).clickable { onOpenSettings() },
                            )
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().weight(1f)
                        .offset(y = (-18).dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(c.background)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(6.dp))
                    SummaryCell(state.affectedCount, state.locations.size)
                    SectionHeader("Your areas")
                    GroupedSection {
                        state.locations.forEachIndexed { i, status ->
                            AreaCell(status, last = i == state.locations.lastIndex, onOpenZip)
                        }
                    }
                    GroupedFootnote(DISCLAIMER)
                    Spacer(Modifier.height(90.dp))
                }
            }
        }

        if (state.locations.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(20.dp)
                    .size(56.dp).clip(RoundedCornerShape(28.dp))
                    .background(c.accent).clickable { onAddLocation() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, contentDescription = "Add location", tint = Color.White) }
        }
    }
}

@Composable
private fun SummaryCell(affected: Int, total: Int) {
    val c = LocalCompass.current
    val (emoji, tint, text) = if (affected > 0) {
        Triple("⚡", c.outageTint, "$affected of $total ${plural(total, "area")} affected")
    } else {
        Triple("✓", c.clearTint, "All $total ${plural(total, "area")} clear")
    }
    GroupedSection {
        Cell(
            title = text,
            leadingEmoji = emoji,
            leadingTint = tint,
            showSeparator = false,
        )
    }
}

@Composable
private fun AreaCell(status: LocationStatus, last: Boolean, onOpenZip: (SavedLocation) -> Unit) {
    val c = LocalCompass.current
    val outage = status.worstOutage
    val subtitle = when {
        status.error -> "Couldn't reach the feed"
        outage == null -> "No outages"
        else -> listOfNotNull(
            outage.cause?.lowercase()?.replaceFirstChar { it.uppercase() },
            outage.eta?.let { "back " + formatEta(it).removePrefix("Estimated restoration: ").lowercase() },
        ).joinToString(" · ").ifEmpty { "Outage reported" }
    }
    Cell(
        title = status.location.label,
        subtitle = subtitle,
        leadingEmoji = if (status.isOut) "⚡" else "✓",
        leadingTint = if (status.isOut) c.outageTint else c.clearTint,
        trailing = when {
            status.error -> "?"
            status.isOut -> if (outage?.isPsps == true) "PSPS" else "Out"
            else -> "Clear"
        },
        trailingColor = if (status.isOut) c.outage else c.clear,
        chevron = true,
        showSeparator = !last,
        onClick = { onOpenZip(status.location) },
    )
}

@Composable
private fun EmptyHome(onAddLocation: () -> Unit) {
    val c = LocalCompass.current
    Column(Modifier.fillMaxSize()) {
        LargeTitle("OutageWatch")
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Is your power out?", color = c.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your ZIP code to see live PG&E outage status and get a push " +
                    "notification the moment an outage hits your area.",
                color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(c.accent)
                    .clickable { onAddLocation() }
                    .padding(horizontal = 28.dp, vertical = 13.dp),
            ) { Text("Add your ZIP", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(28.dp))
            Text(
                DISCLAIMER, color = c.secondary, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"
