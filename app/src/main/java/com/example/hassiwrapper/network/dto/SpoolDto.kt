package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName
import com.example.hassiwrapper.data.model.Spool

/** Transport object matching the JSON shape returned by ATLAS for [sms].[sms_spool].
 *  Numeric fields are read as String? to tolerate "123", "123.0", "" or null without crashing. */
data class SpoolDto(
    @SerializedName(value = "spool_id",           alternate = ["spoolId"])           val spoolId: String? = null,
    @SerializedName(value = "project_id",         alternate = ["projectId"])         val projectId: String? = null,
    @SerializedName(value = "spool_code",         alternate = ["spoolCode"])         val spoolCode: String? = null,
    @SerializedName(value = "spool_suffix",       alternate = ["spoolSuffix"])       val spoolSuffix: String? = null,
    @SerializedName(value = "line_code",          alternate = ["lineCode"])          val lineCode: String? = null,
    @SerializedName(value = "unit_id",            alternate = ["unitId"])            val unitId: String? = null,
    @SerializedName(value = "service")                                                val service: String? = null,
    @SerializedName(value = "train")                                                  val train: String? = null,
    @SerializedName(value = "module")                                                 val module: String? = null,
    @SerializedName(value = "iso_type_id",        alternate = ["isoTypeId"])         val isoTypeId: String? = null,
    @SerializedName(value = "spec_id",            alternate = ["specId"])            val specId: String? = null,
    @SerializedName(value = "iso_revision_date",  alternate = ["isoRevisionDate"])   val isoRevisionDate: String? = null,
    @SerializedName(value = "subcontractor_id",   alternate = ["subcontractorId"])   val subcontractorId: String? = null,
    @SerializedName(value = "area_id",            alternate = ["areaId"])            val areaId: String? = null,
    @SerializedName(value = "is_active",          alternate = ["isActive"])          val isActive: Boolean? = null,
    @SerializedName(value = "created_at",         alternate = ["createdAt"])         val createdAt: String? = null,
    @SerializedName(value = "created_by",         alternate = ["createdBy"])         val createdBy: String? = null,
    @SerializedName(value = "updated_at",         alternate = ["updatedAt"])         val updatedAt: String? = null,
    @SerializedName(value = "updated_by",         alternate = ["updatedBy"])         val updatedBy: String? = null
) {
    private fun String?.toLongOrNullSafe(): Long? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toInt()

    fun toModel(): Spool = Spool(
        spoolId.toLongOrNullSafe() ?: 0L,
        projectId.toIntOrNullSafe() ?: 0,
        spoolCode.orEmpty(),
        spoolSuffix,
        lineCode,
        unitId.toIntOrNullSafe(),
        service,
        train,
        module,
        isoTypeId.toIntOrNullSafe(),
        specId.toLongOrNullSafe(),
        isoRevisionDate,
        subcontractorId.toLongOrNullSafe(),
        areaId.toLongOrNullSafe(),
        isActive ?: true,
        createdAt.orEmpty(),
        createdBy.orEmpty(),
        updatedAt,
        updatedBy
    )
}
