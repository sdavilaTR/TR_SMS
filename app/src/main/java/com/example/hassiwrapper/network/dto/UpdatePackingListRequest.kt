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
    @SerializedName("spools")             val spools: List<AssignSpoolRequest> = emptyList()
)
