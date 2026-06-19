package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsPositionDao
import com.example.hassiwrapper.data.db.entities.SmsIncidentEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import java.time.Instant
import java.util.UUID

/**
 * Manages SMS spool damage incidents — auto-fills position (from terminal's
 * configured location) and author (from the assigned-operator setting).
 */
class SmsIncidentService(
    private val incidentDao: SmsIncidentDao,
    private val configRepo: ConfigRepository,
    private val positionDao: SmsPositionDao
) {
    companion object {
        val SEVERITIES = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
        val LOCATION_TYPES = listOf("LAYDOWN", "SITE", "WORKSHOP")

        fun getSeverityColor(severity: String?): Int = when (severity) {
            "CRITICAL" -> 0xFFb91c1c.toInt()
            "HIGH" -> 0xFFef4444.toInt()
            "MEDIUM" -> 0xFFf59e0b.toInt()
            "LOW" -> 0xFF3b82f6.toInt()
            else -> 0xFF6b7280.toInt()
        }
    }

    /** Resolves the terminal's configured position (set via Settings → device_location). */
    suspend fun getCurrentPosition(): SmsPositionEntity? {
        val code = configRepo.get("device_location")?.takeIf { it.isNotBlank() } ?: return null
        return positionDao.getByCode(code)
    }

    /** Resolves the operator name configured for this terminal (Settings → Operador asignado). */
    suspend fun getAssignedOperatorName(): String? =
        configRepo.get("assigned_operator_name")?.takeIf { it.isNotBlank() }

    suspend fun createIncident(
        spoolCode: String,
        spoolSuffix: String?,
        description: String,
        vehiclePlate: String?,
        locationType: String,
        locationDetail: String?,
        severity: String,
        photoPath: String?
    ): SmsIncidentEntity {
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        val position = getCurrentPosition()
        val incident = SmsIncidentEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            spool_code = spoolCode,
            spool_suffix = spoolSuffix,
            description = description,
            vehicle_plate = vehiclePlate,
            location_type = locationType,
            location_detail = locationDetail,
            severity = severity,
            position_id = position?.position_id,
            position_code = position?.code,
            author_name = getAssignedOperatorName(),
            photo_path = photoPath,
            event_date = Instant.now().toString(),
            status = "OPEN",
            synced = false
        )
        val id = incidentDao.insert(incident)
        return incident.copy(id = id)
    }

    suspend fun getIncidents(projectId: Int) = incidentDao.getByProject(projectId)
    suspend fun getCriticalCount(projectId: Int) = incidentDao.getCriticalCount(projectId)

    /** Closes an incident, recording the assigned operator and timestamp. */
    suspend fun closeIncident(incidentId: Long) {
        incidentDao.close(incidentId, getAssignedOperatorName(), Instant.now().toString())
    }
}
