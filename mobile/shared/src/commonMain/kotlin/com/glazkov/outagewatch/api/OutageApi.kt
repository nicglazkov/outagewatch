package com.glazkov.outagewatch.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ApiConfig {
    // Cloud Run URL for the outagewatch-api service; updated at deploy time.
    var baseUrl: String = "https://outagewatch-api-7bi2fdpqrq-uw.a.run.app"
}

/** A GitHub release, as much of it as the update check needs. */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

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

    /** Null means the outage is gone (404, likely restored); throws on network/server error. */
    suspend fun outageDetail(id: String): OutageDetail? {
        val response = client.get("${ApiConfig.baseUrl}/v1/outages/$id")
        return when (response.status) {
            HttpStatusCode.OK -> response.body()
            HttpStatusCode.NotFound -> null
            else -> throw IllegalStateException("outage detail failed: ${response.status}")
        }
    }

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

    /**
     * Release a subscription. The backend only deletes it if [deviceToken] owns
     * it, so a leaked id alone can't unsubscribe someone else's device.
     */
    suspend fun unsubscribe(subscriptionId: String, deviceToken: String?) {
        client.delete("${ApiConfig.baseUrl}/v1/subscriptions/$subscriptionId") {
            if (deviceToken != null) header("X-Device-Token", deviceToken)
        }
    }

    /**
     * The latest published GitHub release, used for the in-app update check.
     * Returns null on any error so a failed check never disrupts the app.
     */
    suspend fun latestRelease(): ReleaseInfo? {
        val resp = client.get("https://api.github.com/repos/nicglazkov/outagewatch/releases/latest") {
            header("Accept", "application/vnd.github+json")
        }
        return if (resp.status == HttpStatusCode.OK) resp.body() else null
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient {
            expectSuccess = false
            // No call may hang forever: a dead/slow network fails fast so the UI
            // can show an error instead of an endless spinner.
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 12_000
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
        }
    }
}
