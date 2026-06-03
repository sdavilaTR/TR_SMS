package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

data class TransferUploadDto(
    @SerializedName("transferId")      val transferId: Long,
    @SerializedName("type")            val type: String,
    @SerializedName("projectId")       val projectId: Int,
    @SerializedName("signatureBase64") val signatureBase64: String?,
    @SerializedName("createdAt")       val createdAt: String,
    @SerializedName("createdBy")       val createdBy: String?,
    @SerializedName("spools")          val spools: List<TransferSpoolUploadDto>
)

data class TransferSpoolUploadDto(
    @SerializedName("spoolId")       val spoolId: Long,
    @SerializedName("packingListId") val packingListId: Long?
)
