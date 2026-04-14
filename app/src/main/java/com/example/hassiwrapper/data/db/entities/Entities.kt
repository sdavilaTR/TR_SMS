package com.example.hassiwrapper.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config")
data class ConfigEntity(
    @PrimaryKey val key: String,
    val value: String?,
    val updated_at: String? = null
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val project_id: Int,
    val project_code: String = "",
    val project_name: String = "",
    val numeric_code: String = "",
    val country_code: String? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val zone_id: Int,
    val project_id: Int? = null,
    val zone_code: String = "",
    val zone_name: String = "",
    val zone_type: String = "",
    val parent_zone_id: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "contractors")
data class ContractorEntity(
    @PrimaryKey val contractor_id: Int,
    val contractor_code: String = "",
    val contractor_name: String = "",
    val legal_name: String? = null,
    val tax_id: String? = null,
    val contact_name: String? = null,
    val contact_email: String? = null,
    val country_code: String? = null,
    val parent_contractor_id: Int? = null,
    val is_active: Boolean = true
)

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey val unique_id_value: String,
    val project_id: Int? = null,
    val badge_number: String = "",
    val given_name: String = "",
    val family_name: String = "",
    val category_code: String = "",
    val position: String = "",
    val contractor_id: Int? = null,
    val discipline_id: Int? = null,
    val designation_id: Int? = null,
    val is_active: Boolean = true,
    val valid_from: String? = null,
    val valid_to: String? = null,
    val photo_url: String? = null,
    val syncStatus: String = "SYNCED"
)

@Entity(tableName = "pending_photos")
data class PendingPhotoEntity(
    @PrimaryKey val unique_id_value: String,
    val project_id: Int,
    val local_path: String,
    val created_at: String
)

@Entity(tableName = "access_points")
data class AccessPointEntity(
    @PrimaryKey val access_point_id: Int,
    val project_id: Int? = null,
    val access_code: String = "",
    val zone_id: Int? = null
)

@Entity(tableName = "crypto_keys")
data class CryptoKeyEntity(
    @PrimaryKey val crypto_key_id: Int,
    val project_id: Int? = null,
    val key_type: String = "",
    val is_active: Int = 1,
    val publicKeyB64: String = ""
)

@Entity(tableName = "revoked_tokens")
data class RevokedTokenEntity(
    @PrimaryKey val cti: String,
    val project_id: Int? = null
)

@Entity(tableName = "access_logs")
data class AccessLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val project_id: Int? = null,
    val unique_id_value: String? = null,
    val access_point_id: Int? = null,
    val terminal_id: String? = null,
    val event_time: String,
    val direction: String,
    val result: String,
    val failure_reason: String? = null,
    val scan_method: String? = null,
    val synced: Boolean = false
)

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val project_id: Int? = null,
    val unique_id_value: String? = null,
    val event_time: String,
    val incident_type: String,
    val severity: String? = null,
    val description: String? = null,
    val access_log_id: Long? = null,
    val zone_id: Int? = null,
    val status: String = "OPEN",
    val badge_number: String? = null,
    val synced: Boolean = false,
    val resolved_at: String? = null,
    val resolved_by: String? = null,
    val resolution_notes: String? = null
)

@Entity(tableName = "hse_observations")
data class HseObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val project_id: Int? = null,
    val observer_device_id: String? = null,
    val observer_badge: String? = null,
    val unique_id_value: String? = null,
    val observed_name: String? = null,
    val observed_badge: String? = null,
    val observed_department: String? = null,
    val observed_position: String? = null,
    val observed_contractor: String? = null,
    val observation_date: String,
    val location: String? = null,
    val area_authority: String? = null,
    val description: String,
    val observation_type: String,
    val safety_type: String,
    val intervention_action: String? = null,
    val outcome: String? = null,
    val action_taken: String? = null,
    val coaching_status: String = "NOT_REQUIRED",
    val additional_comments: String? = null,
    val categories: String? = null,
    val synced: Boolean = false
)

@Entity(tableName = "work_sessions")
data class WorkSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String? = null,
    val project_id: Int? = null,
    val unique_id_value: String,
    val session_date: String,
    val first_entry_log_id: Long? = null,
    val clock_in: String? = null,
    val clock_out: String? = null,
    val last_exit_log_id: Long? = null,
    val status: String = "OPEN",
    val synced: Boolean = false
)

@Entity(tableName = "training_compliance", primaryKeys = ["unique_id_value", "training_definition_id"])
data class TrainingComplianceEntity(
    val unique_id_value: String,
    val training_definition_id: Long,
    val badge_number: String = "",
    val training_code: String = "",
    val training_name: String = "",
    val is_mandatory: Boolean = false,
    val status: String = "MISSING",
    val completed_date: String? = null,
    val expiry_date: String? = null
)

@Entity(tableName = "document_compliance", primaryKeys = ["unique_id_value", "document_type_id"])
data class DocumentComplianceEntity(
    val unique_id_value: String,
    val document_type_id: Int,
    val type_code: String = "",
    val type_name: String = "",
    val is_mandatory: Boolean = false,
    val status: String = "missing",
    val person_document_id: Long? = null
)

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val asset_id: Long,
    val asset_uuid: String = "",
    val project_id: Int? = null,
    val identifier: String = "",
    val asset_name: String = "",
    val vehicle_type_name: String = "",
    val contractor_id: Int? = null,
    val contractor_name: String = "",
    val license_plate: String = "",
    val owner_register_sn: String = "",
    val brand: String = "",
    val model: String = "",
    val insurance_expiry: String? = null,
    val inspection_expiry: String? = null,
    val is_active: Boolean = true,
    val badge_printed: Boolean = false
)
