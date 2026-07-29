package com.glazkov.outagewatch

import com.glazkov.outagewatch.api.AddressSuggestion
import com.glazkov.outagewatch.api.Explanation
import com.glazkov.outagewatch.api.Outage
import com.glazkov.outagewatch.api.OutageApi
import com.glazkov.outagewatch.api.OutageDetail
import com.glazkov.outagewatch.api.ReleaseInfo
import com.glazkov.outagewatch.api.SubscriptionCreated
import com.glazkov.outagewatch.api.SubscriptionRequest
import com.glazkov.outagewatch.api.ZipInfo
import com.glazkov.outagewatch.data.LocationsRepository
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Exercises the repository against a fake backend. No push token is ever set,
 * so subscribeFor() short-circuits and none of these touch the network.
 */
class LocationsRepositoryTest {

    /** Every ZIP is a covered PG&E ZIP; nothing else is called. */
    private class FakeApi : OutageApi {
        override suspend fun outagesNear(
            lat: Double,
            lon: Double,
            radiusKm: Double,
            includeGeometry: Boolean,
        ): List<Outage> = emptyList()

        override suspend fun outagesForZip(zip: String): List<Outage> = emptyList()
        override suspend fun outageDetail(id: String): OutageDetail? = null
        override suspend fun explain(id: String) = Explanation(outageId = id, explanation = "")
        override suspend fun autocomplete(
            query: String,
            lat: Double?,
            lon: Double?,
        ): List<AddressSuggestion> = emptyList()

        override suspend fun zipInfo(zip: String) =
            ZipInfo(zip = zip, lat = 37.0, lon = -122.0, radiusKm = 3.0, pge = true)

        override suspend fun subscribe(request: SubscriptionRequest) = SubscriptionCreated("sub")
        override suspend fun unsubscribe(subscriptionId: String, deviceToken: String?) = Unit
        override suspend fun latestRelease(): ReleaseInfo? = null
    }

    private fun repo() = LocationsRepository(FakeApi(), MapSettings())

    private suspend fun LocationsRepository.seed() {
        addZip("95336", "First")
        addZip("94102", "Second")
        addZip("96001", "Third")
    }

    @Test
    fun addingPlacesKeepsThemInTheOrderTheyWereAdded() = runTest {
        val repo = repo()
        repo.seed()
        assertEquals(listOf("First", "Second", "Third"), repo.locations.value.map { it.label })
    }

    @Test
    fun editingAPlaceLeavesItWhereItIs() = runTest {
        val repo = repo()
        repo.seed()
        val first = repo.locations.value.first()

        repo.setAreaAlerts(first, enabled = false)

        // Regression: commit() used to drop the place and re-append it, so
        // toggling the top row sent it to the bottom of the list.
        assertEquals(listOf("First", "Second", "Third"), repo.locations.value.map { it.label })
        assertEquals(false, repo.locations.value.first().areaAlerts)
    }

    @Test
    fun removingAPlaceLeavesTheRestInOrder() = runTest {
        val repo = repo()
        repo.seed()

        repo.remove(repo.locations.value[1])

        assertEquals(listOf("First", "Third"), repo.locations.value.map { it.label })
    }
}
