package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Upload payload for a terminal Bug Report. [uuid] is the client-generated idempotency key —
 * the server create-only-upserts on it so a retried submit never creates a duplicate row.
 */
data class CreateSmsBugReportRequest(
    @SerializedName("uuid")         val uuid: String,
    @SerializedName("title")        val title: String,
    @SerializedName("description")  val description: String,
    @SerializedName("logs")         val logs: String?,
    @SerializedName("reporterName") val reporterName: String?,
    @SerializedName("terminalCode") val terminalCode: String?,
    @SerializedName("appVersion")   val appVersion: String?,
    @SerializedName("deviceModel")  val deviceModel: String?,
    @SerializedName("screenName")   val screenName: String?
)
