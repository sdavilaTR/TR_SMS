package com.example.hassiwrapper.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sms_area")
data class SmsAreaEntity(
    @PrimaryKey val area_id: Long,
    val project_id: Int,
    val parent_area_id: Long? = null,
    val name: String = "",
    val full_path: String = "",
    val level: Int = 0,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String = "",
    val updated_at: String? = null,
    val updated_by: String? = null
)

@Entity(tableName = "sms_sub_position")
data class SmsSubPositionEntity(
    @PrimaryKey val sub_position_id: Long,
    val project_id: Int,
    val position_id: Int,
    val parent_sub_id: Long? = null,
    val code: String = "",
    val name: String = "",
    val full_path: String = "",
    val level: Int = 0,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String = "",
    val updated_at: String? = null,
    val updated_by: String? = null
)

@Entity(tableName = "sms_bore_size")
data class SmsBoreSizeEntity(
    @PrimaryKey val bore_size_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_incomplete_status")
data class SmsIncompleteStatusEntity(
    @PrimaryKey val incomplete_status_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_iso_type")
data class SmsIsoTypeEntity(
    @PrimaryKey val iso_type_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_packing_list")
data class SmsPackingListEntity(
    @PrimaryKey val packing_list_id: Long,
    val project_id: Int,
    val packing_list_name: String = "",
    val vehicle_id: Long? = null,
    val vehicle_plate: String? = null,
    val position_id: Int? = null,
    val position: String? = null,
    val packing_date: String = "",
    val total_spools_count: Int? = null,
    val total_weight_kg: Double? = null,
    val notes: String? = null,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String? = null,
    val updated_at: String? = null,
    val synced: Boolean = false,
    val ready_to_send: Boolean = false
)

@Entity(tableName = "sms_packing_list_spool", indices = [Index(value = ["spool_id"], unique = true)])
data class SmsPackingListSpoolEntity(
    @PrimaryKey val packing_list_spool_id: Long,
    val packing_list_id: Long,
    val spool_id: Long,
    val sequence_number: Int? = null,
    val added_at: String = "",
    val added_by: String? = null
)

@Entity(tableName = "sms_position")
data class SmsPositionEntity(
    @PrimaryKey val position_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_spec")
data class SmsSpecEntity(
    @PrimaryKey val spec_id: Long,
    val project_id: Int,
    val code: String = "",
    val description: String? = null,
    val material_type: String? = null,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String = "",
    val updated_at: String? = null,
    val updated_by: String? = null
)

@Entity(tableName = "sms_spool")
data class SmsSpoolEntity(
    @PrimaryKey val spool_id: Long,
    val project_id: Int,
    val spool_code: String = "",
    val spool_suffix: String? = null,
    val line_code: String? = null,
    val unit_id: Int? = null,
    val service: String? = null,
    val train: String? = null,
    val module: String? = null,
    val iso_type_id: Int? = null,
    val spec_id: Long? = null,
    val iso_revision_date: String? = null,
    val subcontractor_id: Long? = null,
    val area_id: Long? = null,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String = "",
    val updated_at: String? = null,
    val updated_by: String? = null,
    val packing_list_id: Long? = null,
    val synced: Boolean = false,
    // Fields from API response (not always populated, depend on project/API version)
    val status: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val zone: String? = null,
    val assigned_unit: String? = null,
    val packing_list_name: String? = null,
    val in_transit: Boolean = false,
    val position_id: Int? = null,
    val sub_position_id: Long? = null
) {
    val displayCode: String
        get() = if (spool_suffix.isNullOrBlank()) spool_code else "$spool_code-$spool_suffix"
}

@Entity(tableName = "sms_spool_event")
data class SmsSpoolEventEntity(
    @PrimaryKey val event_id: Long,
    val event_date: String = "",
    val spool_id: Long,
    val event_type: String = "",
    val old_value: String? = null,
    val new_value: String? = null,
    val source: String? = null,
    val created_at: String = "",
    val created_by: String = ""
)

@Entity(tableName = "sms_spool_property")
data class SmsSpoolPropertyEntity(
    @PrimaryKey val spool_id: Long,
    val diameter_inches: Double? = null,
    val diameter: Double? = null,
    val bore_size_id: Int? = null,
    val weight_kg: Double? = null,
    val updated_at: String = ""
)

@Entity(tableName = "sms_spool_status")
data class SmsSpoolStatusEntity(
    @PrimaryKey val status_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_spool_status_flags")
data class SmsSpoolStatusFlagsEntity(
    @PrimaryKey val spool_id: Long,
    val status_id: Int? = null,
    val incomplete_status_id: Int? = null,
    val position_id: Int? = null,
    val sub_position_id: Long? = null,
    val hold: Boolean = false,
    val damaged: Boolean = false,
    val returned_to_factory: Boolean = false,
    val position_status_discrepancy: Boolean = false,
    val review_discrepancy: Boolean = false,
    val last_event_date: String? = null,
    val pca_status_date: String? = null,
    val pca_entry_date: String? = null,
    val updated_at: String = ""
)

@Entity(tableName = "sms_subcontractor")
data class SmsSubcontractorEntity(
    @PrimaryKey val subcontractor_id: Long,
    val project_id: Int,
    val code: String = "",
    val name: String = "",
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String = "",
    val updated_at: String? = null,
    val updated_by: String? = null
)

@Entity(tableName = "sms_unit")
data class SmsUnitEntity(
    @PrimaryKey val unit_id: Int,
    val code: String = "",
    val name: String = "",
    val sort_order: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "sms_vehicle_loading")
data class SmsVehicleLoadingEntity(
    @PrimaryKey(autoGenerate = true) val loading_id: Long = 0,
    val vehicle_id: Long,
    val vehicle_plate: String,
    val project_id: Int,
    val created_at: String,
    val synced: Boolean = false
)

@Entity(tableName = "sms_vehicle_loading_spool")
data class SmsVehicleLoadingSpoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loading_id: Long,
    val spool_id: Long,
    val spool_code: String,
    val spool_suffix: String?,
    val packing_list_id: Long?,
    val packing_list_name: String?
)

@Entity(tableName = "sms_incident")
data class SmsIncidentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val project_id: Int,
    val spool_code: String,
    val spool_suffix: String? = null,
    val description: String,
    val vehicle_plate: String? = null,
    val location_type: String,
    val location_detail: String? = null,
    val severity: String,
    val position_id: Int? = null,
    val sub_position_id: Long? = null,
    val position_code: String? = null,
    val author_name: String? = null,
    val photo_path: String? = null,
    val event_date: String,
    val status: String = "OPEN",
    val closed_by: String? = null,
    val closed_at: String? = null,
    val synced: Boolean = false,
    /** Server-assigned incident id, set once [synced]; required to address the photo-upload endpoint. */
    val server_id: Long? = null,
    /** Whether [photo_path] has been uploaded — tracked separately from [synced] since the photo upload is a second, independently-retried call. */
    val photo_synced: Boolean = false
)

@Entity(tableName = "sms_audit_log")
data class SmsAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val log_id: Long = 0,
    val project_id: Int,
    val action_type: String,
    val entity_type: String,
    val entity_id: Long,
    val entity_name: String,
    val detail: String? = null,
    val terminal_name: String,
    val timestamp: Long
)

@Entity(tableName = "sms_vehicle")
data class SmsVehicleEntity(
    @PrimaryKey val vehicle_id: Long,
    val project_id: Int,
    val company: String? = null,
    val license_plate: String = "",
    val vehicle_name: String? = null,
    val vehicle_type: String? = null,
    val capacity_weight_kg: Double? = null,
    val is_active: Boolean = true,
    val created_at: String = "",
    val created_by: String? = null,
    val updated_at: String? = null,
    val synced: Boolean = false,
    val on_route: Boolean = false,
    val destination: Int? = null,
    val route_synced: Boolean = true
)
