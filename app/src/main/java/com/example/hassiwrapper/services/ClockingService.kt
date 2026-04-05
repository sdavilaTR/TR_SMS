package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import java.time.Instant
import java.util.UUID

/**
 * Core business logic for access control scanner.
 * Validates person OR vehicle identity and anti-bounce. Direction (ENTRY/EXIT)
 * is managed by the API/front-end, not by this device.
 */
class ClockingService(
    private val personDao: PersonDao,
    private val accessLogDao: AccessLogDao,
    private val workSessionDao: WorkSessionDao,
    private val incidentService: IncidentService,
    private val rulesService: RulesService,
    private val configRepo: ConfigRepository,
    private val vehicleDao: VehicleDao? = null
) {
    companion object {
        private const val COOLDOWN_SECONDS = 30
        const val SCAN_TYPE_PERSON = "PERSON"
        const val SCAN_TYPE_VEHICLE = "VEHICLE"
        private const val VEHICLE_QR_PREFIX = "VEH:"
    }

    data class ScanResult(
        val success: Boolean,
        val person: PersonEntity? = null,
        val vehicle: VehicleEntity? = null,
        val log: AccessLogEntity? = null,
        val reason: String? = null,
        val result: String = if (success) "GRANTED" else "DENIED",
        val failure_reason: String? = reason,
        val scanType: String = SCAN_TYPE_PERSON
    )

    suspend fun processScan(
        badgeOrUuid: String,
        accessPointId: Int,
        projectId: Int,
        scanMethod: String
    ): ScanResult {
        // Detect if this is a vehicle QR code (prefixed with "VEH:")
        if (badgeOrUuid.startsWith(VEHICLE_QR_PREFIX)) {
            val vehicleId = badgeOrUuid.removePrefix(VEHICLE_QR_PREFIX).trim()
            return processVehicleScan(vehicleId, accessPointId, projectId, scanMethod)
        }

        // Otherwise, process as a person scan (original flow)
        return processPersonScan(badgeOrUuid, accessPointId, projectId, scanMethod)
    }

    // ── Person scan (original logic, unchanged) ──────────────────────────────

    private suspend fun processPersonScan(
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
            log = savedLog,
            scanType = SCAN_TYPE_PERSON
        )
    }

    // ── Vehicle scan ─────────────────────────────────────────────────────────

    private suspend fun processVehicleScan(
        vehicleId: String,
        accessPointId: Int,
        projectId: Int,
        scanMethod: String
    ): ScanResult {
        val dao = vehicleDao ?: return ScanResult(
            success = false,
            reason = "VEHICLE_NOT_SUPPORTED",
            scanType = SCAN_TYPE_VEHICLE
        )

        // Try to find by asset_id (numeric) or asset_uuid (string)
        val vehicle = vehicleId.toLongOrNull()?.let { dao.getById(it) }
            ?: dao.getByUuid(vehicleId)

        if (vehicle == null) {
            return logVehicleAndReturnDenied("UNKNOWN_VEHICLE", vehicleId, accessPointId, projectId, scanMethod)
        }

        // Validate active status
        if (!vehicle.is_active) {
            return logVehicleAndReturnDenied("INACTIVE_VEHICLE", vehicleId, accessPointId, projectId, scanMethod, vehicle)
        }

        // Validate project
        if (vehicle.project_id != null && vehicle.project_id != projectId) {
            return logVehicleAndReturnDenied("WRONG_PROJECT", vehicleId, accessPointId, projectId, scanMethod, vehicle)
        }

        // Anti-bounce: use asset_uuid as unique_id_value for vehicle logs
        val vehicleUuid = "VEH:${vehicle.asset_uuid}"
        val lastLog = accessLogDao.getLastByPerson(vehicleUuid)
        if (lastLog != null) {
            val lastTime = Instant.parse(lastLog.event_time)
            val secondsSince = (Instant.now().epochSecond - lastTime.epochSecond)
            if (secondsSince < COOLDOWN_SECONDS && lastLog.result == "GRANTED") {
                return logVehicleAndReturnDenied("DUPLICATE_SCAN", vehicleId, accessPointId, projectId, scanMethod, vehicle)
            }
        }

        // Build granted log
        val terminalId = configRepo.get("terminal_id")
        val logEntity = AccessLogEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = vehicleUuid,
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

        return ScanResult(
            success = true,
            vehicle = vehicle,
            log = logEntity.copy(id = logId),
            scanType = SCAN_TYPE_VEHICLE
        )
    }

    // ── Log helpers ──────────────────────────────────────────────────────────

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
            reason = reasonCode,
            scanType = SCAN_TYPE_PERSON
        )
    }

    private suspend fun logVehicleAndReturnDenied(
        reasonCode: String,
        vehicleId: String,
        accessPointId: Int,
        projectId: Int,
        scanMethod: String,
        vehicle: VehicleEntity? = null
    ): ScanResult {
        val terminalId = configRepo.get("terminal_id")
        val vehicleUuid = vehicle?.let { "VEH:${it.asset_uuid}" }
        val logEntity = AccessLogEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            unique_id_value = vehicleUuid,
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
            vehicle = vehicle,
            log = logEntity.copy(id = logId),
            reason = reasonCode,
            scanType = SCAN_TYPE_VEHICLE
        )
    }
}
