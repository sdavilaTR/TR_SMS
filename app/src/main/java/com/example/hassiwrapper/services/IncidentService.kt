package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.IncidentDao
import com.example.hassiwrapper.data.db.entities.IncidentEntity
import java.time.Instant
import java.util.UUID

/**
 * Manages security violations and access incidents — port of incident.service.js.
 */
class IncidentService(
    private val incidentDao: IncidentDao,
    private val configRepo: ConfigRepository
) {
    companion object {
        val INCIDENT_DESCRIPTIONS = mapOf(
            "EXIT_WITHOUT_ENTRY" to "Fichaje de salida sin entrada previa registrada",
            "DUPLICATE_ENTRY" to "Fichaje de entrada duplicado sin salida intermedia",
            "BADGE_NOT_FOUND" to "Badge no encontrado en la base de datos",
            "WORKER_INACTIVE" to "Trabajador con estado inactivo o suspendido",
            "UNAUTHORIZED_ZONE" to "Fichaje en zona no autorizada para este trabajador",
            "OUTSIDE_SCHEDULE" to "Fichaje fuera del horario permitido",
            "HOURS_EXCEEDED" to "Horas trabajadas exceden el máximo permitido",
            "HOURS_INSUFFICIENT" to "Horas trabajadas por debajo del mínimo requerido"
        )

        val SEVERITY_MAP = mapOf(
            "BADGE_NOT_FOUND" to "HIGH",
            "WORKER_INACTIVE" to "HIGH",
            "UNAUTHORIZED_ZONE" to "MEDIUM",
            "OUTSIDE_SCHEDULE" to "MEDIUM",
            "HOURS_EXCEEDED" to "MEDIUM",
            "HOURS_INSUFFICIENT" to "MEDIUM",
            "EXIT_WITHOUT_ENTRY" to "LOW",
            "DUPLICATE_ENTRY" to "LOW"
        )

        val TYPE_LABELS = mapOf(
            "EXIT_WITHOUT_ENTRY" to "Salida sin entrada",
            "DUPLICATE_ENTRY" to "Entrada duplicada",
            "BADGE_NOT_FOUND" to "Badge no encontrado",
            "WORKER_INACTIVE" to "Worker inactivo",
            "UNAUTHORIZED_ZONE" to "Zona no autorizada",
            "OUTSIDE_SCHEDULE" to "Fuera de horario",
            "HOURS_EXCEEDED" to "Exceso de horas",
            "HOURS_INSUFFICIENT" to "Horas insuficientes"
        )
    }

    suspend fun createIncident(
        type: String,
        uniqueIdValue: String?,
        badgeNumber: String?,
        relatedAccessLogId: Long?,
        zoneId: Int?,
        customDescription: String? = null
    ): IncidentEntity {
        val projectId = configRepo.getInt("current_project_id")
        val incident = IncidentEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = uniqueIdValue,
            event_time = Instant.now().toString(),
            incident_type = type,
            severity = SEVERITY_MAP[type],
            description = customDescription ?: INCIDENT_DESCRIPTIONS[type],
            access_log_id = relatedAccessLogId,
            zone_id = zoneId,
            status = "OPEN",
            badge_number = badgeNumber,
            synced = false
        )
        val id = incidentDao.insert(incident)
        return incident.copy(id = id)
    }

    suspend fun getUnresolvedIncidents() = incidentDao.getUnresolved()
    suspend fun getAllIncidents() = incidentDao.getAll()
    suspend fun getUnresolvedCount() = incidentDao.getUnresolvedCount()

    fun getSeverityColor(severity: String?): Int {
        return when (severity) {
            "HIGH" -> 0xFFef4444.toInt()
            "MEDIUM" -> 0xFFf59e0b.toInt()
            "LOW" -> 0xFF3b82f6.toInt()
            else -> 0xFF6b7280.toInt()
        }
    }

    fun getTypeLabel(type: String): String = TYPE_LABELS[type] ?: type
}
