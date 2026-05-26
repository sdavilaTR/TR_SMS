package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

data class CreateVehicleRequest(
    @SerializedName("licensePlate")     val licensePlate: String,
    @SerializedName("company")          val company: String?,
    @SerializedName("vehicleName")      val vehicleName: String?,
    @SerializedName("vehicleType")      val vehicleType: String?,
    @SerializedName("capacityWeightKg") val capacityWeightKg: Double?,
    @SerializedName("createdBy")        val createdBy: String,
    @SerializedName("projectCode")      val projectCode: String,
    @SerializedName("isActive")         val isActive: Boolean = true
)
