package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsPositionDao
import com.example.hassiwrapper.data.db.dao.SmsSubPositionDao
import com.example.hassiwrapper.data.db.entities.SmsIncidentEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSubPositionEntity
import java.time.Instant
import java.util.UUID

/**
 * Manages SMS spool damage incidents — auto-fills position (from terminal's
 * configured location) and author (from the assigned-operator setting).
 */
class SmsIncidentService(
    private val incidentDao: SmsIncidentDao,
    private val configRepo: ConfigRepository,
    private val positionDao: SmsPositionDao,
    private val subPositionDao: SmsSubPositionDao
) {
    companion object {
        val SEVERITIES = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL")
        val LOCATION_TYPES = listOf("LAYDOWN", "SITE", "WORKSHOP")

        const val TYPE_DAMAGE = "DAMAGE"
        const val TYPE_REVISION_MISMATCH = "REVISION_MISMATCH"

        const val STATUS_OPEN = "OPEN"
        const val STATUS_REPRINT_APPROVED = "REPRINT_APPROVED"
        const val STATUS_CLOSED = "CLOSED"

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

    /** Sub-sections (Laydown/Site) belonging to the terminal's configured position, for the incident's sub-section picker. */
    suspend fun getSubPositionsForCurrentPosition(): List<SmsSubPositionEntity> {
        val position = getCurrentPosition() ?: return emptyList()
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        return subPositionDao.getByPosition(projectId, position.position_id)
    }

    suspend fun createIncident(
        spoolCode: String,
        spoolSuffix: String?,
        description: String,
        vehiclePlate: String?,
        locationType: String,
        locationDetail: String?,
        severity: String,
        photoPath: String?,
        subPositionId: Long? = null
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
            sub_position_id = subPositionId,
            position_code = position?.code,
            author_name = getAssignedOperatorName(),
            photo_path = photoPath,
            event_date = Instant.now().toString(),
            status = "OPEN",
            synced = false,
            device_code = configRepo.get("device_code")?.takeIf { it.isNotBlank() }
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

    /**
     * Auto-raises a REVISION_MISMATCH incident when a scanned tag's physical revision disagrees
     * with the stored engineering revision — this is what "notifies supervisors" (the incident
     * syncs to the backend and shows up in Incidencias for anyone with a supervisor role).
     * Idempotent: returns null without inserting if this spool already has one open, so repeat
     * scans of the same still-mismatched tag don't spam duplicates.
     */
    suspend fun createRevisionMismatchIncident(
        spoolCode: String,
        spoolSuffix: String?,
        scannedRevision: String,
        storedRevision: String
    ): SmsIncidentEntity? {
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        // JAFURAH-imported spools sometimes already bake the SPnn suffix into spool_code; storing
        // it again in spool_suffix would double it up wherever it's displayed ("...-SP02-SP02").
        // Mirrors SmsSpoolEntity.displayCode's own rule for detecting that case.
        val suffix = spoolSuffix?.takeIf { it.isNotBlank() }
            ?.takeUnless { Regex("(?i)^SP\\d+$").matches(it) && spoolCode.endsWith("-$it", ignoreCase = true) }
        if (incidentDao.getOpenRevisionMismatch(projectId, spoolCode, suffix) != null) return null

        val position = getCurrentPosition()
        val incident = SmsIncidentEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            spool_code = spoolCode,
            spool_suffix = suffix,
            description = "Revisión física escaneada ($scannedRevision) distinta a la registrada ($storedRevision). Requiere reimpresión de tag.",
            location_type = "SITE",
            severity = "MEDIUM",
            position_id = position?.position_id,
            position_code = position?.code,
            author_name = getAssignedOperatorName(),
            event_date = Instant.now().toString(),
            status = STATUS_OPEN,
            synced = false,
            device_code = configRepo.get("device_code")?.takeIf { it.isNotBlank() },
            incident_type = TYPE_REVISION_MISMATCH,
            scanned_revision = scannedRevision,
            stored_revision = storedRevision
        )
        val id = incidentDao.insert(incident)
        return incident.copy(id = id)
    }

    /** Supervisor approval step: flags the incident as "pendiente de reimpresión" — the actual
     *  print happens outside the app (office/print station), so this is a status flag, not a print job. */
    suspend fun approveReprint(incidentId: Long) {
        incidentDao.setStatus(incidentId, STATUS_REPRINT_APPROVED)
    }
}
