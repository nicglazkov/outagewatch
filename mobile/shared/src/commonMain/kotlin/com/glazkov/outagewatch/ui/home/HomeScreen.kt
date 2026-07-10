package com.glazkov.outagewatch.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
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
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.formatEta
import com.glazkov.outagewatch.ui.map.OutageMapView
import com.glazkov.outagewatch.ui.theme.Cell
import com.glazkov.outagewatch.ui.theme.GroupedFootnote
import com.glazkov.outagewatch.ui.theme.LargeTitle
import com.glazkov.outagewatch.ui.theme.LocalCompass
import com.glazkov.outagewatch.ui.theme.SectionHeader
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(c.background)) {
        when {
            state.loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator(color = c.accent) }

            state.locations.isEmpty() -> EmptyHome(onAddLocation)

            else -> Column(Modifier.fillMaxSize()) {
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
                        Box(Modifier.fillMaxSize().clickable { onOpenZip(center) })
                        Box(
                            Modifier.fillMaxWidth().height(110.dp).background(
                                Brush.verticalGradient(listOf(Color(0x73000000), Color(0x00000000)))
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                                .padding(start = 18.dp, end = 6.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "OutageWatch", color = Color.White, fontSize = 26.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            )
                            // Padded hit area so the gear is an easy ~46dp target, not a 24dp icon.
                            Box(
                                Modifier.clip(RoundedCornerShape(22.dp))
                                    .clickable { onOpenSettings() }.padding(11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Settings, contentDescription = "Settings",
                                    tint = Color.White, modifier = Modifier.size(24.dp),
                                )
                            }
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
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp),
                    ) {
                        Text(
                            "YOUR AREAS", color = c.secondary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        Text("Swipe a row to remove", color = c.tertiary, fontSize = 11.sp)
                    }
                    Column(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)).background(c.card),
                    ) {
                        state.locations.forEachIndexed { i, status ->
                            key(status.location.id) {
                                RemovableAreaCell(
                                    status = status,
                                    last = i == state.locations.lastIndex,
                                    onOpenZip = onOpenZip,
                                    onRemove = {
                                        scope.launch { AppGraph.locations.remove(status.location) }
                                    },
                                )
                            }
                        }
                    }
                    GroupedFootnote(DISCLAIMER)
                    Spacer(Modifier.height(96.dp))
                }
            }
        }

        if (state.locations.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp)
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
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(c.card),
    ) {
        Cell(title = text, leadingEmoji = emoji, leadingTint = tint, showSeparator = false)
    }
}

@Composable
private fun RemovableAreaCell(
    status: LocationStatus,
    last: Boolean,
    onOpenZip: (SavedLocation) -> Unit,
    onRemove: () -> Unit,
) {
    val c = LocalCompass.current
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(c.outage).padding(end = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White) }
        },
    ) {
        Box(Modifier.background(c.card)) {
            AreaCell(status, last, onOpenZip)
        }
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
            outage.eta?.let {
                "back " + formatEta(it).removePrefix("Estimated restoration: ").lowercase()
            },
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        LargeTitle("OutageWatch")
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Is your power out?", color = c.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your area to see live PG&E outage status and get a push notification " +
                    "the moment an outage hits you.",
                color = c.secondary, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(c.accent)
                    .clickable { onAddLocation() }
                    .padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Text(
                    "Add your area", color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(DISCLAIMER, color = c.secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"
