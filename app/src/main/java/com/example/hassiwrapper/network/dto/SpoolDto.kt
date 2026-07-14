package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName
 import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolLocationEntity
import com.example.hassiwrapper.data.model.Spool
import java.util.zip.CRC32

data class AssignSpoolRequest(
    @SerializedName("spoolId")        val spoolId: Long,
    @SerializedName("addedBy")        val addedBy: String,
    @SerializedName("sequenceNumber") val sequenceNumber: Int? = null
)

data class CreateSpoolRequest(
    @SerializedName("spoolCode")   val spoolCode: String,
    @SerializedName("spoolSuffix") val spoolSuffix: String?,
    @SerializedName("lineCode")    val lineCode: String?,
    @SerializedName("projectId")   val projectId: Int,
    @SerializedName("createdAt")   val createdAt: String,
    @SerializedName("createdBy")   val createdBy: String,
    @SerializedName("isActive")    val isActive: Boolean = true,
    @SerializedName("unitId")      val unitId: Int? = null,
    @SerializedName("isoTypeId")   val isoTypeId: Int? = null,
    @SerializedName("train")       val train: String? = null,
    @SerializedName("zone")        val zone: String? = null
)

data class CreateSpoolPropertyRequest(
    @SerializedName("spoolId")        val spoolId: Long,
    @SerializedName("diameterInches") val diameterInches: Double?,
    @SerializedName("diameter")       val diameter: Double?,
    @SerializedName("boreSizeId")     val boreSizeId: Int?,
    @SerializedName("weightKg")       val weightKg: Double?
)

data class CreateSpoolStatusFlagsRequest(
    @SerializedName("spoolId")            val spoolId: Long,
    @SerializedName("statusId")           val statusId: Int?,
    @SerializedName("incompleteStatusId") val incompleteStatusId: Int?,
    @SerializedName("positionId")         val positionId: Int?,
    @SerializedName("subPositionId")      val subPositionId: Long? = null
)

/** Full mirror of backend SpoolStatusFlagsDto — body for the authoritative PUT status-flags path.
 *  The PUT overwrites the whole flags row, so callers GET-merge-PUT (read current flags, override
 *  only positionId/subPositionId, send everything back) to avoid wiping hold/damaged/status/dates.
 *  updatedAt is set server-side (GETUTCDATE) and intentionally omitted. */
data class SpoolStatusFlagsRequest(
    @SerializedName("spoolId")                   val spoolId: Long,
    @SerializedName("statusId")                  val statusId: Int? = null,
    @SerializedName("incompleteStatusId")        val incompleteStatusId: Int? = null,
    @SerializedName("positionId")                val positionId: Int? = null,
    @SerializedName("subPositionId")             val subPositionId: Long? = null,
    @SerializedName("hold")                      val hold: Boolean = false,
    @SerializedName("damaged")                   val damaged: Boolean = false,
    @SerializedName("returnedToFactory")         val returnedToFactory: Boolean = false,
    @SerializedName("positionStatusDiscrepancy") val positionStatusDiscrepancy: Boolean = false,
    @SerializedName("reviewDiscrepancy")         val reviewDiscrepancy: Boolean = false,
    @SerializedName("lastEventDate")             val lastEventDate: String? = null,
    @SerializedName("pcaStatusDate")             val pcaStatusDate: String? = null,
    @SerializedName("pcaEntryDate")              val pcaEntryDate: String? = null
)

/** Transport object matching the JSON shape returned by ATLAS for [sms].[sms_spool].
 *  Handles two API formats:
 *   - Legacy: numeric spool_id, spool_code, spool_suffix fields
 *   - Current: string spoolId like "886-600C-65440-002", no spool_code field */
data class SpoolDto(
    @SerializedName(value = "id",                 alternate = ["numericId"])         val id: Long? = null,
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
    @SerializedName(value = "sub_position_id",    alternate = ["subPositionId"])     val subPositionId: String? = null,
    @SerializedName(value = "status")                                                 val status: String? = null,
    @SerializedName(value = "zone")                                                   val zone: String? = null,
    @SerializedName(value = "description")                                            val description: String? = null,
    @SerializedName(value = "is_active",          alternate = ["isActive"])          val isActive: Boolean? = null,
    @SerializedName(value = "created_at",         alternate = ["createdAt"])         val createdAt: String? = null,
    @SerializedName(value = "created_by",         alternate = ["createdBy"])         val createdBy: String? = null,
    @SerializedName(value = "updated_at",         alternate = ["updatedAt"])         val updatedAt: String? = null,
    @SerializedName(value = "updated_by",         alternate = ["updatedBy", "lastModifiedBy"]) val updatedBy: String? = null,
    @SerializedName(value = "packing_list_id",    alternate = ["packingListId"])     val packingListId: Long? = null,
    @SerializedName(value = "priority")                                              val priority: String? = null,
    @SerializedName(value = "in_transit",        alternate = ["inTransit"])         val inTransit: Boolean? = null,
    @SerializedName(value = "packing_list_name", alternate = ["packingListName"])   val packingListName: String? = null,
    @SerializedName(value = "sit_number",        alternate = ["sitNumber"])         val sitNumber: String? = null,
    @SerializedName(value = "revision",          alternate = ["revisionCode", "fabRevision"]) val revision: String? = null,
    // Backend column ISO_rev_number — e.g. "01A" trailing the JAFURAH physical tag
    // (UNIT-SERVICE-LINE-SIT-SPOOLID-REVISION). Prefer this over the legacy `revision`
    // aliases above when both are present; QR-scan parsing keeps writing `revision` locally.
    @SerializedName(value = "ISO_rev_number",    alternate = ["iso_rev_number", "isoRevNumber"]) val isoRevNumber: String? = null,
    // PCA scan flag (Tr.ATLAS.Domain.Models.Sms.SpoolDto.Scanned), present on every row
    // of the unfiltered GET /spools response; null means never scanned. Local queries
    // filter on this (scanned=1) instead of the API doing any server-side filtering.
    @SerializedName(value = "scanned")                                               val scanned: Boolean? = null
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
        if (id != null && id != 0L) return id
        val s = (spoolId ?: spoolCode)?.trim() ?: return 0L
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

    private fun resolveService(): String? = service

    private fun resolveRevision(): String? =
        (isoRevNumber ?: revision)?.takeIf { it.isNotBlank() }

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
        packingListId,
        sitNumber?.takeIf { it.isNotBlank() },
        resolveRevision()
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
        sub_position_id = subPositionId.toLongOrNullSafe(),
        is_active       = isActive ?: true,
        created_at      = createdAt.orEmpty(),
        created_by      = createdBy.orEmpty(),
        updated_at      = updatedAt,
        updated_by      = updatedBy,
        packing_list_id = packingListId ?: defaultPackingListId,
        synced          = true,
        status          = status?.takeIf { it.isNotBlank() },
        description     = description?.takeIf { it.isNotBlank() },
        priority        = priority?.takeIf { it.isNotBlank() },
        zone            = zone?.takeIf { it.isNotBlank() },
        assigned_unit   = assignedUnit?.takeIf { it.isNotBlank() },
        packing_list_name = packingListName?.takeIf { it.isNotBlank() },
        in_transit      = inTransit ?: false,
        sit_number      = sitNumber?.takeIf { it.isNotBlank() },
        revision        = resolveRevision(),
        scanned         = scanned ?: false
    )
}

data class SpoolLocationRequest(
    @SerializedName("latitude")      val latitude: Double,
    @SerializedName("longitude")     val longitude: Double,
    @SerializedName("gpsAccuracyM") val gpsAccuracyM: Float?,
    @SerializedName("capturedAt")   val capturedAt: String,
    @SerializedName("capturedBy")   val capturedBy: String?,
    // Marks this as a real scan on the backend: sms_spool.scanned/scanned_by/scanned_at/scanned_from.
    @SerializedName("scannedBy")   val scannedBy: String? = null,
    @SerializedName("scannedFrom") val scannedFrom: String? = null
)

data class SpoolLocationResponse(
    @SerializedName("locationId")   val locationId: Long,
    @SerializedName("spoolId")      val spoolId: Long,
    @SerializedName("latitude")     val latitude: Double,
    @SerializedName("longitude")    val longitude: Double,
    @SerializedName("gpsAccuracyM") val gpsAccuracyM: Float?,
    @SerializedName("capturedAt")   val capturedAt: String,
    @SerializedName("capturedBy")   val capturedBy: String?
) {
    fun toEntity(synced: Boolean = true) = SmsSpoolLocationEntity(
        location_id   = locationId,
        spool_id      = spoolId,
        latitude      = latitude,
        longitude     = longitude,
        gps_accuracy_m = gpsAccuracyM,
        captured_at   = capturedAt,
        captured_by   = capturedBy,
        synced        = synced
    )
}
