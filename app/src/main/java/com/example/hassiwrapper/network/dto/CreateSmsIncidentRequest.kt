package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Upload payload for an SMS spool-damage incident created offline on the terminal.
 * [uuid] is the client-generated idempotency key — the server must upsert on it so
 * retries (best-effort uploads have no dedup on the client) never create duplicates.
 */
data class CreateSmsIncidentRequest(
    @SerializedName("uuid")           val uuid: String,
    @SerializedName("projectCode")    val projectCode: String,
    @SerializedName("spoolCode")      val spoolCode: String,
    @SerializedName("spoolSuffix")    val spoolSuffix: String?,
    @SerializedName("description")    val description: String,
    @SerializedName("vehiclePlate")   val vehiclePlate: String?,
    @SerializedName("locationType")   val locationType: String,
    @SerializedName("locationDetail") val locationDetail: String?,
    @SerializedName("severity")       val severity: String,
    @SerializedName("positionId")     val positionId: Int?,
    @SerializedName("subPositionId")  val subPositionId: Long?,
    @SerializedName("positionCode")   val positionCode: String?,
    @SerializedName("authorName")     val authorName: String?,
    @SerializedName("eventDate")      val eventDate: String,
    @SerializedName("status")         val status: String,
    @SerializedName("closedBy")       val closedBy: String?,
    @SerializedName("closedAt")       val closedAt: String?
)
