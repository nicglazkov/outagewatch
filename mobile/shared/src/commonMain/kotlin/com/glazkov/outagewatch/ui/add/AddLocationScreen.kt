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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.glazkov.outagewatch.data.DeviceLocation
import com.glazkov.outagewatch.data.LocationResult
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.detail.NavBar
import com.glazkov.outagewatch.ui.theme.LocalCompass
import kotlinx.coroutines.launch

@Composable
fun AddLocationScreen(onDone: () -> Unit) {
    val c = LocalCompass.current
    var zip by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun add(useZip: String, useLabel: String) {
        busy = true
        scope.launch {
            val result = AppGraph.locations.addZip(useZip, useLabel)
            busy = false
            result.fold(
                onSuccess = { onDone() },
                onFailure = { error = it.message ?: "Something went wrong" },
            )
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar("Add an area", onDone)
        Column(Modifier.padding(16.dp)) {
            // One-tap: find the user's ZIP from GPS. Easiest path for anyone.
            if (DeviceLocation.available) {
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp))
                        .background(c.accent)
                        .clickable(enabled = !locating && !busy) {
                            locating = true
                            error = null
                            scope.launch {
                                when (val r = DeviceLocation.currentZip()) {
                                    is LocationResult.Found -> { locating = false; add(r.zip, "Home") }
                                    LocationResult.PermissionDenied -> {
                                        locating = false
                                        error = "Location permission is off. Enter your ZIP below."
                                    }
                                    LocationResult.Unavailable -> {
                                        locating = false
                                        error = "Couldn't find your location. Enter your ZIP below."
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (locating) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp
                        )
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
                Spacer(Modifier.height(14.dp))
                Text("or enter a ZIP", color = c.secondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    "Enter a California ZIP code in PG&E territory. You'll get a push " +
                        "notification when an outage starts, when the estimate changes, and " +
                        "when power is back.",
                    color = c.secondary, fontSize = 14.sp,
                )
                Spacer(Modifier.height(16.dp))
            }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.card, unfocusedContainerColor = c.card,
                focusedBorderColor = c.accent, unfocusedBorderColor = c.separator,
                focusedTextColor = c.label, unfocusedTextColor = c.label,
                cursorColor = c.accent,
            )
            OutlinedTextField(
                value = zip,
                onValueChange = { zip = it.filter(Char::isDigit).take(5); error = null },
                label = { Text("ZIP code") },
                isError = error != null,
                supportingText = { error?.let { Text(it, color = c.outage) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
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
            val enabled = zip.length == 5 && !busy && !locating
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) c.accent else c.separator)
                    .clickable(enabled = enabled) { add(zip, label) },
                contentAlignment = Alignment.Center,
            ) {
                if (busy && !locating) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        "Watch this area",
                        color = if (enabled) Color.White else c.secondary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
