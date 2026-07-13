package com.example.hassiwrapper.data.model

/**
 * Mirrors [sms].[sms_spool] in the ATLAS database.
 * Fields are nullable where the DB column allows NULL.
 */
data class Spool(
    val spoolId: Long,
    val projectId: Int,
    val spoolCode: String,
    val spoolSuffix: String?,
    val lineCode: String?,
    val unitId: Int?,
    val service: String?,
    val train: String?,
    val module: String?,
    val isoTypeId: Int?,
    val specId: Long?,
    val isoRevisionDate: String?,
    val subcontractorId: Long?,
    val areaId: Long?,
    val isActive: Boolean,
    val createdAt: String,
    val createdBy: String,
    val updatedAt: String?,
    val updatedBy: String?,
    val packingListId: Long? = null,
    val sitNumber: String? = null,
    val revision: String? = null
) {
    val displayCode: String
        get() = if (spoolSuffix.isNullOrBlank()) spoolCode else "$spoolCode-$spoolSuffix"
}
