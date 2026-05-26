package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

data class UpdateVehicleRequest(
    @SerializedName("vehicleId")        val vehicleId: Long,
    @SerializedName("licensePlate")     val licensePlate: String,
    @SerializedName("company")          val company: String?,
    @SerializedName("vehicleName")      val vehicleName: String?,
    @SerializedName("vehicleType")      val vehicleType: String?,
    @SerializedName("capacityWeightKg") val capacityWeightKg: Double?,
    @SerializedName("updatedBy")        val updatedBy: String,
    @SerializedName("projectCode")      val projectCode: String,
    @SerializedName("isActive")         val isActive: Boolean = true
)
