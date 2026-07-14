package com.glazkov.outagewatch

import com.glazkov.outagewatch.update.AppUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {

    // A tiny stand-in for the persisted "last notified version".
    private class Store(var notified: String? = null)

    private fun decide(current: String, latestTag: String?, store: Store) =
        AppUpdate.decide(current, latestTag, "https://example/rel", store.notified) { store.notified = it }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(AppUpdate.compareVersions("0.2.3", "0.2.2") > 0)
        assertTrue(AppUpdate.compareVersions("0.2.2", "0.2.3") < 0)
        assertEquals(0, AppUpdate.compareVersions("0.2.3", "0.2.3"))
        // Numeric, not lexicographic: 0.2.10 is newer than 0.2.9.
        assertTrue(AppUpdate.compareVersions("0.2.10", "0.2.9") > 0)
        assertTrue(AppUpdate.compareVersions("1.0", "0.9.9") > 0)
    }

    @Test
    fun promptsWhenNewerAndNotYetShown() {
        val a = decide("0.2.2", "v0.2.3", Store())
        assertEquals("0.2.3", a?.version)
        assertEquals("https://example/rel", a?.url)
    }

    @Test
    fun doesNotPromptWhenUpToDateOrOlder() {
        assertNull(decide("0.2.3", "v0.2.3", Store()))
        assertNull(decide("0.2.3", "v0.2.2", Store()))
    }

    @Test
    fun promptsOnceAndOnlyOncePerVersion() {
        val store = Store()
        // First open with the new version: prompt.
        assertEquals("0.2.3", decide("0.2.2", "v0.2.3", store)?.version)
        // Every later open with the same latest version: no prompt.
        assertNull(decide("0.2.2", "v0.2.3", store))
        assertNull(decide("0.2.2", "v0.2.3", store))
        // A brand new version prompts again, once.
        assertEquals("0.2.4", decide("0.2.2", "v0.2.4", store)?.version)
        assertNull(decide("0.2.2", "v0.2.4", store))
    }

    @Test
    fun blankCurrentVersionNeverPrompts() {
        assertNull(decide("", "v9.9.9", Store()))
    }
}
