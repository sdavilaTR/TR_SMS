package com.example.hassiwrapper.network.dto

import com.google.gson.annotations.SerializedName

// ── Auth ─────────────────────────────────────────────────────────────────────

data class LoginRequest(
    val userName: String,
    val password: String
)

// ── Sync Download Response ───────────────────────────────────────────────────

data class SyncDownloadResponse(
    val projects: List<ProjectDto>? = null,
    val zones: List<ZoneDto>? = null,
    val contractors: List<ContractorDto>? = null,
    val persons: List<PersonDto>? = null,
    val vehicles: List<VehicleDto>? = null,
    val cryptoKeys: List<CryptoKeyDto>? = null
)

data class ProjectDto(
    val id: Int? = null,
    @SerializedName("project_id") val projectIdSnake: Int? = null,
    val projectCode: String? = null,
    @SerializedName("project_code") val projectCodeSnake: String? = null,
    val projectName: String? = null,
    @SerializedName("project_name") val projectNameSnake: String? = null,
    val numericCode: String? = null,
    @SerializedName("numeric_code") val numericCodeSnake: String? = null,
    val countryCode: String? = null,
    @SerializedName("country_code") val countryCodeSnake: String? = null,
    val isActive: Boolean? = null,
    @SerializedName("is_active") val isActiveSnake: Boolean? = null
)

data class ZoneDto(
    val id: Int? = null,
    @SerializedName("zone_id") val zoneIdSnake: Int? = null,
    val projectId: Int? = null,
    @SerializedName("project_id") val projectIdSnake: Int? = null,
    val zoneCode: String? = null,
    @SerializedName("zone_code") val zoneCodeSnake: String? = null,
    val zoneName: String? = null,
    @SerializedName("zone_name") val zoneNameSnake: String? = null,
    val zoneType: String? = null,
    @SerializedName("zone_type") val zoneTypeSnake: String? = null,
    val parentZoneId: Int? = null,
    @SerializedName("parent_zone_id") val parentZoneIdSnake: Int? = null,
    val isActive: Boolean? = null,
    @SerializedName("is_active") val isActiveSnake: Boolean? = null
)

data class ContractorDto(
    val id: Int? = null,
    @SerializedName("contractor_id") val contractorIdSnake: Int? = null,
    val contractorCode: String? = null,
    @SerializedName("contractor_code") val contractorCodeSnake: String? = null,
    val contractorName: String? = null,
    @SerializedName("contractor_name") val contractorNameSnake: String? = null,
    val legalName: String? = null,
    @SerializedName("legal_name") val legalNameSnake: String? = null,
    val taxId: String? = null,
    @SerializedName("tax_id") val taxIdSnake: String? = null,
    val contactName: String? = null,
    @SerializedName("contact_name") val contactNameSnake: String? = null,
    val contactEmail: String? = null,
    @SerializedName("contact_email") val contactEmailSnake: String? = null,
    val countryCode: String? = null,
    @SerializedName("country_code") val countryCodeSnake: String? = null,
    val parentContractorId: Int? = null,
    @SerializedName("parent_contractor_id") val parentContractorIdSnake: Int? = null,
    val isActive: Boolean? = null,
    @SerializedName("is_active") val isActiveSnake: Boolean? = null
)

data class PersonDto(
    val uniqueIdValue: String? = null,
    @SerializedName("unique_id_value") val uniqueIdValueSnake: String? = null,
    val uuid: String? = null,
    val projectId: Int? = null,
    @SerializedName("project_id") val projectIdSnake: Int? = null,
    val badgeNumber: String? = null,
    @SerializedName("badge_number") val badgeNumberSnake: String? = null,
    val givenName: String? = null,
    @SerializedName("given_name") val givenNameSnake: String? = null,
    @SerializedName("first_name") val firstName: String? = null,
    val familyName: String? = null,
    @SerializedName("family_name") val familyNameSnake: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    val categoryCode: String? = null,
    @SerializedName("category_code") val categoryCodeSnake: String? = null,
    val category: String? = null,
    val position: String? = null,
    val puesto: String? = null,
    val positionName: String? = null,
    @SerializedName("position_name") val positionNameSnake: String? = null,
    val designationName: String? = null,
    @SerializedName("designation_name") val designationNameSnake: String? = null,
    val jobTitle: String? = null,
    @SerializedName("job_title") val jobTitleSnake: String? = null,
    val contractorId: Int? = null,
    @SerializedName("contractor_id") val contractorIdSnake: Int? = null,
    val disciplineId: Int? = null,
    @SerializedName("discipline_id") val disciplineIdSnake: Int? = null,
    val designationId: Int? = null,
    @SerializedName("designation_id") val designationIdSnake: Int? = null,
    val isActive: Boolean? = null,
    @SerializedName("is_active") val isActiveSnake: Boolean? = null,
    val validFrom: String? = null,
    @SerializedName("valid_from") val validFromSnake: String? = null,
    @SerializedName("entryDate") val entryDate: String? = null,
    @SerializedName("entry_date") val entryDateSnake: String? = null,
    @SerializedName("entranceDate") val entranceDate: String? = null,
    @SerializedName("entrance_date") val entranceDateSnake: String? = null,
    val validTo: String? = null,
    @SerializedName("valid_to") val validToSnake: String? = null,
    val photoUrl: String? = null,
    @SerializedName("photo_url") val photoUrlSnake: String? = null
)

data class CryptoKeyDto(
    val cryptoKeyId: Int? = null,
    @SerializedName("crypto_key_id") val cryptoKeyIdSnake: Int? = null,
    val keyPurpose: String? = null,
    @SerializedName("key_purpose") val keyPurposeSnake: String? = null,
    val publicKeyB64: String? = null,
    @SerializedName("public_key_b64") val publicKeyB64Snake: String? = null
)

data class VehicleDto(
    val assetId: Long? = null,
    @SerializedName("asset_id") val assetIdSnake: Long? = null,
    val assetUuid: String? = null,
    @SerializedName("asset_uuid") val assetUuidSnake: String? = null,
    val projectId: Int? = null,
    @SerializedName("project_id") val projectIdSnake: Int? = null,
    val identifier: String? = null,
    val assetName: String? = null,
    @SerializedName("asset_name") val assetNameSnake: String? = null,
    val vehicleTypeName: String? = null,
    @SerializedName("vehicle_type_name") val vehicleTypeNameSnake: String? = null,
    val contractorId: Int? = null,
    @SerializedName("contractor_id") val contractorIdSnake: Int? = null,
    val contractorName: String? = null,
    @SerializedName("contractor_name") val contractorNameSnake: String? = null,
    val licensePlate: String? = null,
    @SerializedName("license_plate") val licensePlateSnake: String? = null,
    val ownerRegisterSn: String? = null,
    @SerializedName("owner_register_sn") val ownerRegisterSnSnake: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val insuranceExpiry: String? = null,
    @SerializedName("insurance_expiry") val insuranceExpirySnake: String? = null,
    val inspectionExpiry: String? = null,
    @SerializedName("inspection_expiry") val inspectionExpirySnake: String? = null,
    val isActive: Boolean? = null,
    @SerializedName("is_active") val isActiveSnake: Boolean? = null,
    val badgePrinted: Boolean? = null,
    @SerializedName("badge_printed") val badgePrintedSnake: Boolean? = null
)

// ── Upload Requests ──────────────────────────────────────────────────────────

data class UploadLogsRequest(val logs: List<AccessLogDto>)
data class UploadIncidentsRequest(val incidents: List<IncidentDto>)
data class UploadSessionsRequest(val sessions: List<SessionDto>)
data class RegisterDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("project_id") val projectId: Int = 1
)

data class UploadObservationsRequest(val observations: List<ObservationUploadDto>)

data class ObservationUploadDto(
    val uuid: String,
    @SerializedName("project_id") val projectId: Int?,
    @SerializedName("observer_device_id") val observerDeviceId: String?,
    @SerializedName("observer_badge") val observerBadge: String?,
    @SerializedName("unique_id_value") val uniqueIdValue: String?,
    @SerializedName("observed_name") val observedName: String?,
    @SerializedName("observed_badge") val observedBadge: String?,
    @SerializedName("observed_department") val observedDepartment: String?,
    @SerializedName("observed_position") val observedPosition: String?,
    @SerializedName("observed_contractor") val observedContractor: String?,
    @SerializedName("observation_date") val observationDate: String,
    val location: String?,
    @SerializedName("area_authority") val areaAuthority: String?,
    val description: String,
    @SerializedName("observation_type") val observationType: String,
    @SerializedName("safety_type") val safetyType: String,
    @SerializedName("intervention_action") val interventionAction: String?,
    val outcome: String?,
    @SerializedName("action_taken") val actionTaken: String?,
    @SerializedName("coaching_status") val coachingStatus: String,
    @SerializedName("additional_comments") val additionalComments: String?,
    val categories: List<String>?,
    @SerializedName("target_type") val targetType: String? = "WORKER",
    @SerializedName("observer_unique_id") val observerUniqueId: String? = null,
    @SerializedName("observer_name") val observerName: String? = null,
    @SerializedName("observer_position") val observerPosition: String? = null,
    @SerializedName("observer_contractor") val observerContractor: String? = null,
    @SerializedName("vehicle_asset_id") val vehicleAssetId: Long? = null,
    @SerializedName("vehicle_identifier") val vehicleIdentifier: String? = null,
    @SerializedName("vehicle_name") val vehicleName: String? = null,
    @SerializedName("vehicle_type") val vehicleType: String? = null,
    @SerializedName("vehicle_contractor") val vehicleContractor: String? = null,
    @SerializedName("equipment_description") val equipmentDescription: String? = null
)

data class UploadResponse(val success: Boolean = false)

data class PhotoUploadResponse(
    val photoUrl: String? = null,
    val sizeBytes: Long = 0,
    val fileName: String? = null
)

data class AccessLogDto(
    val uuid: String,
    @SerializedName("project_id") val projectId: Int?,
    @SerializedName("unique_id_value") val uniqueIdValue: String?,
    @SerializedName("access_point_id") val accessPointId: Int?,
    @SerializedName("terminal_id") val terminalId: String?,
    @SerializedName("event_time") val eventTime: String,
    val result: String,
    @SerializedName("failure_reason") val failureReason: String? = null
)

data class IncidentDto(
    val uuid: String,
    @SerializedName("project_id") val projectId: Int?,
    @SerializedName("unique_id_value") val uniqueIdValue: String?,
    @SerializedName("access_log_id") val accessLogId: Long? = null,
    @SerializedName("incident_type") val incidentType: String,
    val severity: String?,
    val description: String? = null,
    val status: String,
    @SerializedName("event_time") val eventTime: String
)

data class SessionDto(
    val uuid: String?,
    @SerializedName("project_id") val projectId: Int?,
    @SerializedName("unique_id_value") val uniqueIdValue: String,
    val status: String
)

data class HeartbeatPayload(
    @SerializedName("batteryLevel") val batteryLevel: Int,
    val latitude: Double?,
    val longitude: Double?,
    @SerializedName("gpsAccuracyM") val gpsAccuracyM: Float?,
    @SerializedName("appVersion") val appVersion: String,
    @SerializedName("lastSyncUtc") val lastSyncUtc: String,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("deviceModel") val deviceModel: String
)

// ── Training Compliance ─────────────────────────────────────────────────────

data class TrainingComplianceDto(
    val trainingDefinitionId: Long = 0,
    val trainingCode: String = "",
    val trainingName: String = "",
    val isMandatory: Boolean = false,
    val status: String = "MISSING",
    val completedDate: String? = null,
    val expiryDate: String? = null,
    val score: Double? = null
)

// ── Document Compliance ─────────────────────────────────────────────────────

data class DocumentComplianceDto(
    val documentTypeId: Int = 0,
    val typeCode: String = "",
    val typeName: String = "",
    val isRequired: Boolean = false,
    val isMandatory: Boolean = false,
    val status: String = "MISSING"
)

// ── Bulk Compliance (sync download) ────────────────────────────────────────

data class BulkComplianceResponse(
    val trainings: List<BulkTrainingComplianceDto> = emptyList(),
    val documents: List<BulkDocumentComplianceDto> = emptyList()
)

data class BulkTrainingComplianceDto(
    val uniqueIdValue: String = "",
    val badgeNumber: String = "",
    val trainingDefinitionId: Long = 0,
    val trainingCode: String = "",
    val trainingName: String = "",
    val isMandatory: Boolean = false,
    val status: String = "MISSING",
    val completedDate: String? = null,
    val expiryDate: String? = null
)

data class BulkDocumentComplianceDto(
    val uniqueIdValue: String = "",
    val documentTypeId: Int = 0,
    val typeCode: String = "",
    val typeName: String = "",
    val isMandatory: Boolean = false,
    val status: String = "missing",
    val personDocumentId: Long? = null
)
