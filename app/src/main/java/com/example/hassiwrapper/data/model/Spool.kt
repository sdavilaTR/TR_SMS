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
    /** Full physical-tag style title: CODE[-SUFFIX][-REVISION], e.g. "774-BD-20041-008-SP03-01A".
     *  Some backends (e.g. JAFURAH) bake the suffix into spool_code itself
     *  ("774-BD-20041-008-SP03"), so only append spoolSuffix when it isn't already there.
     *  The baked-in check is restricted to JAFURAH's SPxx suffix shape (see QrParser.JAFURAH_TAG)
     *  so a code that coincidentally ends in "-<suffix>" for an unrelated reason (e.g. a plain
     *  numeric suffix matching a trailing drawing/sheet number) doesn't get silently swallowed. */
    val displayCode: String
        get() {
            val suffix = spoolSuffix?.takeIf { it.isNotBlank() }
            val alreadyBaked = suffix != null && BAKED_IN_SUFFIX_SHAPE.matches(suffix) &&
                spoolCode.endsWith("-$suffix", ignoreCase = true)
            val base = if (suffix == null || alreadyBaked) spoolCode else "$spoolCode-$suffix"
            return if (revision.isNullOrBlank()) base else "$base-$revision"
        }
}

private val BAKED_IN_SUFFIX_SHAPE = Regex("""(?i)^SP\d+$""")
