package com.glazkov.outagewatch.ui.zip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glazkov.outagewatch.ui.customersLine
import com.glazkov.outagewatch.ui.detail.NavBar
import com.glazkov.outagewatch.ui.etaBack
import com.glazkov.outagewatch.ui.map.OutageMapView
import com.glazkov.outagewatch.ui.theme.Cell
import com.glazkov.outagewatch.ui.theme.GroupedSection
import com.glazkov.outagewatch.ui.theme.LocalCompass
import com.glazkov.outagewatch.ui.theme.SectionHeader
import kotlin.math.roundToInt

@Composable
fun ZipDetailScreen(
    zip: String,
    label: String,
    lat: Double,
    lon: Double,
    radiusKm: Double,
    onOpenOutage: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ZipViewModel = viewModel(key = "$zip:$lat,$lon") {
        ZipViewModel(zip, lat, lon, radiusKm)
    },
) {
    val c = LocalCompass.current
    val state by viewModel.state.collectAsState()
    val dark = isSystemInDarkTheme()
    // Tapping an outage row flies the map to it (below) instead of navigating.
    // Token is "<id>#<nonce>" so tapping the same row again re-triggers the fly.
    var focusToken by remember { mutableStateOf<String?>(null) }
    val focusNonce = remember { intArrayOf(0) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar(label, onBack)
        if (state.loading) {
            Column(
                Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator(color = c.accent) }
            return@Column
        }
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            OutageMapView(
                centerLat = lat, centerLon = lon, radiusKm = radiusKm,
                outages = state.areaOutages.map { it.outage }, dark = dark,
                onOutageTap = onOpenOutage, modifier = Modifier.fillMaxSize(),
                focusToken = focusToken,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.error) {
                Text(
                    "Couldn't load outages right now. Check your connection.",
                    color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 8.dp),
                )
                Box(
                    Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp))
                        .background(c.accent).clickable { viewModel.reload() }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text("Try again", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    summaryLine(state), color = c.label, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                )
                if (state.areaOutages.isEmpty()) {
                    Text(
                        "Nothing reported within ${state.contextRadiusKm.roundToInt()} km. You'll " +
                            "get a push the moment that changes.",
                        color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 8.dp),
                    )
                } else {
                    SectionHeader("Outages")
                    GroupedSection {
                        state.areaOutages.forEachIndexed { i, entry ->
                            val o = entry.outage
                            Cell(
                                title = if (o.isPsps) "PSPS shutoff" else "Power outage",
                                subtitle = listOfNotNull(
                                    o.cause, o.city, customersLine(o.estCustomers), etaBack(o.eta),
                                ).joinToString(" · ").ifEmpty { "Details pending" },
                                leadingEmoji = if (o.isPsps) "⚠️" else "⚡",
                                leadingTint = c.outageTint,
                                trailing = when {
                                    entry.inZip -> "here"
                                    entry.distanceKm != null -> "${entry.distanceKm.roundToInt()} km"
                                    else -> "nearby"
                                },
                                trailingColor = if (entry.inZip) c.outage else c.secondary,
                                chevron = true,
                                showSeparator = i != state.areaOutages.lastIndex,
                                onClick = {
                                    focusNonce[0]++
                                    focusToken = "${o.id}#${focusNonce[0]}"
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private fun summaryLine(state: ZipState): String {
    val inZip = state.areaOutages.count { it.inZip }
    val nearby = state.areaOutages.size - inZip
    return when {
        inZip > 0 && nearby > 0 -> "$inZip in your area · $nearby more nearby"
        inZip > 0 -> "$inZip outage${if (inZip > 1) "s" else ""} in your area"
        nearby > 0 -> "Your area is clear · $nearby nearby"
        else -> "No outages in or near your area"
    }
}
