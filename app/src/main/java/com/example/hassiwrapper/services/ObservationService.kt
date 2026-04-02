package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.HseObservationDao
import com.example.hassiwrapper.data.db.entities.HseObservationEntity
import com.google.gson.Gson
import java.time.Instant
import java.util.UUID

class ObservationService(
    private val dao: HseObservationDao,
    private val configRepo: ConfigRepository
) {
    companion object {
        private const val TAG = "ObservationService"

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
        categories: List<String>
    ): Long {
        val projectId = configRepo.getInt("current_project_id")
        val deviceId = configRepo.get("device_id")

        val entity = HseObservationEntity(
            uuid = UUID.randomUUID().toString(),
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
            categories = if (categories.isNotEmpty()) gson.toJson(categories) else null
        )

        val id = dao.insert(entity)
        Log.i(TAG, "Observation created (id=$id, uuid=${entity.uuid})")
        return id
    }

    suspend fun getPendingCount(): Int = dao.getPendingCount()
}
