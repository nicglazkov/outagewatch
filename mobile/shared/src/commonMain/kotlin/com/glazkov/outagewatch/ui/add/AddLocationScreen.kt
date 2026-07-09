package com.glazkov.outagewatch.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.background)) {
        NavBar("Add a location", onDone)
        Column(Modifier.padding(16.dp)) {
            Text(
                "Enter a California ZIP code in PG&E territory. You'll get a push " +
                    "notification when an outage starts there, when the restoration estimate " +
                    "changes, and when power is back.",
                color = c.secondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
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
                label = { Text("Label (Home, Cabin, Mom's place...)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            val enabled = zip.length == 5 && !busy
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) c.accent else c.separator)
                    .clickable(enabled = enabled) {
                        busy = true
                        scope.launch {
                            val result = AppGraph.locations.addZip(zip, label)
                            busy = false
                            result.fold(
                                onSuccess = { onDone() },
                                onFailure = { error = it.message ?: "Something went wrong" },
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
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
