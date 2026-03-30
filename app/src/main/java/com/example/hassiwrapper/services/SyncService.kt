package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import com.example.hassiwrapper.network.ApiClient
import com.example.hassiwrapper.network.AtlasApiService
import com.example.hassiwrapper.network.dto.*
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Full synchronisation service — port of sync.js.
 * Cycle: registerDevice → masterData → upload logs/incidents/sessions → download workers
 *
 * Transient failures (network errors, HTTP 5xx) are retried with exponential back-off
 * for up to RETRY_BUDGET_MS. Non-retryable failures (4xx, auth) surface immediately.
 */
class SyncService(
    private val apiClient: ApiClient,
    private val configRepo: ConfigRepository,
    private val projectDao: ProjectDao,
    private val zoneDao: ZoneDao,
    private val contractorDao: ContractorDao,
    private val personDao: PersonDao,
    private val accessPointDao: AccessPointDao,
    private val cryptoKeyDao: CryptoKeyDao,
    private val accessLogDao: AccessLogDao,
    private val incidentDao: IncidentDao,
    private val workSessionDao: WorkSessionDao
) {
    companion object {
        private const val TAG = "SyncService"

        // Retry budget — keep trying for up to 90 seconds total
        private const val RETRY_BUDGET_MS  = 90_000L
        private const val RETRY_INITIAL_MS =  5_000L   // first wait: 5 s
        private const val RETRY_MAX_MS     = 30_000L   // cap per wait: 30 s
    }

    /** Thrown for errors that are worth retrying (network issues, server 5xx). */
    private class TransientException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    data class SyncResult(
        val success: Boolean = false,
        val logsUploaded: Int = 0,
        val incidentsUploaded: Int = 0,
        val sessionsUploaded: Int = 0,
        val workersAdded: Int = 0,
        val workersUpdated: Int = 0,
        val workersSkipped: Int = 0,
        val error: String? = null
    )

    data class PendingCounts(
        val logs: Int = 0,
        val incidents: Int = 0,
        val sessions: Int = 0
    )

    /** Structured info passed to the caller on each retry so the UI can show it. */
    data class RetryState(val attempt: Int, val waitSeconds: Int)

    suspend fun getPendingCounts(): PendingCounts {
        return PendingCounts(
            logs = accessLogDao.getPendingCount(),
            incidents = incidentDao.getPendingCount(),
            sessions = workSessionDao.getPendingCount()
        )
    }

    /**
     * Runs a full sync cycle with automatic retry on transient failures.
     * [onRetry] is called (on the caller's coroutine dispatcher) each time a retry
     * is about to be scheduled, with the attempt number and wait in seconds.
     */
    suspend fun fullSync(onRetry: ((RetryState) -> Unit)? = null): SyncResult {
        val deadline = System.currentTimeMillis() + RETRY_BUDGET_MS
        var attempt = 0
        var waitMs = RETRY_INITIAL_MS

        // Reset cached URL so we re-probe primary vs fallback each sync,
        // preventing stale fallback URL when the device was offline at startup.
        apiClient.resetResolvedBase()

        while (true) {
            attempt++
            try {
                return doSync()
            } catch (e: TransientException) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    Log.e(TAG, "Sync budget exhausted after $attempt attempt(s): ${e.message}")
                    return SyncResult(error = "Sin respuesta tras $attempt intentos: ${e.message}")
                }
                val actualWait = minOf(waitMs, remaining)
                Log.w(TAG, "Attempt $attempt failed (${e.message}). Retry in ${actualWait}ms")
                onRetry?.invoke(RetryState(attempt, (actualWait / 1000).toInt()))
                delay(actualWait)
                waitMs = minOf(waitMs * 2, RETRY_MAX_MS)
                // Re-resolve URL before next attempt in case connectivity changed
                apiClient.resetResolvedBase()
            } catch (e: Exception) {
                // Non-transient (auth error, bad request, etc.) — surface immediately
                Log.e(TAG, "Sync failed (non-retryable)", e)
                return SyncResult(error = e.message ?: "Sync fallido")
            }
        }
    }

    /**
     * One full sync attempt. Throws [TransientException] for retryable failures.
     * Returns a final [SyncResult] for non-retryable outcomes (including partial success).
     */
    private suspend fun doSync(): SyncResult {
        val api = apiClient.getService()

        // 1. Health check — network errors and 5xx are transient
        val healthResp = try {
            api.health()
        } catch (e: Exception) {
            throw TransientException("Sin conexión con el servidor", e)
        }
        if (!healthResp.isSuccessful) {
            if (healthResp.code() >= 500) {
                throw TransientException("Servidor no disponible (HTTP ${healthResp.code()})")
            }
            // 4xx — not retryable (auth / bad config)
            return SyncResult(error = "Servidor rechazó la conexión (HTTP ${healthResp.code()})")
        }

        // 2. Register device (non-fatal, no retry)
        registerDevice(api)

        // 3. Download master data + workers (best-effort; if it fails we still upload)
        var workerResult = WorkerResult()
        try {
            val downloadResp = api.downloadSync()
            if (downloadResp.isSuccessful) {
                val data = downloadResp.body()
                if (data != null) {
                    applyMasterData(data)
                    workerResult = applyWorkers(data)
                } else {
                    Log.w(TAG, "downloadSync returned null body")
                }
            } else {
                Log.w(TAG, "downloadSync failed: HTTP ${downloadResp.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadSync exception (non-fatal): ${e.message}")
        }

        // 4. Upload pending data — each throws TransientException on network/5xx
        val (logsUploaded, logsError)         = uploadAccessLogs(api)
        val (incidentsUploaded, incidentsError) = uploadIncidents(api)
        val (sessionsUploaded, sessionsError)  = uploadSessions(api)

        val uploadErrors = listOfNotNull(logsError, incidentsError, sessionsError)
        val success = uploadErrors.isEmpty()

        if (success) {
            configRepo.set("last_sync", Instant.now().toString())
        }

        return SyncResult(
            success = success,
            logsUploaded = logsUploaded,
            incidentsUploaded = incidentsUploaded,
            sessionsUploaded = sessionsUploaded,
            workersAdded = workerResult.added,
            workersUpdated = workerResult.updated,
            workersSkipped = workerResult.skipped,
            error = if (uploadErrors.isNotEmpty()) uploadErrors.joinToString("; ") else null
        )
    }

    // ── Device registration ───────────────────────────────────────────────────

    private suspend fun registerDevice(api: AtlasApiService) {
        val deviceId = configRepo.get("device_id") ?: return
        if (deviceId == "unknown") return
        val deviceName = configRepo.get("device_name") ?: "Android Terminal"
        try {
            api.registerDevice(RegisterDeviceRequest(deviceId, deviceName))
        } catch (e: Exception) {
            Log.w(TAG, "registerDevice failed (non-fatal): ${e.message}")
        }
    }

    // ── Master data ───────────────────────────────────────────────────────────

    private suspend fun applyMasterData(data: SyncDownloadResponse) {
        data.projects?.let { list ->
            val entities = list.map { p ->
                ProjectEntity(
                    project_id = p.id ?: p.projectIdSnake ?: 0,
                    project_code = p.projectCode ?: p.projectCodeSnake ?: "",
                    project_name = p.projectName ?: p.projectNameSnake ?: "",
                    numeric_code = p.numericCode ?: p.numericCodeSnake ?: "",
                    country_code = p.countryCode ?: p.countryCodeSnake,
                    is_active = p.isActive ?: p.isActiveSnake ?: true
                )
            }
            if (entities.isNotEmpty()) {
                projectDao.insertAll(entities)
                if (configRepo.get("current_project_id") == null) {
                    configRepo.setInt("current_project_id", entities[0].project_id)
                }
            }
        }

        data.zones?.let { list ->
            val entities = list.map { z ->
                ZoneEntity(
                    zone_id = z.id ?: z.zoneIdSnake ?: 0,
                    project_id = z.projectId ?: z.projectIdSnake,
                    zone_code = z.zoneCode ?: z.zoneCodeSnake ?: "",
                    zone_name = z.zoneName ?: z.zoneNameSnake ?: "",
                    zone_type = z.zoneType ?: z.zoneTypeSnake ?: "",
                    parent_zone_id = z.parentZoneId ?: z.parentZoneIdSnake,
                    is_active = z.isActive ?: z.isActiveSnake ?: true
                )
            }
            if (entities.isNotEmpty()) zoneDao.insertAll(entities)
        }

        data.contractors?.let { list ->
            val entities = list.map { c ->
                ContractorEntity(
                    contractor_id = c.id ?: c.contractorIdSnake ?: 0,
                    contractor_code = c.contractorCode ?: c.contractorCodeSnake ?: "",
                    contractor_name = c.contractorName ?: c.contractorNameSnake ?: "",
                    legal_name = c.legalName ?: c.legalNameSnake,
                    tax_id = c.taxId ?: c.taxIdSnake,
                    contact_name = c.contactName ?: c.contactNameSnake,
                    contact_email = c.contactEmail ?: c.contactEmailSnake,
                    country_code = c.countryCode ?: c.countryCodeSnake,
                    parent_contractor_id = c.parentContractorId ?: c.parentContractorIdSnake,
                    is_active = c.isActive ?: c.isActiveSnake ?: true
                )
            }
            if (entities.isNotEmpty()) contractorDao.insertAll(entities)
        }

        data.cryptoKeys?.let { list ->
            val entities = list.map { k ->
                CryptoKeyEntity(
                    crypto_key_id = k.cryptoKeyId ?: k.cryptoKeyIdSnake ?: 0,
                    key_type = k.keyPurpose ?: k.keyPurposeSnake ?: "",
                    is_active = 1,
                    publicKeyB64 = k.publicKeyB64 ?: k.publicKeyB64Snake ?: ""
                )
            }
            if (entities.isNotEmpty()) cryptoKeyDao.insertAll(entities)
        }

        configRepo.set("masterDataLastSync", Instant.now().toString())
        Log.d(TAG, "Master data applied")
    }

    data class WorkerResult(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0)

    private suspend fun applyWorkers(data: SyncDownloadResponse): WorkerResult {
        val persons = data.persons ?: return WorkerResult()
        var added = 0; var updated = 0; var skipped = 0

        for (p in persons) {
            val entity = transformWorker(p)
            val existing = personDao.getByUuid(entity.unique_id_value)
            if (existing != null) {
                personDao.insert(entity.copy(syncStatus = "SYNCED"))
                updated++
            } else {
                try {
                    personDao.insert(entity.copy(syncStatus = "SYNCED"))
                    added++
                } catch (_: Exception) {
                    skipped++
                }
            }
        }

        configRepo.set("online_workers_count", (added + updated + skipped).toString())
        Log.d(TAG, "Workers: $added added, $updated updated, $skipped skipped")
        return WorkerResult(added, updated, skipped)
    }

    private fun transformWorker(p: PersonDto): PersonEntity {
        return PersonEntity(
            unique_id_value = p.uniqueIdValue ?: p.uniqueIdValueSnake ?: p.uuid ?: "",
            project_id = p.projectId ?: p.projectIdSnake,
            badge_number = p.badgeNumber ?: p.badgeNumberSnake ?: "",
            given_name = p.givenName ?: p.givenNameSnake ?: p.firstName ?: "",
            family_name = p.familyName ?: p.familyNameSnake ?: p.lastName ?: "",
            category_code = p.categoryCode ?: p.categoryCodeSnake ?: p.category ?: "",
            position = p.position ?: p.puesto ?: p.positionName ?: p.positionNameSnake
                ?: p.designationName ?: p.designationNameSnake ?: p.jobTitle ?: p.jobTitleSnake ?: "",
            contractor_id = p.contractorId ?: p.contractorIdSnake,
            discipline_id = p.disciplineId ?: p.disciplineIdSnake,
            designation_id = p.designationId ?: p.designationIdSnake,
            is_active = p.isActive ?: p.isActiveSnake ?: true,
            valid_from = p.validFrom ?: p.validFromSnake ?: p.entryDate ?: p.entryDateSnake
                ?: p.entranceDate ?: p.entranceDateSnake,
            valid_to = p.validTo ?: p.validToSnake,
            photo_url = p.photoUrl ?: p.photoUrlSnake,
            syncStatus = "SYNCED"
        )
    }

    // ── Upload helpers ────────────────────────────────────────────────────────
    //
    // Each returns Pair(countSynced, errorMsg):
    //   - Pair(n, null)     → success
    //   - Pair(0, msg)      → non-retryable failure (4xx); caller shows error
    //   - throws TransientException → retryable; caller's retry loop handles it

    private suspend fun uploadAccessLogs(api: AtlasApiService): Pair<Int, String?> {
        val pending = accessLogDao.getPending()
        if (pending.isEmpty()) return Pair(0, null)

        val payload = pending.map { log ->
            AccessLogDto(
                uuid = log.uuid,
                projectId = log.project_id,
                uniqueIdValue = log.unique_id_value,
                accessPointId = log.access_point_id,
                terminalId = log.terminal_id,
                eventTime = log.event_time,
                direction = log.direction,
                result = log.result,
                failureReason = log.failure_reason
            )
        }

        val response = try {
            api.uploadAccessLogs(UploadLogsRequest(payload))
        } catch (e: Exception) {
            throw TransientException("Registros: error de red (${e.message})", e)
        }

        return when {
            response.isSuccessful && response.body()?.success == true -> {
                accessLogDao.markSynced(pending.map { it.id })
                Pair(pending.size, null)
            }
            response.code() >= 500 ->
                throw TransientException("Registros: error servidor (HTTP ${response.code()})")
            else -> {
                Log.e(TAG, "uploadAccessLogs rejected: HTTP ${response.code()}")
                Pair(0, "Registros no subidos (HTTP ${response.code()})")
            }
        }
    }

    private suspend fun uploadIncidents(api: AtlasApiService): Pair<Int, String?> {
        val pending = incidentDao.getPending()
        if (pending.isEmpty()) return Pair(0, null)

        val payload = pending.map { inc ->
            IncidentDto(
                uuid = inc.uuid,
                projectId = inc.project_id,
                uniqueIdValue = inc.unique_id_value,
                accessLogId = inc.access_log_id,
                incidentType = inc.incident_type,
                severity = inc.severity,
                description = inc.description,
                status = inc.status,
                eventTime = inc.event_time
            )
        }

        val response = try {
            api.uploadIncidents(UploadIncidentsRequest(payload))
        } catch (e: Exception) {
            throw TransientException("Incidencias: error de red (${e.message})", e)
        }

        return when {
            response.isSuccessful && response.body()?.success == true -> {
                incidentDao.markSynced(pending.map { it.id })
                Pair(pending.size, null)
            }
            response.code() >= 500 ->
                throw TransientException("Incidencias: error servidor (HTTP ${response.code()})")
            else -> {
                Log.e(TAG, "uploadIncidents rejected: HTTP ${response.code()}")
                Pair(0, "Incidencias no subidas (HTTP ${response.code()})")
            }
        }
    }

    private suspend fun uploadSessions(api: AtlasApiService): Pair<Int, String?> {
        val pending = workSessionDao.getPendingClosed()
        if (pending.isEmpty()) return Pair(0, null)

        val payload = pending.map { s ->
            SessionDto(
                uuid = s.uuid,
                projectId = s.project_id,
                uniqueIdValue = s.unique_id_value,
                entryTime = s.entry_time,
                exitTime = s.exit_time,
                status = s.status
            )
        }

        val response = try {
            api.uploadSessions(UploadSessionsRequest(payload))
        } catch (e: Exception) {
            throw TransientException("Sesiones: error de red (${e.message})", e)
        }

        return when {
            response.isSuccessful && response.body()?.success == true -> {
                workSessionDao.markSynced(pending.map { it.id })
                Pair(pending.size, null)
            }
            response.code() >= 500 ->
                throw TransientException("Sesiones: error servidor (HTTP ${response.code()})")
            else -> {
                Log.e(TAG, "uploadSessions rejected: HTTP ${response.code()}")
                Pair(0, "Sesiones no subidas (HTTP ${response.code()})")
            }
        }
    }
}
