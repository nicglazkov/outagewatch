package com.glazkov.outagewatch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Outage(
    val id: String,
    val cause: String? = null,
    @SerialName("crew_status") val crewStatus: String? = null,
    @SerialName("est_customers") val estCustomers: Int? = null,
    val city: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    val eta: String? = null,
    @SerialName("last_update") val lastUpdate: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("is_psps") val isPsps: Boolean = false,
    val geometry: JsonObject? = null,
)

@Serializable
data class EtaHistoryEntry(
    val eta: String? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

@Serializable
data class OutageDetail(
    val outage: Outage,
    @SerialName("eta_history") val etaHistory: List<EtaHistoryEntry> = emptyList(),
)

@Serializable
data class Explanation(
    @SerialName("outage_id") val outageId: String,
    val explanation: String,
)

@Serializable
data class ZipInfo(
    val zip: String,
    val lat: Double,
    val lon: Double,
    @SerialName("radius_km") val radiusKm: Double,
    val pge: Boolean = true,
    @SerialName("served_by") val servedBy: String? = null,
)

@Serializable
data class AddressSuggestion(
    val id: String,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val zip: String? = null,
    val pge: Boolean = true,
    @SerialName("served_by") val servedBy: String? = null,
)

@Serializable
data class SubscriptionRequest(
    val token: String,
    val platform: String,
    @SerialName("zip_code") val zipCode: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("radius_km") val radiusKm: Double = 1.0,
    val label: String? = null,
    @SerialName("quiet_start") val quietStart: String? = null,
    @SerialName("quiet_end") val quietEnd: String? = null,
    val tz: String = "America/Los_Angeles",
    @SerialName("psps_warnings") val pspsWarnings: Boolean = true,
    // True for an exact address: alert only when the outage covers the point.
    val precise: Boolean = false,
)

@Serializable
data class SubscriptionCreated(val id: String)
