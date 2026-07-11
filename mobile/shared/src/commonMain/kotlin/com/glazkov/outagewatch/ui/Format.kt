package com.glazkov.outagewatch.ui

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

private val PACIFIC = TimeZone.of("America/Los_Angeles")

/** Great-circle distance in kilometers. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0088
    val p1 = lat1 * PI / 180
    val p2 = lat2 * PI / 180
    val dp = (lat2 - lat1) * PI / 180
    val dl = (lon2 - lon1) * PI / 180
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** Full restoration line for the detail header. */
fun formatEta(iso: String?): String {
    if (iso == null) return "No restoration estimate yet"
    if (isPast(iso)) return "Estimate has passed, PG&E hasn't posted a new one"
    return "Estimated restoration: ${formatIso(iso)}"
}

/** Compact "back <time>" for list rows, or null when there's nothing useful to show. */
fun etaBack(iso: String?): String? {
    if (iso == null) return null
    if (isPast(iso)) return "estimate passed"
    return "back " + formatIso(iso).lowercase()
}

/** 1200 -> "1,200". */
fun groupedNumber(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

/** "1 customer", "1,200 customers", or null. */
fun customersLine(n: Int?): String? {
    if (n == null || n < 0) return null
    return if (n == 1) "1 customer" else "${groupedNumber(n)} customers"
}

@OptIn(ExperimentalTime::class)
private fun isPast(iso: String): Boolean {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return false
    return instant < Clock.System.now()
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
