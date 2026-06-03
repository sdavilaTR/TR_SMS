package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity

data class SmsVehicleDto(
    @SerializedName(value = "vehicle_id",          alternate = ["vehicleId"])          val vehicleId: String? = null,
    @SerializedName(value = "project_id",          alternate = ["projectId"])          val projectId: String? = null,
    @SerializedName(value = "company")                                                  val company: String? = null,
    @SerializedName(value = "license_plate",       alternate = ["licensePlate"])       val licensePlate: String? = null,
    @SerializedName(value = "vehicle_name",        alternate = ["vehicleName"])        val vehicleName: String? = null,
    @SerializedName(value = "vehicle_type",        alternate = ["vehicleType"])        val vehicleType: String? = null,
    @SerializedName(value = "capacity_weight_kg",  alternate = ["capacityWeightKg"])   val capacityWeightKg: String? = null,
    @SerializedName(value = "is_active",           alternate = ["isActive"])           val isActive: Boolean? = null,
    @SerializedName(value = "created_at",          alternate = ["createdAt"])          val createdAt: String? = null,
    @SerializedName(value = "created_by",          alternate = ["createdBy"])          val createdBy: String? = null,
    @SerializedName(value = "updated_at",          alternate = ["updatedAt"])          val updatedAt: String? = null,
    @SerializedName(value = "on_route",            alternate = ["onRoute"])            val onRoute: Boolean? = null,
    @SerializedName(value = "destination")                                              val destination: Int? = null,
    @SerializedName(value = "destination_code",    alternate = ["destinationCode"])    val destinationCode: String? = null,
    @SerializedName(value = "destination_name",    alternate = ["destinationName"])    val destinationName: String? = null
) {
    private fun String?.toLongOrNullSafe(): Long? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toInt()

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun toEntity(defaultProjectId: Int): SmsVehicleEntity = SmsVehicleEntity(
        vehicle_id = vehicleId.toLongOrNullSafe() ?: 0L,
        project_id = defaultProjectId,
        company = company,
        license_plate = licensePlate.orEmpty(),
        vehicle_name = vehicleName,
        vehicle_type = vehicleType,
        capacity_weight_kg = capacityWeightKg.toDoubleOrNullSafe(),
        is_active = isActive ?: true,
        created_at = createdAt.orEmpty(),
        created_by = createdBy,
        updated_at = updatedAt,
        on_route = onRoute ?: false,
        destination = destination
    )
}
