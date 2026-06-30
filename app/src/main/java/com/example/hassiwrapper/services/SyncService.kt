package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.AtlasApp
import com.example.hassiwrapper.R
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import com.example.hassiwrapper.network.ApiClient
import com.example.hassiwrapper.network.AuthRepository
import com.example.hassiwrapper.network.AtlasApiService
import com.example.hassiwrapper.network.dto.*
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
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
    private val contractorDao: ContractorDao,
    private val heartbeatManager: HeartbeatManager? = null,
    private val vehicleDao: VehicleDao? = null,
    private val authRepo: AuthRepository? = null,
    private val smsSpoolDao: SmsSpoolDao? = null,
    private val smsPackingListDao: SmsPackingListDao? = null,
    private val smsPositionDao: SmsPositionDao? = null,
    private val smsVehicleDao: SmsVehicleDao? = null,
    private val smsVehicleLoadingDao: SmsVehicleLoadingDao? = null,
    private val smsTransferDao: SmsTransferDao? = null,
    private val smsIncidentDao: SmsIncidentDao? = null,
    private val outboxService: OutboxService? = null,
    private val smsSpoolLocationDao: SmsSpoolLocationDao? = null
) {
    companion object {
        private const val TAG = "SyncService"

        // Retry budget — keep trying for up to 90 seconds total
        private const val RETRY_BUDGET_MS  = 90_000L
        private const val RETRY_INITIAL_MS =  5_000L   // first wait: 5 s
        private const val RETRY_MAX_MS     = 30_000L   // cap per wait: 30 s
    }

    private val syncMutex = Mutex()
    private val smsUploadMutex = Mutex()

    /** Thrown for errors that are worth retrying (network issues, server 5xx). */
    private class TransientException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    data class SyncResult(
        val success: Boolean = false,
        val logsUploaded: Int = 0,
        val incidentsUploaded: Int = 0,
        val sessionsUploaded: Int = 0,
        val observationsUploaded: Int = 0,
        val workersAdded: Int = 0,
        val workersUpdated: Int = 0,
        val workersSkipped: Int = 0,
        val vehiclesAdded: Int = 0,
        val vehiclesUpdated: Int = 0,
        val photosUploaded: Int = 0,
        val photosFailed: Int = 0,
        val photoErrors: List<String> = emptyList(),
        val observationPhotosUploaded: Int = 0,
        val observationPhotosFailed: Int = 0,
        val error: String? = null
    )

    /** Structured info passed to the caller on each retry so the UI can show it. */
    data class RetryState(val attempt: Int, val waitSeconds: Int)

    /**
     * Runs a full sync cycle with automatic retry on transient failures.
     * [onRetry] is called (on the caller's coroutine dispatcher) each time a retry
     * is about to be scheduled, with the attempt number and wait in seconds.
     */
    suspend fun fullSync(onRetry: ((RetryState) -> Unit)? = null, onProgress: ((String) -> Unit)? = null): SyncResult {
        if (!syncMutex.tryLock()) {
            Log.i(TAG, "Sync already in progress, skipping concurrent call")
            return SyncResult(success = true)
        }
        try {
        return fullSyncLocked(onRetry, onProgress)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun fullSyncLocked(onRetry: ((RetryState) -> Unit)? = null, onProgress: ((String) -> Unit)? = null): SyncResult {
        val deadline = System.currentTimeMillis() + RETRY_BUDGET_MS
        var attempt = 0
        var waitMs = RETRY_INITIAL_MS

        // Reset cached URL so we re-probe primary vs fallback each sync,
        // preventing stale fallback URL when the device was offline at startup.
        apiClient.resetResolvedBase()

        while (true) {
            attempt++
            try {
                return doSync(onProgress = onProgress)
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

    data class SmsUploadResult(val success: Boolean = true, val error: String? = null)

    /**
     * Uploads everything the SMS module owns: send-flow packing lists, vehicle loadings,
     * transfers, SMS incidents, vehicle route state, and the CRUD outbox (spool/vehicle/PL
     * create/update/delete/assign). Does not touch the Access-Control sync engine
     * ([fullSync]) — no health check, master-data download, compliance, or AC log/session
     * upload. Call sites: MainActivity's auto-sync loop, the Sync screen, and after
     * send/receive transfer flows.
     */
    suspend fun syncSmsUploads(): SmsUploadResult {
        if (!smsUploadMutex.tryLock()) {
            Log.i(TAG, "SMS upload sync already in progress, skipping concurrent call")
            return SmsUploadResult()
        }
        try {
            return doSmsUploads()
        } finally {
            smsUploadMutex.unlock()
        }
    }

    private suspend fun doSmsUploads(didReLogin: Boolean = false): SmsUploadResult {
        val api = try {
            apiClient.getService()
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsUploads: could not obtain API service", e)
            return SmsUploadResult(success = false, error = e.message)
        }

        if (!didReLogin && authRepo != null && !authRepo.isAuthenticated()) {
            Log.i(TAG, "syncSmsUploads: token expired, attempting auto-re-login")
            if (authRepo.reLoginWithStoredCode(api)) {
                Log.i(TAG, "syncSmsUploads: auto-re-login succeeded")
            } else {
                return SmsUploadResult(success = false, error = AtlasApp.instance.getString(R.string.sync_error_session_expired))
            }
        }

        return try {
            uploadNewPackingLists(api)
            uploadVehicleLoadings(api)
            uploadTransfers(api)
            uploadVehicleRouteState(api)
            uploadSmsIncidents(api)
            uploadSpoolLocations(api)
            outboxService?.drain(api)?.let { r ->
                if (r.transient) Log.w(TAG, "syncSmsUploads: outbox drain stopped (transient), will retry next sync")
            }
            SmsUploadResult()
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsUploads failed", e)
            SmsUploadResult(success = false, error = e.message)
        }
    }

    /**
     * One full sync attempt. Throws [TransientException] for retryable failures.
     * Returns a final [SyncResult] for non-retryable outcomes (including partial success).
     */
    private suspend fun doSync(onProgress: ((String) -> Unit)? = null, didReLogin: Boolean = false): SyncResult {
        val api = apiClient.getService()

        // 0. Ensure token is still valid; auto-re-login if expired before hitting the API
        if (!didReLogin && authRepo != null && !authRepo.isAuthenticated()) {
            onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_session))
            Log.i(TAG, "Token expired, attempting auto-re-login before sync")
            if (authRepo.reLoginWithStoredCode(api)) {
                Log.i(TAG, "Auto-re-login succeeded after token expiry")
            } else {
                return SyncResult(error = AtlasApp.instance.getString(R.string.sync_error_session_expired))
            }
        }

        // 1. Health check — network errors and 5xx are transient
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_server))
        val healthResp = try {
            api.health()
        } catch (e: Exception) {
            throw TransientException(AtlasApp.instance.getString(R.string.sync_error_no_connection), e)
        }
        if (!healthResp.isSuccessful) {
            if (healthResp.code() >= 500) {
                throw TransientException(AtlasApp.instance.getString(R.string.sync_error_server_unavailable, healthResp.code()))
            }
            // 4xx — auth failure; try auto-re-login with stored device code (once)
            if (!didReLogin && healthResp.code() in 401..403 && authRepo != null) {
                Log.i(TAG, "Auth failed (HTTP ${healthResp.code()}), attempting auto-re-login")
                if (authRepo.reLoginWithStoredCode(api)) {
                    Log.i(TAG, "Auto-re-login succeeded, retrying sync")
                    return doSync(onProgress = onProgress, didReLogin = true)
                }
                Log.w(TAG, "Auto-re-login failed — no stored device code or credentials invalid")
            }
            return SyncResult(error = AtlasApp.instance.getString(R.string.sync_error_server_rejected, healthResp.code()))
        }

        // 2. Register device (non-fatal, no retry)
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_register))
        registerDevice(api)

        // 3. Download master data (projects/contractors/vehicles; best-effort, if it fails we still upload).
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_download))
        var vehicleResult = VehicleResult()
        try {
            val projectId = configRepo.getInt("selected_project_id") ?: 6
            val downloadResp = api.downloadSync(projectId)
            if (downloadResp.isSuccessful) {
                val data = downloadResp.body()
                if (data != null) {
                    vehicleResult = applyMasterData(data)
                    if (vehicleResult.added + vehicleResult.updated > 0) {
                        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_download_ok, vehicleResult.added, vehicleResult.updated))
                    }
                } else {
                    Log.w(TAG, "downloadSync returned null body")
                }
            } else {
                Log.w(TAG, "downloadSync failed: HTTP ${downloadResp.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadSync exception (non-fatal): ${e.message}")
        }

        // 4b. Upload entities still on their own queues (not the outbox).
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_pl))
        uploadNewPackingLists(api)

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_loadings))
        uploadVehicleLoadings(api)

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_transfers))
        uploadTransfers(api)

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_route))
        uploadVehicleRouteState(api)

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_incidents))
        uploadSmsIncidents(api)

        uploadSpoolLocations(api)

        // 4c. Drain the SMS mutation outbox in order (create/update/delete/assign).
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_outbox))
        outboxService?.drain(api)?.let { r ->
            if (r.transient) throw TransientException(AtlasApp.instance.getString(R.string.sync_error_outbox_retry))
        }

        configRepo.set("last_sync", Instant.now().toString())

        // 6. Heartbeat telemetry (best-effort, always at the end)
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_heartbeat))
        heartbeatManager?.sendHeartbeat()

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_done))

        return SyncResult(
            success = true,
            vehiclesAdded = vehicleResult.added,
            vehiclesUpdated = vehicleResult.updated
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

    private suspend fun applyMasterData(data: SyncDownloadResponse): VehicleResult {
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

        var vResult = VehicleResult()
        data.vehicles?.let { list ->
            val entities = list.map { v ->
                VehicleEntity(
                    asset_id = v.assetId ?: v.assetIdSnake ?: 0,
                    asset_uuid = v.assetUuid ?: v.assetUuidSnake ?: "",
                    project_id = v.projectId ?: v.projectIdSnake,
                    identifier = v.identifier ?: "",
                    asset_name = v.assetName ?: v.assetNameSnake ?: "",
                    vehicle_type_name = v.vehicleTypeName ?: v.vehicleTypeNameSnake ?: "",
                    contractor_id = v.contractorId ?: v.contractorIdSnake,
                    contractor_name = v.contractorName ?: v.contractorNameSnake ?: "",
                    license_plate = v.licensePlate ?: v.licensePlateSnake ?: "",
                    owner_register_sn = v.ownerRegisterSn ?: v.ownerRegisterSnSnake ?: "",
                    brand = v.brand ?: "",
                    model = v.model ?: "",
                    insurance_expiry = v.insuranceExpiry ?: v.insuranceExpirySnake,
                    inspection_expiry = v.inspectionExpiry ?: v.inspectionExpirySnake,
                    is_active = v.isActive ?: v.isActiveSnake ?: true,
                    badge_printed = v.badgePrinted ?: v.badgePrintedSnake ?: false,
                    is_compliant = v.isCompliant ?: true,
                    inactive_reason_code = v.inactiveReasonCode,
                    inactive_reason_detail = v.inactiveReasonDetail
                )
            }
            if (entities.isNotEmpty() && vehicleDao != null) {
                val existingUuids = vehicleDao.getAllUuids().toHashSet()
                val incomingUuids = entities.map { it.asset_uuid }.toHashSet()
                val added = entities.count { it.asset_uuid !in existingUuids }
                val updated = entities.count { it.asset_uuid in existingUuids }
                vehicleDao.insertAll(entities)

                // Soft-delete de vehículos no presentes en el servidor
                val obsolete = existingUuids - incomingUuids
                if (obsolete.isNotEmpty()) {
                    vehicleDao.deactivateByUuids(obsolete.toList())
                }
                vResult = VehicleResult(added, updated)
                Log.d(TAG, "Vehicles: $added added, $updated updated, ${obsolete.size} deactivated")
            }
        }

        configRepo.set("masterDataLastSync", Instant.now().toString())
        Log.d(TAG, "Master data applied")
        return vResult
    }

    data class VehicleResult(val added: Int = 0, val updated: Int = 0)

    // ── Upload helpers ────────────────────────────────────────────────────────
    //
    // Each returns Pair(countSynced, errorMsg):
    //   - Pair(n, null)     → success
    //   - Pair(0, msg)      → non-retryable failure (4xx); caller shows error
    //   - throws TransientException → retryable; caller's retry loop handles it

    // ── New packing list upload ───────────────────────────────────────────────

    private suspend fun uploadNewPackingLists(api: AtlasApiService) {
        val dao = smsPackingListDao ?: return
        // Only Send-flow PLs (positive ids). PLs from the standard CRUD screens use negative
        // temp ids and upload via the outbox drain — skip them here to avoid a duplicate server
        // PL (the packing-list create endpoint does not dedup).
        val unsynced = dao.getUnsynced().filter { it.packing_list_id > 0 }
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Uploading ${unsynced.size} new packing list(s)")
        val synced = mutableListOf<Long>()

        for (pl in unsynced) {
            val project = projectDao.getById(pl.project_id)
            val projectCode = project?.project_code
            if (projectCode.isNullOrBlank()) {
                Log.w(TAG, "No project code for PL ${pl.packing_list_id}, skipping")
                continue
            }
            try {
                val positionName = pl.position_id?.let { pid ->
                    smsPositionDao?.getAll()?.find { it.position_id == pid }?.name
                }
                val body = CreatePackingListRequest(
                    packingListName  = pl.packing_list_name,
                    vehicle          = pl.vehicle_plate,
                    vehicleId        = pl.vehicle_id,
                    position         = positionName,
                    positionId       = pl.position_id,
                    packingDate      = pl.packing_date.takeIf { it.isNotBlank() } ?: pl.created_at,
                    notes            = pl.notes,
                    createdBy        = pl.created_by ?: "API",
                    projectCode      = projectCode,
                    totalSpoolsCount = pl.total_spools_count ?: 0
                )
                val response = api.createPackingList(projectCode, body)
                if (response.isSuccessful) {
                    synced.add(pl.packing_list_id)
                    Log.i(TAG, "PL ${pl.packing_list_id} uploaded")
                } else {
                    Log.e(TAG, "PL ${pl.packing_list_id} upload failed: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "PL ${pl.packing_list_id} upload error: ${e.message}")
            }
        }

        if (synced.isNotEmpty()) dao.markSynced(synced)
    }

    // ── Vehicle loading upload ────────────────────────────────────────────────

    private suspend fun uploadVehicleLoadings(api: AtlasApiService) {
        val dao = smsVehicleLoadingDao ?: return
        val unsynced = dao.getUnsynced()
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Uploading ${unsynced.size} vehicle loading(s)")
        val synced = mutableListOf<Long>()
        val plIdsToMarkReady = mutableSetOf<Long>()

        val project = projectDao.getById(
            unsynced.first().project_id
        )
        val projectCode = project?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "uploadVehicleLoadings: no project code, skipping")
            return
        }

        for (loading in unsynced) {
            try {
                val loadingSpools = dao.getSpoolsByLoading(loading.loading_id)
                val body = VehicleLoadingUploadDto(
                    vehicleLoadingId = loading.loading_id,
                    vehicleId        = loading.vehicle_id,
                    vehiclePlate     = loading.vehicle_plate,
                    projectId        = loading.project_id,
                    createdAt        = loading.created_at,
                    createdBy        = null,
                    spools           = loadingSpools.map { s ->
                        VehicleLoadingSpoolUploadDto(
                            spoolId       = s.spool_id,
                            packingListId = s.packing_list_id
                        )
                    }
                )
                val response = api.uploadVehicleLoading(projectCode, body)
                if (response.isSuccessful) {
                    synced.add(loading.loading_id)
                    loadingSpools.mapNotNull { it.packing_list_id }.forEach { plIdsToMarkReady += it }
                    Log.i(TAG, "VehicleLoading ${loading.loading_id} uploaded")
                } else {
                    Log.e(TAG, "VehicleLoading ${loading.loading_id} upload failed: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "VehicleLoading ${loading.loading_id} upload error: ${e.message}")
            }
        }

        if (synced.isNotEmpty()) dao.markSynced(synced)
        plIdsToMarkReady.forEach { plId ->
            try { api.setPackingListReadyToSend(projectCode, plId, true) } catch (_: Exception) { }
        }
    }

    // ── Transfer upload ───────────────────────────────────────────────────────

    private suspend fun uploadTransfers(api: AtlasApiService) {
        val dao = smsTransferDao ?: return
        val unsynced = dao.getUnsynced()
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Uploading ${unsynced.size} transfer(s)")
        val synced = mutableListOf<Long>()

        val project = projectDao.getById(unsynced.first().project_id)
        val projectCode = project?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "uploadTransfers: no project code, skipping")
            return
        }

        for (transfer in unsynced) {
            try {
                val transferSpools = dao.getSpoolsByTransfer(transfer.transfer_id)
                val body = TransferUploadDto(
                    transferId      = transfer.transfer_id,
                    type            = transfer.transfer_type,
                    projectId       = transfer.project_id,
                    signatureBase64 = transfer.signature_data,
                    createdAt       = transfer.created_at,
                    createdBy       = null,
                    spools          = transferSpools.map { s ->
                        TransferSpoolUploadDto(
                            spoolId       = s.spool_id,
                            packingListId = null
                        )
                    }
                )
                val response = api.uploadTransfer(projectCode, body)
                if (response.isSuccessful) {
                    synced.add(transfer.transfer_id)
                    Log.i(TAG, "Transfer ${transfer.transfer_id} uploaded")
                } else {
                    Log.e(TAG, "Transfer ${transfer.transfer_id} upload failed: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transfer ${transfer.transfer_id} upload error: ${e.message}")
            }
        }

        if (synced.isNotEmpty()) dao.markSynced(synced)
    }

    // ── Per-spool position / sub-position upload ──────────────────────────────

    /** Pushes a spool's position + sub-position to the server via PUT status-flags.
     *  GET-merge-PUT: reads the current flags row first so the overwrite doesn't wipe
     *  hold/damaged/status/dates. Best-effort — returns false (and logs) on any failure,
     *  including no existing flags row (PUT 404) which the backend can't create via API.
     *  Called after a local RECEIVE or QR relocate; the local field is the source of truth. */
    suspend fun uploadSpoolStatusFlags(
        projectCode: String,
        spoolId: Long,
        positionId: Int?,
        subPositionId: Long?
    ): Boolean {
        return try {
            val api = apiClient.getService()
            val getResp = api.getSpoolStatusFlags(projectCode, spoolId.toString())
            if (!getResp.isSuccessful) {
                Log.w(TAG, "status-flags GET $spoolId HTTP ${getResp.code()} — skipping upload")
                return false
            }
            val json = getResp.body()?.string().orEmpty()
            if (json.isBlank()) {
                Log.w(TAG, "status-flags GET $spoolId empty body — skipping upload")
                return false
            }
            val o = JsonParser.parseString(json).asJsonObject
            fun optInt(k: String): Int? = o.get(k)?.takeIf { !it.isJsonNull }?.asInt
            fun optLong(k: String): Long? = o.get(k)?.takeIf { !it.isJsonNull }?.asLong
            fun optBool(k: String): Boolean = o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            fun optStr(k: String): String? = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            val body = SpoolStatusFlagsRequest(
                spoolId                   = spoolId,
                statusId                  = optInt("statusId"),
                incompleteStatusId        = optInt("incompleteStatusId"),
                positionId                = positionId ?: optInt("positionId"),
                subPositionId             = subPositionId,
                hold                      = optBool("hold"),
                damaged                   = optBool("damaged"),
                returnedToFactory         = optBool("returnedToFactory"),
                positionStatusDiscrepancy = optBool("positionStatusDiscrepancy"),
                reviewDiscrepancy         = optBool("reviewDiscrepancy"),
                lastEventDate             = optStr("lastEventDate"),
                pcaStatusDate             = optStr("pcaStatusDate"),
                pcaEntryDate              = optStr("pcaEntryDate")
            )
            val putResp = api.updateSpoolStatusFlags(projectCode, spoolId, body)
            if (putResp.isSuccessful) {
                Log.i(TAG, "status-flags PUT $spoolId ok (pos=$positionId sub=$subPositionId)")
                true
            } else {
                Log.w(TAG, "status-flags PUT $spoolId HTTP ${putResp.code()}: ${putResp.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "status-flags upload $spoolId error: ${e.message}")
            false
        }
    }

    // ── SMS incident upload ───────────────────────────────────────────────────

    private suspend fun uploadSmsIncidents(api: AtlasApiService) {
        val dao = smsIncidentDao ?: return
        val unsynced = dao.getUnsynced()

        if (unsynced.isNotEmpty()) {
            Log.i(TAG, "Uploading ${unsynced.size} SMS incident(s)")
            val synced = mutableListOf<Long>()

            for (inc in unsynced) {
                val project = projectDao.getById(inc.project_id)
                val projectCode = project?.project_code
                if (projectCode.isNullOrBlank()) {
                    Log.w(TAG, "No project code for SMS incident ${inc.id}, skipping")
                    continue
                }
                try {
                    val body = CreateSmsIncidentRequest(
                        uuid           = inc.uuid,
                        projectCode    = projectCode,
                        spoolCode      = inc.spool_code,
                        spoolSuffix    = inc.spool_suffix,
                        description    = inc.description,
                        vehiclePlate   = inc.vehicle_plate,
                        locationType   = inc.location_type,
                        locationDetail = inc.location_detail,
                        severity       = inc.severity,
                        positionId     = inc.position_id,
                        subPositionId  = inc.sub_position_id,
                        positionCode   = inc.position_code,
                        authorName     = inc.author_name,
                        eventDate      = inc.event_date,
                        status         = inc.status,
                        closedBy       = inc.closed_by,
                        closedAt       = inc.closed_at
                    )
                    val response = api.createSmsIncident(projectCode, body)
                    if (response.isSuccessful) {
                        synced.add(inc.id)
                        parseIncidentServerId(response)?.let { dao.setServerId(inc.id, it) }
                        Log.i(TAG, "SMS incident ${inc.id} uploaded")
                    } else {
                        Log.e(TAG, "SMS incident ${inc.id} upload failed: HTTP ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SMS incident ${inc.id} upload error: ${e.message}")
                }
            }

            if (synced.isNotEmpty()) dao.markSynced(synced)
        }

        uploadSmsIncidentPhotos(api, dao)
    }

    /**
     * Second pass, decoupled from [uploadSmsIncidents]: uploads the local damage photo for
     * any incident that has a server id but hasn't had its photo accepted yet. Kept separate
     * so a photo failure (or the endpoint not existing yet on this environment) never blocks
     * the metadata upload above, and is retried on its own next cycle.
     */
    private suspend fun uploadSmsIncidentPhotos(api: AtlasApiService, dao: SmsIncidentDao) {
        val pending = dao.getPendingPhotoUploads()
        if (pending.isEmpty()) return

        Log.i(TAG, "Uploading ${pending.size} SMS incident photo(s)")
        for (inc in pending) {
            val serverId = inc.server_id ?: continue
            val photoPath = inc.photo_path ?: continue
            val projectCode = projectDao.getById(inc.project_id)?.project_code
            if (projectCode.isNullOrBlank()) continue

            val file = File(photoPath)
            if (!file.exists()) {
                Log.w(TAG, "SMS incident ${inc.id} photo missing on disk ($photoPath) — giving up on it")
                dao.markPhotoSynced(inc.id)
                continue
            }
            try {
                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody("image/jpeg".toMediaType())
                )
                val response = api.uploadSmsIncidentPhoto(projectCode, serverId, part)
                if (response.isSuccessful) {
                    dao.markPhotoSynced(inc.id)
                    Log.i(TAG, "SMS incident ${inc.id} photo uploaded")
                } else {
                    Log.e(TAG, "SMS incident ${inc.id} photo upload failed: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SMS incident ${inc.id} photo upload error: ${e.message}")
            }
        }
    }

    /** Pulls the server-assigned incident id out of createSmsIncident's raw JSON response body. */
    private fun parseIncidentServerId(resp: Response<okhttp3.ResponseBody>): Long? = try {
        val el = JsonParser.parseString(resp.body()?.string().orEmpty())
        val obj = when {
            el.isJsonObject && el.asJsonObject.has("data") && !el.asJsonObject.get("data").isJsonNull ->
                el.asJsonObject.getAsJsonObject("data")
            el.isJsonObject -> el.asJsonObject
            else -> null
        }
        obj?.get("incidentId")?.takeIf { !it.isJsonNull }?.asLong
            ?: obj?.get("id")?.takeIf { !it.isJsonNull }?.asLong
    } catch (_: Exception) { null }

    // ── Vehicle route state upload ────────────────────────────────────────────

    private suspend fun uploadVehicleRouteState(api: AtlasApiService) {
        val dao = smsVehicleDao ?: return
        val unsynced = dao.getUnsyncedRouteState()
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Syncing ${unsynced.size} vehicle route state(s)")
        val synced = mutableListOf<Long>()

        val project = projectDao.getById(unsynced.first().project_id)
        val projectCode = project?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "uploadVehicleRouteState: no project code, skipping")
            return
        }

        for (vehicle in unsynced) {
            try {
                val response = if (vehicle.on_route) {
                    api.setVehicleOnRoute(projectCode, vehicle.vehicle_id, vehicle.destination)
                } else {
                    api.setVehicleOffRoute(projectCode, vehicle.vehicle_id)
                }
                if (response.isSuccessful) {
                    // Only mark route_synced=1 when going off-route: server doesn't persist on_route=true
                    // in GET /vehicles, so trusting server after on-route upload would flip on_route=false.
                    if (!vehicle.on_route) synced.add(vehicle.vehicle_id)
                    Log.i(TAG, "Vehicle ${vehicle.vehicle_id} route state synced (on_route=${vehicle.on_route})")
                } else {
                    Log.e(TAG, "Vehicle ${vehicle.vehicle_id} route state failed: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vehicle ${vehicle.vehicle_id} route state error: ${e.message}")
            }
        }

        if (synced.isNotEmpty()) dao.markRouteStateSynced(synced)
    }

    // ── Spool GPS location upload ─────────────────────────────────────────────

    private suspend fun uploadSpoolLocations(api: AtlasApiService) {
        val dao = smsSpoolLocationDao ?: return
        val unsynced = dao.getUnsynced()
        if (unsynced.isEmpty()) return

        Log.i(TAG, "Uploading ${unsynced.size} spool location(s)")
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        val projectCode = projectDao.getById(projectId)?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "uploadSpoolLocations: no project code for id=$projectId, skipping")
            return
        }

        val synced = mutableListOf<Long>()
        for (loc in unsynced) {
            try {
                val body = SpoolLocationRequest(
                    latitude     = loc.latitude,
                    longitude    = loc.longitude,
                    gpsAccuracyM = loc.gps_accuracy_m,
                    capturedAt   = loc.captured_at,
                    capturedBy   = loc.captured_by
                )
                val response = api.postSpoolLocation(projectCode, loc.spool_id, body)
                if (response.isSuccessful) {
                    val json = response.body()?.string().orEmpty()
                    val serverIdRaw = runCatching {
                        JsonParser.parseString(json).asJsonObject.get("locationId")?.asLong
                    }.getOrNull()
                    if (serverIdRaw != null && serverIdRaw != loc.location_id) {
                        dao.remapAndSync(loc.location_id, serverIdRaw)
                    } else {
                        synced.add(loc.location_id)
                    }
                    Log.i(TAG, "Spool location ${loc.location_id} → spool ${loc.spool_id} uploaded")
                } else {
                    Log.w(TAG, "Spool location ${loc.location_id} upload HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spool location ${loc.location_id} upload error: ${e.message}")
            }
        }
        if (synced.isNotEmpty()) dao.markSynced(synced)
    }

}
