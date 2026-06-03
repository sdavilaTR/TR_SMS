package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

data class VehicleLoadingUploadDto(
    @SerializedName("vehicleLoadingId") val vehicleLoadingId: Long,
    @SerializedName("vehicleId")        val vehicleId: Long,
    @SerializedName("vehiclePlate")     val vehiclePlate: String?,
    @SerializedName("projectId")        val projectId: Int,
    @SerializedName("createdAt")        val createdAt: String,
    @SerializedName("createdBy")        val createdBy: String?,
    @SerializedName("spools")           val spools: List<VehicleLoadingSpoolUploadDto>
)

data class VehicleLoadingSpoolUploadDto(
    @SerializedName("spoolId")       val spoolId: Long,
    @SerializedName("packingListId") val packingListId: Long?
)
