package com.glazkov.outagewatch.ui

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

private val PACIFIC = TimeZone.of("America/Los_Angeles")

fun formatEta(iso: String?): String {
    if (iso == null) return "No restoration estimate yet"
    return "Estimated restoration: ${formatIso(iso)}"
}

/** "2026-07-09T23:00:00+00:00" -> "Jul 9, 4:00 PM" (Pacific). */
fun formatIso(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val local = instant.toLocalDateTime(PACIFIC)
    val hour12 = when (val h = local.hour % 12) {
        0 -> 12
        else -> h
    }
    val ampm = if (local.hour < 12) "AM" else "PM"
    val minute = local.minute.toString().padStart(2, '0')
    return "${MONTHS[local.month.number - 1]} ${local.day}, $hour12:$minute $ampm"
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
