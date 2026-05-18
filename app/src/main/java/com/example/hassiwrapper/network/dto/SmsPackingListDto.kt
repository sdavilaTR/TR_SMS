package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity

data class SmsPackingListDto(
    @SerializedName(value = "packing_list_id", alternate = ["packingListId", "id"])
    val packingListId: String? = null,
    @SerializedName(value = "packing_list_name", alternate = ["packingListName", "name"])
    val packingListName: String? = null,
    @SerializedName(value = "project_id", alternate = ["projectId"])
    val projectId: String? = null,
    @SerializedName(value = "vehicle_id", alternate = ["vehicleId"])
    val vehicleId: String? = null,
    @SerializedName(value = "vehicle")
    val vehiclePlate: String? = null,
    @SerializedName(value = "position_id", alternate = ["positionId"])
    val positionId: String? = null,
    @SerializedName(value = "packing_date", alternate = ["packingDate"])
    val packingDate: String? = null,
    @SerializedName(value = "total_spools_count", alternate = ["totalSpoolsCount"])
    val totalSpoolsCount: String? = null,
    @SerializedName(value = "total_weight_kg", alternate = ["totalWeightKg"])
    val totalWeightKg: String? = null,
    @SerializedName(value = "notes")
    val notes: String? = null,
    @SerializedName(value = "is_active", alternate = ["isActive"])
    val isActive: Boolean? = null,
    @SerializedName(value = "created_at", alternate = ["createdAt"])
    val createdAt: String? = null,
    @SerializedName(value = "created_by", alternate = ["createdBy"])
    val createdBy: String? = null,
    @SerializedName(value = "updated_at", alternate = ["updatedAt"])
    val updatedAt: String? = null
) {
    private fun String?.toLongOrNullSafe(): Long? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toInt()

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun toEntity(defaultProjectId: Int): SmsPackingListEntity = SmsPackingListEntity(
        packing_list_id = packingListId.toLongOrNullSafe() ?: 0L,
        project_id = defaultProjectId,
        packing_list_name = packingListName.orEmpty(),
        vehicle_id = vehicleId.toLongOrNullSafe(),
        vehicle_plate = vehiclePlate,
        position_id = positionId.toIntOrNullSafe(),
        packing_date = packingDate.orEmpty(),
        total_spools_count = totalSpoolsCount.toIntOrNullSafe(),
        total_weight_kg = totalWeightKg.toDoubleOrNullSafe(),
        notes = notes,
        is_active = isActive ?: true,
        created_at = createdAt.orEmpty(),
        created_by = createdBy,
        updated_at = updatedAt
    )
}
