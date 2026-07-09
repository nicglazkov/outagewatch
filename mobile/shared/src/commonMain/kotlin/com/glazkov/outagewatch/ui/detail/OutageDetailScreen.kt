package com.glazkov.outagewatch.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Text
import com.glazkov.outagewatch.ui.formatEta
import com.glazkov.outagewatch.ui.formatIso
import com.glazkov.outagewatch.ui.theme.Cell
import com.glazkov.outagewatch.ui.theme.GroupedFootnote
import com.glazkov.outagewatch.ui.theme.GroupedSection
import com.glazkov.outagewatch.ui.theme.LocalCompass
import com.glazkov.outagewatch.ui.theme.SectionHeader

@Composable
fun OutageDetailScreen(
    outageId: String,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel(key = outageId) { DetailViewModel(outageId) },
) {
    val c = LocalCompass.current
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar(if (state.detail?.outage?.isPsps == true) "PSPS outage" else "Outage", onBack)
        when {
            state.loading -> Column(
                Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator(color = c.accent) }

            state.notFound -> Text(
                "This outage is no longer in PG&E's feed. Power is likely restored.",
                color = c.secondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp),
            )

            else -> {
                val outage = state.detail?.outage ?: return
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Big status header.
                    Column(Modifier.padding(20.dp, 8.dp, 20.dp, 4.dp)) {
                        Text(
                            if (outage.isPsps) "Public Safety Power Shutoff" else "Power outage",
                            color = c.label, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            formatEta(outage.eta), color = c.outage, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
                        )
                        if (state.etaChanges > 0) {
                            Text(
                                etaTrust(state.etaChanges), color = c.secondary, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    SectionHeader("Details")
                    GroupedSection {
                        val facts = buildList {
                            outage.cause?.let { add("Cause" to it) }
                            outage.crewStatus?.let { add("Crew status" to it) }
                            outage.estCustomers?.let { add("Customers affected" to it.toString()) }
                            outage.city?.let { add("City" to it) }
                            outage.startedAt?.let { add("Started" to formatIso(it)) }
                        }
                        facts.forEachIndexed { i, (k, v) ->
                            Cell(title = k, trailing = v, showSeparator = i != facts.lastIndex)
                        }
                    }

                    SectionHeader("What's going on?")
                    GroupedSection {
                        Box(Modifier.padding(16.dp)) {
                            when {
                                state.explanation != null -> Text(
                                    state.explanation!!, color = c.label, fontSize = 15.sp,
                                )
                                state.explainFailed -> Text(
                                    "Explanation unavailable right now.",
                                    color = c.secondary, fontSize = 13.sp,
                                )
                                else -> CircularProgressIndicator(
                                    Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                    GroupedFootnote(
                        "Not affiliated with PG&E. Data can lag. For emergencies call 911; " +
                            "report downed lines to PG&E at 1-800-743-5000.",
                    )
                    Spacer(Modifier.height(24.dp))
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
internal fun NavBar(title: String, onBack: () -> Unit) {
    val c = LocalCompass.current
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable { onBack() }.padding(8.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.accent) }
        Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun etaTrust(changes: Int) = when (changes) {
    1 -> "The estimate has changed once."
    else -> "The estimate has changed $changes times, treat it loosely."
}
