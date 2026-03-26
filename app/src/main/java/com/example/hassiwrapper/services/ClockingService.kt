package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Core business logic for access control scanner.
 * Direct port of clocking.service.js — same ENTRY/EXIT/AUTO decisions,
 * anti-bounce cooldown, work session management.
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
        val direction: String = "",
        val reason: String? = null,
        val result: String = if (success) "GRANTED" else "DENIED",
        val failure_reason: String? = reason
    )

    suspend fun processScan(
        badgeOrUuid: String,
        requestedDirection: String,
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
            return logAndReturnDenied("UNKNOWN_PERSON", badgeOrUuid, requestedDirection, accessPointId, projectId, scanMethod)
        }

        // Validate project
        if (person.project_id != null && person.project_id != projectId) {
            return logAndReturnDenied("WRONG_PROJECT", badgeOrUuid, requestedDirection, accessPointId, projectId, scanMethod, person)
        }

        // 2. Anti-bounce
        val lastLog = accessLogDao.getLastByPerson(person.unique_id_value)
        if (lastLog != null) {
            val lastTime = Instant.parse(lastLog.event_time)
            val secondsSince = (Instant.now().epochSecond - lastTime.epochSecond)
            if (secondsSince < COOLDOWN_SECONDS && lastLog.result == "GRANTED") {
                return logAndReturnDenied("DUPLICATE_SCAN", badgeOrUuid, requestedDirection, accessPointId, projectId, scanMethod, person)
            }
        }

        // 3. Resolve direction
        val todayStr = LocalDate.now().toString()
        val openSession = workSessionDao.getOpenSession(person.unique_id_value, todayStr)
        val actualDirection = when (requestedDirection) {
            "AUTO" -> if (openSession != null) "EXIT" else "ENTRY"
            else -> requestedDirection
        }

        // 4. Validate direction logic
        val isEntry = actualDirection == "ENTRY"
        if (isEntry && openSession != null) {
            return logAndReturnDenied("ALREADY_IN", badgeOrUuid, actualDirection, accessPointId, projectId, scanMethod, person)
        }
        if (!isEntry && openSession == null) {
            return logAndReturnDenied("NOT_IN", badgeOrUuid, actualDirection, accessPointId, projectId, scanMethod, person)
        }

        // 4b. Schedule validation (soft — log incident but still grant)
        if (isEntry) {
            try {
                val rules = rulesService.resolvePersonRules(person, configRepo)
                if (!rulesService.isWithinSchedule(rules)) {
                    incidentService.createIncident(
                        "OUTSIDE_SCHEDULE",
                        person.unique_id_value,
                        person.badge_number,
                        null, null
                    )
                }
            } catch (_: Exception) { }
        }

        // 5. Build granted log
        val terminalId = configRepo.get("terminal_id")
        val logEntity = AccessLogEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = person.unique_id_value,
            access_point_id = accessPointId,
            terminal_id = terminalId,
            event_time = Instant.now().toString(),
            direction = actualDirection,
            result = "GRANTED",
            failure_reason = null,
            scan_method = scanMethod,
            synced = false
        )
        val logId = accessLogDao.insert(logEntity)
        val savedLog = logEntity.copy(id = logId)

        // 6. Manage work session
        if (isEntry) {
            workSessionDao.insert(
                WorkSessionEntity(
                    project_id = projectId,
                    unique_id_value = person.unique_id_value,
                    session_date = todayStr,
                    first_entry_log_id = logId,
                    clock_in = savedLog.event_time,
                    entry_time = savedLog.event_time,
                    status = "OPEN"
                )
            )
        } else if (openSession != null) {
            workSessionDao.closeSession(openSession.id, savedLog.event_time, logId)
        }

        return ScanResult(
            success = true,
            person = person,
            log = savedLog,
            direction = actualDirection
        )
    }

    private suspend fun logAndReturnDenied(
        reasonCode: String,
        badgeNumber: String,
        direction: String,
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
            direction = if (direction == "AUTO") "UNKNOWN" else direction,
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
            direction = direction,
            reason = reasonCode
        )
    }
}
