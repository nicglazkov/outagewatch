package com.glazkov.outagewatch.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object ApiConfig {
    // Cloud Run URL for the outagewatch-api service; updated at deploy time.
    var baseUrl: String = "https://outagewatch-api-7bi2fdpqrq-uw.a.run.app"
}

class OutageApi(private val client: HttpClient = defaultClient()) {

    suspend fun outagesNear(
        lat: Double,
        lon: Double,
        radiusKm: Double = 10.0,
        includeGeometry: Boolean = false,
    ): List<Outage> =
        client.get("${ApiConfig.baseUrl}/v1/outages") {
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("radius_km", radiusKm)
            if (includeGeometry) parameter("include_geometry", true)
        }.body()

    suspend fun outagesForZip(zip: String): List<Outage> =
        client.get("${ApiConfig.baseUrl}/v1/outages") { parameter("zip", zip) }.body()

    suspend fun outageDetail(id: String): OutageDetail =
        client.get("${ApiConfig.baseUrl}/v1/outages/$id").body()

    suspend fun explain(id: String): Explanation =
        client.get("${ApiConfig.baseUrl}/v1/outages/$id/explain").body()

    /**
     * Google-Maps-style address suggestions as the user types. Biased toward
     * [lat]/[lon] when given. Returns empty on any error so typing never throws.
     */
    suspend fun autocomplete(
        query: String,
        lat: Double? = null,
        lon: Double? = null,
    ): List<AddressSuggestion> {
        val response = client.get("${ApiConfig.baseUrl}/v1/geocode/autocomplete") {
            parameter("q", query)
            if (lat != null) parameter("lat", lat)
            if (lon != null) parameter("lon", lon)
        }
        return if (response.status == HttpStatusCode.OK) response.body() else emptyList()
    }

    /** Null when the ZIP is not a known California ZIP. */
    suspend fun zipInfo(zip: String): ZipInfo? {
        val response = client.get("${ApiConfig.baseUrl}/v1/zips/$zip")
        return if (response.status == HttpStatusCode.OK) response.body() else null
    }

    suspend fun subscribe(request: SubscriptionRequest): SubscriptionCreated =
        client.post("${ApiConfig.baseUrl}/v1/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun unsubscribe(subscriptionId: String) {
        client.delete("${ApiConfig.baseUrl}/v1/subscriptions/$subscriptionId")
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
        }
    }
}
