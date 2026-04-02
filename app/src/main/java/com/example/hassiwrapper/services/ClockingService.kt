package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import java.time.Instant
import java.util.UUID

/**
 * Core business logic for access control scanner.
 * Validates person identity and anti-bounce. Direction (ENTRY/EXIT)
 * is managed by the API/front-end, not by this device.
 */
class ClockingService(
    private val personDao: PersonDao,
    private val accessLogDao: AccessLogDao,
    private val workSessionDao: WorkSessionDao,
    private val incidentService: IncidentService,
    private val rulesService: RulesService,
    private val configRepo: ConfigRepository
) {
    companion object {
        private const val COOLDOWN_SECONDS = 30
    }

    data class ScanResult(
        val success: Boolean,
        val person: PersonEntity? = null,
        val log: AccessLogEntity? = null,
        val reason: String? = null,
        val result: String = if (success) "GRANTED" else "DENIED",
        val failure_reason: String? = reason
    )

    suspend fun processScan(
        badgeOrUuid: String,
        accessPointId: Int,
        projectId: Int,
        scanMethod: String
    ): ScanResult {
        // 1. Find person
        val person = if (badgeOrUuid.length > 20) {
            personDao.getByUuid(badgeOrUuid)
        } else {
            personDao.getByBadge(badgeOrUuid)
        }

        if (person == null) {
            return logAndReturnDenied("UNKNOWN_PERSON", badgeOrUuid, accessPointId, projectId, scanMethod)
        }

        // Validate active status
        if (!person.is_active) {
            return logAndReturnDenied("INACTIVE_WORKER", badgeOrUuid, accessPointId, projectId, scanMethod, person)
        }

        // Validate project
        if (person.project_id != null && person.project_id != projectId) {
            return logAndReturnDenied("WRONG_PROJECT", badgeOrUuid, accessPointId, projectId, scanMethod, person)
        }

        // 2. Anti-bounce
        val lastLog = accessLogDao.getLastByPerson(person.unique_id_value)
        if (lastLog != null) {
            val lastTime = Instant.parse(lastLog.event_time)
            val secondsSince = (Instant.now().epochSecond - lastTime.epochSecond)
            if (secondsSince < COOLDOWN_SECONDS && lastLog.result == "GRANTED") {
                return logAndReturnDenied("DUPLICATE_SCAN", badgeOrUuid, accessPointId, projectId, scanMethod, person)
            }
        }

        // 3. Build granted log
        val terminalId = configRepo.get("terminal_id")
        val logEntity = AccessLogEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = person.unique_id_value,
            access_point_id = accessPointId,
            terminal_id = terminalId,
            event_time = Instant.now().toString(),
            direction = "ACCESS",
            result = "GRANTED",
            failure_reason = null,
            scan_method = scanMethod,
            synced = false
        )
        val logId = accessLogDao.insert(logEntity)
        val savedLog = logEntity.copy(id = logId)

        return ScanResult(
            success = true,
            person = person,
            log = savedLog
        )
    }

    private suspend fun logAndReturnDenied(
        reasonCode: String,
        badgeNumber: String,
        accessPointId: Int,
        projectId: Int,
        scanMethod: String,
        person: PersonEntity? = null
    ): ScanResult {
        val terminalId = configRepo.get("terminal_id")
        val logEntity = AccessLogEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = person?.unique_id_value,
            access_point_id = accessPointId,
            terminal_id = terminalId,
            event_time = Instant.now().toString(),
            direction = "ACCESS",
            result = "DENIED",
            failure_reason = reasonCode,
            scan_method = scanMethod,
            synced = false
        )
        val logId = accessLogDao.insert(logEntity)

        return ScanResult(
            success = false,
            person = person,
            log = logEntity.copy(id = logId),
            reason = reasonCode
        )
    }
}
