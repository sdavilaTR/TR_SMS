package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

data class UpdatePackingListRequest(
    @SerializedName("packingListId")   val packingListId: Long,
    @SerializedName("packingListName") val packingListName: String,
    @SerializedName("vehicle")         val vehicle: String?,
    @SerializedName("position")        val position: String?,
    @SerializedName("positionId")      val positionId: Int?,
    @SerializedName("packingDate")     val packingDate: String?,
    @SerializedName("notes")           val notes: String?,
    @SerializedName("createdBy")       val createdBy: String?,
    @SerializedName("updatedBy")       val updatedBy: String?,
    @SerializedName("projectCode")        val projectCode: String,
    @SerializedName("totalSpoolsCount")   val totalSpoolsCount: Int = 0,
    @SerializedName("spools")             val spools: List<AssignSpoolRequest> = emptyList(),
    // Base64 rv; null skips the server's optimistic-concurrency check. NOT YET SENT by any call
    // site: local row_version doesn't advance after a successful update (the update endpoint
    // returns bool, not the updated row, and there's no GET-by-id call on the client to refresh
    // it), so wiring this up today would false-conflict on the device's own next edit. Field/DB
    // column/download-parse are in place; arm the call sites once a refresh path exists.
    @SerializedName("rowVersion")         val rowVersion: String? = null
)
