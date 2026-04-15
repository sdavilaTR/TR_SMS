package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.HseObservationDao
import com.example.hassiwrapper.data.db.dao.HseObservationPhotoDao
import com.example.hassiwrapper.data.db.entities.HseObservationEntity
import com.example.hassiwrapper.data.db.entities.HseObservationPhotoEntity
import com.example.hassiwrapper.data.db.entities.PersonEntity
import com.google.gson.Gson
import java.time.Instant
import java.util.UUID

class ObservationService(
    private val dao: HseObservationDao,
    private val configRepo: ConfigRepository,
    private val photoDao: HseObservationPhotoDao? = null
) {
    companion object {
        private const val TAG = "ObservationService"

        const val TARGET_WORKER = "WORKER"
        const val TARGET_VEHICLE = "VEHICLE"
        const val TARGET_SITE = "SITE_CONDITION"
        const val TARGET_EQUIPMENT = "EQUIPMENT"

        val HSE_ROLE_KEYWORDS = listOf("HSE", "SUPERVISOR", "SAFETY", "SEGURIDAD", "QHSE")

        val CATEGORY_CODES = listOf(
            "PPE", "SITUATIONAL_AWARENESS", "SAFETY_DEVICES", "ISOLATION_LOCKOUT",
            "SAFETY_SIGNAGE", "TOOLS_EQUIPMENT", "LINE_OF_FIRE", "HEALTH_HYGIENE",
            "WORKPLACE_ENVIRONMENT", "LIFTING", "MANUAL_HANDLING", "HOUSEKEEPING",
            "TOXIC_FLAMMABLE", "WORK_PLANNING", "WORKING_AT_HEIGHT", "CONFINED_SPACE",
            "HOT_WORK", "EXCAVATION", "DRIVING_VEHICLES", "SUPERVISION",
            "PROCEDURES", "SECURITY", "IMPROVEMENT_OPPORTUNITY", "EMERGENCY_RESPONSE"
        )

        val CATEGORY_LABELS = mapOf(
            "PPE" to "EPP",
            "SITUATIONAL_AWARENESS" to "Conciencia situacional",
            "SAFETY_DEVICES" to "Dispositivos de seguridad",
            "ISOLATION_LOCKOUT" to "Aislamiento/bloqueo",
            "SAFETY_SIGNAGE" to "Señalización seguridad",
            "TOOLS_EQUIPMENT" to "Herramientas/equipos",
            "LINE_OF_FIRE" to "Línea de fuego",
            "HEALTH_HYGIENE" to "Salud/higiene/agua",
            "WORKPLACE_ENVIRONMENT" to "Entorno de trabajo",
            "LIFTING" to "Levantamiento",
            "MANUAL_HANDLING" to "Manejo manual/mecánico",
            "HOUSEKEEPING" to "Orden y limpieza",
            "TOXIC_FLAMMABLE" to "Tóxico/inflamable",
            "WORK_PLANNING" to "Planificación/autorización",
            "WORKING_AT_HEIGHT" to "Trabajo en altura",
            "CONFINED_SPACE" to "Espacio confinado",
            "HOT_WORK" to "Trabajo en caliente",
            "EXCAVATION" to "Excavación",
            "DRIVING_VEHICLES" to "Conducción/vehículos",
            "SUPERVISION" to "Supervisión",
            "PROCEDURES" to "Procedimientos",
            "SECURITY" to "Seguridad",
            "IMPROVEMENT_OPPORTUNITY" to "Oportunidad de mejora",
            "EMERGENCY_RESPONSE" to "Respuesta emergencia"
        )

        /** Allowed category codes per target_type (per plan). */
        val CATEGORIES_BY_TARGET: Map<String, List<String>> = mapOf(
            TARGET_WORKER to listOf(
                "PPE", "SITUATIONAL_AWARENESS", "SAFETY_DEVICES", "LINE_OF_FIRE",
                "HEALTH_HYGIENE", "LIFTING", "MANUAL_HANDLING",
                "WORKING_AT_HEIGHT", "CONFINED_SPACE", "HOT_WORK", "EXCAVATION",
                "SUPERVISION", "PROCEDURES", "IMPROVEMENT_OPPORTUNITY"
            ),
            TARGET_VEHICLE to listOf(
                "SITUATIONAL_AWARENESS", "SAFETY_DEVICES", "SAFETY_SIGNAGE",
                "TOOLS_EQUIPMENT", "DRIVING_VEHICLES", "PROCEDURES",
                "SECURITY", "IMPROVEMENT_OPPORTUNITY"
            ),
            TARGET_SITE to listOf(
                "SAFETY_SIGNAGE", "WORKPLACE_ENVIRONMENT", "HOUSEKEEPING",
                "TOXIC_FLAMMABLE", "WORK_PLANNING", "EXCAVATION",
                "SECURITY", "IMPROVEMENT_OPPORTUNITY", "EMERGENCY_RESPONSE"
            ),
            TARGET_EQUIPMENT to listOf(
                "SAFETY_DEVICES", "ISOLATION_LOCKOUT", "TOOLS_EQUIPMENT",
                "LIFTING", "MANUAL_HANDLING", "HOT_WORK",
                "PROCEDURES", "IMPROVEMENT_OPPORTUNITY"
            )
        )

        /** Case-insensitive match against a person's position/category_code. */
        fun isHseRole(person: PersonEntity?): Boolean {
            if (person == null) return false
            val haystack = ((person.position ?: "") + " " + (person.category_code ?: "")).uppercase()
            return HSE_ROLE_KEYWORDS.any { haystack.contains(it) }
        }
    }

    private val gson = Gson()

    suspend fun createObservation(
        uniqueIdValue: String?,
        observedName: String?,
        observedBadge: String?,
        observedDepartment: String?,
        observedPosition: String?,
        observedContractor: String?,
        description: String,
        observationType: String,
        safetyType: String,
        location: String?,
        areaAuthority: String?,
        interventionAction: String?,
        outcome: String?,
        actionTaken: String?,
        coachingStatus: String,
        additionalComments: String?,
        categories: List<String>,
        targetType: String = TARGET_WORKER,
        observerUniqueId: String? = null,
        observerName: String? = null,
        observerPosition: String? = null,
        observerContractor: String? = null,
        vehicleAssetId: Long? = null,
        vehicleIdentifier: String? = null,
        vehicleName: String? = null,
        vehicleType: String? = null,
        vehicleContractor: String? = null,
        equipmentDescription: String? = null
    ): Pair<Long, String> {
        val projectId = configRepo.getInt("current_project_id")
        val deviceId = configRepo.get("device_id")

        val uuid = UUID.randomUUID().toString()
        val entity = HseObservationEntity(
            uuid = uuid,
            project_id = projectId,
            observer_device_id = deviceId,
            unique_id_value = uniqueIdValue,
            observed_name = observedName,
            observed_badge = observedBadge,
            observed_department = observedDepartment,
            observed_position = observedPosition,
            observed_contractor = observedContractor,
            observation_date = Instant.now().toString(),
            location = location,
            area_authority = areaAuthority,
            description = description,
            observation_type = observationType,
            safety_type = safetyType,
            intervention_action = interventionAction,
            outcome = outcome,
            action_taken = actionTaken,
            coaching_status = coachingStatus,
            additional_comments = additionalComments,
            categories = if (categories.isNotEmpty()) gson.toJson(categories) else null,
            target_type = targetType,
            observer_unique_id = observerUniqueId,
            observer_name = observerName,
            observer_position = observerPosition,
            observer_contractor = observerContractor,
            vehicle_asset_id = vehicleAssetId,
            vehicle_identifier = vehicleIdentifier,
            vehicle_name = vehicleName,
            vehicle_type = vehicleType,
            vehicle_contractor = vehicleContractor,
            equipment_description = equipmentDescription
        )

        val id = dao.insert(entity)
        Log.i(TAG, "Observation created (id=$id, uuid=$uuid, target=$targetType)")
        return Pair(id, uuid)
    }

    suspend fun addPhoto(observationUuid: String, localPath: String, fileName: String?, sortOrder: Int) {
        val dao = photoDao ?: return
        dao.insert(
            HseObservationPhotoEntity(
                uuid = UUID.randomUUID().toString(),
                observation_uuid = observationUuid,
                local_path = localPath,
                file_name = fileName,
                sort_order = sortOrder
            )
        )
    }

    suspend fun getPendingCount(): Int = dao.getPendingCount()
}
