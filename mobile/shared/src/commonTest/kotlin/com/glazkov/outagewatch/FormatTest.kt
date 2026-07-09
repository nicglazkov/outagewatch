package com.glazkov.outagewatch

import com.glazkov.outagewatch.ui.formatEta
import com.glazkov.outagewatch.ui.formatIso
import com.glazkov.outagewatch.ui.nearby.haversineKm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatTest {

    @Test
    fun etaConvertsUtcToPacific() {
        // 23:00 UTC on Jul 9 is 4:00 PM PDT
        assertEquals("Jul 9, 4:00 PM", formatIso("2026-07-09T23:00:00+00:00"))
    }

    @Test
    fun etaCrossingMidnightUtcStaysPreviousDayPacific() {
        // 02:00 UTC Jul 10 is 7:00 PM PDT Jul 9
        assertEquals("Jul 9, 7:00 PM", formatIso("2026-07-10T02:00:00+00:00"))
    }

    @Test
    fun missingEtaHasHonestCopy() {
        assertEquals("No restoration estimate yet", formatEta(null))
    }

    @Test
    fun garbageInputFallsBackToRaw() {
        assertEquals("not-a-date", formatIso("not-a-date"))
    }

    @Test
    fun haversineSfToOakland() {
        val km = haversineKm(37.7749, -122.4194, 37.8044, -122.2712)
        assertTrue(km in 10.0..16.0, "got $km")
    }
}
