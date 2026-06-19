package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Upload payload for editing an existing spool. Mirror of [CreateSpoolRequest] plus
 * the server [spoolId]. Backend endpoint `PUT /api/atlas/projects/{projectCode}/spools`
 * is net-new — spool edits cannot sync without it.
 */
data class UpdateSpoolRequest(
    @SerializedName("spoolId")     val spoolId: Long,
    @SerializedName("spoolCode")   val spoolCode: String,
    @SerializedName("spoolSuffix") val spoolSuffix: String?,
    @SerializedName("lineCode")    val lineCode: String?,
    @SerializedName("projectId")   val projectId: Int,
    @SerializedName("unitId")      val unitId: Int? = null,
    @SerializedName("isoTypeId")   val isoTypeId: Int? = null,
    @SerializedName("train")       val train: String? = null,
    @SerializedName("projectCode") val projectCode: String,
    @SerializedName("updatedBy")   val updatedBy: String? = null
)
