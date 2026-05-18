package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName
 import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.model.Spool
import java.util.zip.CRC32

/** Transport object matching the JSON shape returned by ATLAS for [sms].[sms_spool].
 *  Handles two API formats:
 *   - Legacy: numeric spool_id, spool_code, spool_suffix fields
 *   - Current: string spoolId like "886-600C-65440-002", no spool_code field */
data class SpoolDto(
    @SerializedName(value = "spool_id",           alternate = ["spoolId"])           val spoolId: String? = null,
    @SerializedName(value = "project_id",         alternate = ["projectId"])         val projectId: String? = null,
    @SerializedName(value = "project_code",       alternate = ["projectCode"])       val projectCode: String? = null,
    @SerializedName(value = "spool_code",         alternate = ["spoolCode"])         val spoolCode: String? = null,
    @SerializedName(value = "spool_suffix",       alternate = ["spoolSuffix"])       val spoolSuffix: String? = null,
    @SerializedName(value = "line_code",          alternate = ["lineCode"])          val lineCode: String? = null,
    @SerializedName(value = "unit_id",            alternate = ["unitId"])            val unitId: String? = null,
    @SerializedName(value = "service")                                                val service: String? = null,
    @SerializedName(value = "train")                                                  val train: String? = null,
    @SerializedName(value = "module")                                                 val module: String? = null,
    @SerializedName(value = "assigned_unit",      alternate = ["assignedUnit"])      val assignedUnit: String? = null,
    @SerializedName(value = "iso_type_id",        alternate = ["isoTypeId"])         val isoTypeId: String? = null,
    @SerializedName(value = "spec_id",            alternate = ["specId"])            val specId: String? = null,
    @SerializedName(value = "iso_revision_date",  alternate = ["isoRevisionDate"])   val isoRevisionDate: String? = null,
    @SerializedName(value = "subcontractor_id",   alternate = ["subcontractorId"])   val subcontractorId: String? = null,
    @SerializedName(value = "area_id",            alternate = ["areaId"])            val areaId: String? = null,
    @SerializedName(value = "status")                                                 val status: String? = null,
    @SerializedName(value = "zone")                                                   val zone: String? = null,
    @SerializedName(value = "description")                                            val description: String? = null,
    @SerializedName(value = "is_active",          alternate = ["isActive"])          val isActive: Boolean? = null,
    @SerializedName(value = "created_at",         alternate = ["createdAt"])         val createdAt: String? = null,
    @SerializedName(value = "created_by",         alternate = ["createdBy"])         val createdBy: String? = null,
    @SerializedName(value = "updated_at",         alternate = ["updatedAt"])         val updatedAt: String? = null,
    @SerializedName(value = "updated_by",         alternate = ["updatedBy", "lastModifiedBy"]) val updatedBy: String? = null,
    @SerializedName(value = "packing_list_id",    alternate = ["packingListId"])     val packingListId: Long? = null
) {
    private fun String?.toLongOrNullSafe(): Long? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()

    private fun String?.toIntOrNullSafe(): Int? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toInt()

    /** Resolves a numeric or string spoolId to a stable Long primary key.
     *  Numeric strings ("12345") parse directly.
     *  String IDs like "886-600C-65440-002" get a CRC32-based hash.
     *  Suffix is included in the hash key so SP01/SP02/... on the same code are distinct. */
    private fun resolveSpoolId(): Long {
        val s = spoolId?.trim() ?: return 0L
        if (s.isEmpty()) return 0L
        val numeric = s.toDoubleOrNull()?.toLong()
        if (numeric != null && numeric != 0L) return numeric
        val suffix = spoolSuffix?.trim()
        val key = if (!suffix.isNullOrEmpty()) "$s-$suffix" else s
        val crc = CRC32()
        crc.update(key.toByteArray())
        val hash = crc.value.toLong()
        return if (hash == 0L) 1L else hash
    }

    /** Resolves display code: prefers explicit spool_code, falls back to full spoolId string. */
    private fun resolveSpoolCode(): String = spoolCode ?: spoolId.orEmpty()

    /** Resolves unit/service label from either the legacy service field or the new assignedUnit. */
    private fun resolveService(): String? = service ?: assignedUnit

    fun toModel(): Spool = Spool(
        resolveSpoolId(),
        projectId.toIntOrNullSafe() ?: 0,
        resolveSpoolCode(),
        spoolSuffix,
        lineCode,
        unitId.toIntOrNullSafe(),
        resolveService(),
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
        updatedBy,
        packingListId
    )

    fun toEntity(defaultPackingListId: Long? = null): SmsSpoolEntity = SmsSpoolEntity(
        spool_id        = resolveSpoolId(),
        project_id      = projectId.toIntOrNullSafe() ?: 0,
        spool_code      = resolveSpoolCode(),
        spool_suffix    = spoolSuffix,
        line_code       = lineCode,
        unit_id         = unitId.toIntOrNullSafe(),
        service         = resolveService(),
        train           = train,
        module          = module,
        iso_type_id     = isoTypeId.toIntOrNullSafe(),
        spec_id         = specId.toLongOrNullSafe(),
        iso_revision_date = isoRevisionDate,
        subcontractor_id = subcontractorId.toLongOrNullSafe(),
        area_id         = areaId.toLongOrNullSafe(),
        is_active       = isActive ?: true,
        created_at      = createdAt.orEmpty(),
        created_by      = createdBy.orEmpty(),
        updated_at      = updatedAt,
        updated_by      = updatedBy,
        packing_list_id = packingListId ?: defaultPackingListId
    )
}
