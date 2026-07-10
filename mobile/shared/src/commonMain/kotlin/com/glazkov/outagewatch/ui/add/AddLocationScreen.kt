package com.glazkov.outagewatch.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glazkov.outagewatch.api.AddressSuggestion
import com.glazkov.outagewatch.data.AddOutcome
import com.glazkov.outagewatch.data.DeviceLocation
import com.glazkov.outagewatch.data.LocationResult
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.detail.NavBar
import com.glazkov.outagewatch.ui.theme.LocalCompass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Mode { Address, Zip }

@Composable
fun AddLocationScreen(onDone: () -> Unit) {
    val c = LocalCompass.current
    var mode by remember { mutableStateOf(if (DeviceLocation.available) Mode.Address else Mode.Zip) }
    var address by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Non-PG&E utility name pending a confirm; when set, the button says "Add anyway".
    var warnUtility by remember { mutableStateOf<String?>(null) }
    // Live autocomplete state.
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var picked by remember { mutableStateOf<AddressSuggestion?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Bias suggestions toward the user's first saved area, if they have one.
    val bias = remember { AppGraph.locations.locations.value.firstOrNull() }

    // Debounced autocomplete: re-keys on every keystroke, so the delay below
    // cancels the previous in-flight query and only the last one lands.
    LaunchedEffect(address, mode, picked) {
        if (mode != Mode.Address || picked != null || address.trim().length < 3) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(180)
        suggestions = runCatching {
            AppGraph.api.autocomplete(address.trim(), bias?.lat, bias?.lon)
        }.getOrDefault(emptyList())
        searching = false
    }

    fun handle(outcome: AddOutcome) {
        busy = false
        when (outcome) {
            is AddOutcome.Added -> onDone()
            is AddOutcome.NotServed -> {
                warnUtility = outcome.utility
                error = "This area is served by ${outcome.utility}, not PG&E. " +
                    "OutageWatch won't show outages here."
            }
            is AddOutcome.Failed -> error = outcome.message
        }
    }

    fun submit(force: Boolean) {
        // Dismiss the keyboard before we navigate away on success; disposing a
        // focused text field mid-navigation with the IME open can wedge the UI.
        focus.clearFocus()
        busy = true
        error = null
        scope.launch {
            val outcome = when (mode) {
                Mode.Address -> picked?.let { AppGraph.locations.addSuggestion(it, label, force) }
                    ?: AppGraph.locations.addAddress(address, label, force)
                Mode.Zip -> AppGraph.locations.addZip(zip, label, force)
            }
            handle(outcome)
        }
    }

    fun choose(s: AddressSuggestion) {
        picked = s
        address = s.title
        suggestions = emptyList()
        warnUtility = null
        error = null
        focus.clearFocus() // dismiss the keyboard on pick, like Google Maps
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar("Add an area", onDone)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            if (DeviceLocation.available) {
                LocationButton(locating || busy) {
                    locating = true
                    error = null
                    warnUtility = null
                    scope.launch {
                        when (val r = DeviceLocation.currentZip()) {
                            is LocationResult.Found -> {
                                locating = false
                                busy = true
                                handle(AppGraph.locations.addZip(r.zip, "Home"))
                            }
                            LocationResult.PermissionDenied -> {
                                locating = false
                                error = "Location permission is off. Add an address or ZIP below."
                            }
                            LocationResult.Unavailable -> {
                                locating = false
                                error = "Couldn't find your location. Add an address or ZIP below."
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Address vs ZIP toggle.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.card).padding(3.dp),
            ) {
                SegTab("Address", mode == Mode.Address, Modifier.weight(1f)) {
                    mode = Mode.Address; error = null; warnUtility = null
                }
                SegTab("ZIP / region", mode == Mode.Zip, Modifier.weight(1f)) {
                    mode = Mode.Zip; error = null; warnUtility = null; suggestions = emptyList()
                }
            }
            Spacer(Modifier.height(14.dp))

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.card, unfocusedContainerColor = c.card,
                focusedBorderColor = c.accent, unfocusedBorderColor = c.separator,
                focusedTextColor = c.label, unfocusedTextColor = c.label, cursorColor = c.accent,
            )
            if (mode == Mode.Address) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; picked = null; error = null; warnUtility = null },
                    label = { Text("Street address or place") },
                    placeholder = { Text("123 Main St, Santa Rosa") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    trailingIcon = {
                        if (searching) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), color = c.secondary, strokeWidth = 2.dp,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    SuggestionList(suggestions, ::choose)
                }
            } else {
                OutlinedTextField(
                    value = zip,
                    onValueChange = { zip = it.filter(Char::isDigit).take(5); error = null; warnUtility = null },
                    label = { Text("ZIP code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = c.outage, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(40) },
                label = { Text("Name it (Home, Cabin, Mom's place...)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            val ready = if (mode == Mode.Address) address.isNotBlank() else zip.length == 5
            val enabled = ready && !busy && !locating
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) (if (warnUtility != null) c.outage else c.accent) else c.separator)
                    .clickable(enabled = enabled) { submit(force = warnUtility != null) },
                contentAlignment = Alignment.Center,
            ) {
                if (busy && !locating) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (warnUtility != null) "Add anyway" else "Watch this area",
                        color = if (enabled) Color.White else c.secondary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(items: List<AddressSuggestion>, onPick: (AddressSuggestion) -> Unit) {
    val c = LocalCompass.current
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) {
        items.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocationOn, contentDescription = null,
                    tint = if (s.pge) c.accent else c.outage, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = if (s.servedBy != null) "${s.subtitle} · not PG&E" else s.subtitle
                    if (sub.isNotBlank()) {
                        Text(
                            sub, color = if (s.servedBy != null) c.outage else c.secondary,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (i != items.lastIndex) {
                HorizontalDivider(
                    color = c.separator, thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 46.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationButton(loading: Boolean, onClick: () -> Unit) {
    val c = LocalCompass.current
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
            .clickable(enabled = !loading) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Use my current location", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SegTab(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCompass.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.background else Color.Transparent)
            .clickable { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (selected) c.label else c.secondary,
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
