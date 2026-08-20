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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val smsSpoolLocationDao: SmsSpoolLocationDao? = null,
    private val smsPackingListSpoolDao: SmsPackingListSpoolDao? = null,
    private val auditLogService: AuditLogService? = null,
    private val smsBugReportDao: SmsBugReportDao? = null
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

    private val _isSyncing = MutableStateFlow(false)
    /** True while [fullSync] is in flight — drives the toolbar sync indicator. */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    // doSync (syncMutex) and doSmsUploads (smsUploadMutex) both call uploadSpoolLocations
    // and don't block each other, so this locks the upload itself to prevent both paths
    // reading the same unsynced rows before either has marked them synced.
    private val spoolLocationUploadMutex = Mutex()
    // doSync and doSmsUploads also both run the shared upload-queue block (uploadNewPackingLists
    // through outboxService.drain) — same overlap as above, one level up. tryLock+skip: if the
    // other path is mid-block, this cycle's upload phase is skipped and picked up next cycle,
    // rather than double-posting the same unsynced rows / outbox ops to the backend.
    private val smsQueueUploadMutex = Mutex()

    /** Thrown for errors that are worth retrying (network issues, server 5xx). */
    private class TransientException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    data class SyncResult(
        val success: Boolean = false,
        // True when the SMS upload phase was skipped this cycle because a concurrent
        // syncSmsUploads() call held smsQueueUploadMutex — distinct from `success` because the
        // three fullSync() callers need different reactions: MainActivity.runSyncCycle should
        // log-only instead of toasting "completed", while SyncFragment's manual sync must still
        // treat this as success (proceed to refresh SMS data, don't paint the red error card) —
        // the concurrent holder uploads the same rows, nothing was actually lost.
        val uploadPhaseSkipped: Boolean = false,
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
        _isSyncing.value = true
        try {
        return fullSyncLocked(onRetry, onProgress)
        } finally {
            _isSyncing.value = false
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-transient (auth error, bad request, etc.) — surface immediately
                Log.e(TAG, "Sync failed (non-retryable)", e)
                return SyncResult(error = e.message ?: "Sync fallido")
            }
        }
    }

    data class SmsUploadResult(val success: Boolean = true, val error: String? = null)

    /**
     * Background safety-net entry point for [com.example.hassiwrapper.workers.OutboxDrainWorker]:
     * drains only the CRUD outbox — no legacy upload routes, no master data download, no health
     * check — so a crash/OEM-kill/update-install between foreground sync cycles doesn't strand an
     * op that's sitting PENDING when the process dies. Guarded by the same [smsQueueUploadMutex]
     * as the foreground upload paths so it can never race a live sync; skips (returns null) rather
     * than blocking if one is already in flight, since the foreground cycle covers the same ground.
     */
    suspend fun drainOutboxOnly(): OutboxService.DrainResult? {
        val svc = outboxService ?: return null
        val api = try {
            apiClient.getService()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "drainOutboxOnly: could not obtain API service: ${e.message}")
            return null
        }
        // A background drain must never let an expired token masquerade as the server
        // permanently rejecting an op (4xx → FAILED) — re-login first, same as the
        // foreground paths, and bail out (not drain) if that fails.
        if (authRepo != null && !authRepo.isAuthenticated()) {
            if (!authRepo.reLoginWithStoredCode(api)) {
                Log.w(TAG, "drainOutboxOnly: token expired and re-login failed, skipping")
                return null
            }
        }
        if (!smsQueueUploadMutex.tryLock()) {
            Log.i(TAG, "drainOutboxOnly: upload pipeline busy, skipping")
            return null
        }
        return try {
            svc.drain(api)
        } finally {
            smsQueueUploadMutex.unlock()
        }
    }

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
            if (smsQueueUploadMutex.tryLock()) {
                try {
                    uploadNewPackingLists(api)
                    uploadPackingListSpoolLinks(api)
                    uploadVehicleLoadings(api)
                    uploadTransfers(api)
                    uploadVehicleRouteState(api)
                    uploadSmsIncidents(api)
                uploadSmsBugReports(api)
                    // Relocations BEFORE locations, and the order is load-bearing — see the same pair
                    // in doSync below.
                    uploadPendingRelocations(api)
                    uploadSpoolLocations(api)
                    outboxService?.drain(api)?.let { r ->
                        if (r.transient) Log.w(TAG, "syncSmsUploads: outbox drain stopped (transient), will retry next sync")
                    }
                } finally {
                    smsQueueUploadMutex.unlock()
                }
                SmsUploadResult()
            } else {
                // The concurrent holder (doSync) runs the identical upload block, so nothing is
                // lost — but this call's caller (e.g. SendPackingListFragment) is waiting on a
                // confirmed upload of THIS device's just-recorded rows, and the other holder may
                // not even include them yet. success=false here is what lets that caller show
                // "partial" instead of falsely telling the user the send confirmed server-side.
                Log.i(TAG, "syncSmsUploads: upload pipeline busy (concurrent fullSync), skipping this cycle")
                SmsUploadResult(success = false, error = "upload pipeline busy, will retry next cycle")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "downloadSync exception (non-fatal): ${e.message}")
        }

        // 4b/4c. Upload entities still on their own queues, then drain the SMS mutation outbox
        // in order (create/update/delete/assign). Guarded by smsQueueUploadMutex since
        // doSmsUploads runs this same block under a different lock — if it's mid-block, skip
        // the whole upload phase this cycle rather than double-posting the same rows/ops.
        var uploadPhaseSkipped = false
        if (smsQueueUploadMutex.tryLock()) {
            try {
                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_pl))
                uploadNewPackingLists(api)
                uploadPackingListSpoolLinks(api)

                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_loadings))
                uploadVehicleLoadings(api)

                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_transfers))
                uploadTransfers(api)

                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_route))
                uploadVehicleRouteState(api)

                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_upload_incidents))
                uploadSmsIncidents(api)
                uploadSmsBugReports(api)

                // Relocations BEFORE locations. The location POST is what stamps scanned_from, and
                // scanned_from is what makes a spool appear in a Material Tracking column — so it has
                // to be the LAST thing a scan publishes, once the yard behind it is already on the
                // server. The other way round (how this ran until 2026-08-20) published every zone in
                // one fast burst and then dripped the yards in over the next couple of minutes via
                // GET+PUT per spool: 20 spools scanned at Site GCP 9 all appeared under Site as
                // "Unassigned", and the chip counted down as the PUTs landed. Belt and braces now that
                // the yard also rides on the location POST itself: this is what keeps the ordering
                // sane for spools whose yard changed through a path the POST does not carry.
                uploadPendingRelocations(api)
                uploadSpoolLocations(api)

                onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_outbox))
                outboxService?.drain(api)?.let { r ->
                    if (r.transient) throw TransientException(AtlasApp.instance.getString(R.string.sync_error_outbox_retry))
                }
            } finally {
                smsQueueUploadMutex.unlock()
            }
        } else {
            // Concurrent syncSmsUploads() holds the block this cycle — nothing was lost (it runs
            // the same upload set), but THIS cycle didn't confirm it, so reporting success=true
            // here would be exactly the silent-loss shape this fix exists to close: last_sync
            // would advance and the caller would toast "completed" for a cycle that uploaded
            // nothing. Downgrade to a soft failure instead — the 60s auto-sync loop retries next
            // tick regardless, this is purely about not lying to the caller about this cycle.
            Log.i(TAG, "doSync: upload pipeline busy (concurrent syncSmsUploads), skipping upload phase this cycle")
            uploadPhaseSkipped = true
        }

        configRepo.set("last_sync", Instant.now().toString())

        // 6. Heartbeat telemetry (best-effort, always at the end)
        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_heartbeat))
        heartbeatManager?.sendHeartbeat()

        onProgress?.invoke(AtlasApp.instance.getString(R.string.sync_step_done))

        return SyncResult(
            success = true,
            uploadPhaseSkipped = uploadPhaseSkipped,
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        // Only genuinely-new Send-flow PLs: positive id (standard CRUD screens use negative temp
        // ids and upload via the outbox drain instead) AND no row_version. row_version is only
        // ever populated by a server response (create/update/download) — a PL that already has
        // one has been seen by the server before, so treating it as "new" here would re-POST an
        // existing PL under the create endpoint (which doesn't dedup) and 500 forever if some
        // other write path flipped its synced flag back to false without going through the outbox.
        val unsynced = dao.getUnsynced().filter { it.packing_list_id > 0 && it.row_version == null }
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
                    val serverId = parseCreatedPlId(response.body()?.string().orEmpty())
                    if (serverId != null && serverId > 0L && serverId != pl.packing_list_id) {
                        // Server assigned its own id — remap every local table that stored
                        // the local id as a foreign key before this upload landed, mirroring
                        // OutboxService's remap-on-CREATE pattern for spool/vehicle.
                        dao.remapAndSync(pl.packing_list_id, serverId)
                        smsSpoolDao?.remapPackingListId(pl.packing_list_id, serverId)
                        smsPackingListSpoolDao?.remapPackingListId(pl.packing_list_id, serverId)
                        smsVehicleLoadingDao?.remapPackingListId(pl.packing_list_id, serverId)
                        smsTransferDao?.remapPackingListId(pl.packing_list_id, serverId)
                        Log.i(TAG, "PL ${pl.packing_list_id} uploaded, remapped to server id $serverId")
                    } else {
                        synced.add(pl.packing_list_id)
                        Log.i(TAG, "PL ${pl.packing_list_id} uploaded")
                    }
                } else if (response.code() == 409) {
                    // Vehicle-conflict guard: another device's PL for this vehicle synced first while
                    // this one was offline. It can never sync now — drop it instead of retrying (and
                    // 409-ing) every cycle forever. This PL may already have been sent locally (spools
                    // marked in_transit, vehicle marked on_route, SEND transfer recorded) before this
                    // upload attempt ran, so unwind all of that too — otherwise the vehicle is stuck
                    // "on route" forever and the transfer is left pointing at a PL that no longer exists.
                    val msg = com.example.hassiwrapper.network.dto.parsePackingListConflictMessage(409, response.errorBody()?.string())
                    Log.e(TAG, "PL ${pl.packing_list_id} vehicle conflict, dropping local copy: $msg")
                    if (smsSpoolDao != null && smsPackingListSpoolDao != null && smsTransferDao != null && smsVehicleDao != null) {
                        releaseDanglingSendForPackingList(pl.packing_list_id, pl.vehicle_id, smsSpoolDao, smsPackingListSpoolDao, smsTransferDao, smsVehicleDao, outboxService, pl.project_id)
                    } else {
                        smsSpoolDao?.getByPackingList(pl.packing_list_id)?.forEach {
                            smsSpoolDao.updatePackingList(it.spool_id, null)
                        }
                        smsPackingListSpoolDao?.deleteByPackingList(pl.packing_list_id)
                    }
                    dao.deleteById(pl.packing_list_id)
                    auditLogService?.log(
                        AuditLogService.PL_ELIMINADO,
                        AuditLogService.ENTITY_PL,
                        pl.packing_list_id, pl.packing_list_name,
                        detail = msg, projectId = pl.project_id
                    )
                } else {
                    Log.e(TAG, "PL ${pl.packing_list_id} upload failed: HTTP ${response.code()}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "PL ${pl.packing_list_id} upload error: ${e.message}")
            }
        }

        if (synced.isNotEmpty()) dao.markSynced(synced)
    }

    // ── Packing-list manifest upload (spool ↔ PL links) ───────────────────────
    //
    // uploadNewPackingLists above creates the PL *header* only — it never tells the server which
    // spools it carries. Before this pass existed the only producer of that link was a bare
    // fire-and-forget `addSpoolToPackingList` inside SendPackingListFragment, gated on the PL
    // having been created during that very send and swallowing every exception, so the manifest
    // was effectively terminal-local: no other terminal and no ATLAS Web screen ever saw a load.
    //
    // Retries are free: the endpoint is idempotent server-side (IF NOT EXISTS … ELSE UPDATE
    // sequence_number), so re-posting a link the server already holds is a no-op. That is also
    // what makes the v46→v47 backfill (every pre-existing row defaults to synced=0) safe.
    private suspend fun uploadPackingListSpoolLinks(api: AtlasApiService) {
        val dao = smsPackingListSpoolDao ?: return
        val plDao = smsPackingListDao ?: return
        val pending = dao.getUnsynced()
        if (pending.isEmpty()) return

        Log.i(TAG, "Uploading ${pending.size} packing-list spool link(s)")
        val done = mutableListOf<Long>()
        for (link in pending) {
            val pl = plDao.getById(link.packing_list_id) ?: continue
            val projectCode = projectDao.getById(pl.project_id)?.project_code
            if (projectCode.isNullOrBlank()) continue
            try {
                val resp = api.addSpoolToPackingList(
                    projectCode,
                    link.packing_list_id,
                    AssignSpoolRequest(link.spool_id, link.added_by ?: "API", link.sequence_number)
                )
                when {
                    resp.isSuccessful -> done.add(link.packing_list_spool_id)
                    // 404 = the PL is gone or inactive server-side. The link can never land; keep
                    // retrying it every cycle forever and it would drown out the real work. The PL
                    // row itself is cleaned up by syncSmsData's removedPLIds handling.
                    resp.code() == 404 -> {
                        Log.w(TAG, "PL link ${link.packing_list_id}/${link.spool_id}: PL not found server-side, dropping")
                        done.add(link.packing_list_spool_id)
                    }
                    else -> Log.e(TAG, "PL link ${link.packing_list_id}/${link.spool_id} failed: HTTP ${resp.code()}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline / 5xx: leave synced=0 so the next cycle retries.
                Log.w(TAG, "PL link ${link.packing_list_id}/${link.spool_id} error: ${e.message}")
            }
        }
        if (done.isNotEmpty()) dao.markSynced(done)
    }

    private fun parseCreatedPlId(raw: String): Long? {
        return try {
            val el = JsonParser.parseString(raw)
            val obj = when {
                el.isJsonObject && el.asJsonObject.has("data") && !el.asJsonObject.get("data").isJsonNull ->
                    el.asJsonObject.getAsJsonObject("data")
                el.isJsonObject -> el.asJsonObject
                else -> return null
            }
            obj.get("packingListId")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("packing_list_id")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        } catch (_: Exception) { null }
    }

    // ── Vehicle loading upload ────────────────────────────────────────────────
    //
    // Normal path is now the outbox (OutboxService.vehicleLoadingCreate), enqueued right after
    // the local insert in SendPackingListFragment... except it isn't: Send-flow still writes
    // these rows the same way it always has (synced=false, no enqueue call — see plan Tier 1.5
    // progress notes on why that fragment is deliberately untouched). So this is the *only*
    // producer of VEHICLE_LOADING outbox ops today, not a backfill for a separate eager path —
    // same shape as bug-report/route-state's backfill, just permanently load-bearing here rather
    // than transitional. hasUnfinishedOp guards against re-enqueuing a row whose op is already
    // draining or gave up.

    private suspend fun uploadVehicleLoadings(api: AtlasApiService) {
        val dao = smsVehicleLoadingDao ?: return
        val outbox = outboxService ?: return
        for (loading in dao.getUnsynced()) {
            if (outbox.hasUnfinishedOp(OutboxService.Entity.VEHICLE_LOADING, OutboxService.Op.CREATE, loading.loading_id)) continue
            outbox.enqueue(
                entityType = OutboxService.Entity.VEHICLE_LOADING,
                opType = OutboxService.Op.CREATE,
                localEntityId = loading.loading_id,
                projectId = loading.project_id
            )
            Log.i(TAG, "Enqueued outbox CREATE op for vehicle loading ${loading.loading_id}")
        }
    }

    // ── Transfer upload ───────────────────────────────────────────────────────
    //
    // Same shape as uploadVehicleLoadings above — see its comment.

    private suspend fun uploadTransfers(api: AtlasApiService) {
        val dao = smsTransferDao ?: return
        val outbox = outboxService ?: return
        for (transfer in dao.getUnsynced()) {
            if (outbox.hasUnfinishedOp(OutboxService.Entity.TRANSFER, OutboxService.Op.CREATE, transfer.transfer_id)) continue
            outbox.enqueue(
                entityType = OutboxService.Entity.TRANSFER,
                opType = OutboxService.Op.CREATE,
                localEntityId = transfer.transfer_id,
                projectId = transfer.project_id
            )
            Log.i(TAG, "Enqueued outbox CREATE op for transfer ${transfer.transfer_id}")
        }
    }

    // ── Per-spool position / sub-position upload ──────────────────────────────

    /** Pushes a spool's position + sub-position to the server via PUT status-flags.
     *  GET-merge-PUT: reads the current flags row first so the overwrite doesn't wipe
     *  hold/damaged/status/dates. If the GET 404s (spool has no flags row yet — common
     *  for ETL-created spools that never got one), PUTs a defaults body instead: the
     *  backend PUT is a MERGE upsert and is the only API path that can create the row.
     *  Any other GET failure still aborts, so a transient error can't overwrite real
     *  server flags with defaults. Best-effort — returns false (and logs) on failure.
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
            val body: SpoolStatusFlagsRequest
            if (getResp.code() == 404) {
                Log.i(TAG, "status-flags GET $spoolId 404 — no server row, creating via PUT upsert")
                body = SpoolStatusFlagsRequest(
                    spoolId       = spoolId,
                    positionId    = positionId,
                    subPositionId = subPositionId
                )
            } else if (!getResp.isSuccessful) {
                Log.w(TAG, "status-flags GET $spoolId HTTP ${getResp.code()} — skipping upload")
                return false
            } else {
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
                body = SpoolStatusFlagsRequest(
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
            }
            val putResp = api.updateSpoolStatusFlags(projectCode, spoolId, body)
            if (putResp.isSuccessful) {
                Log.i(TAG, "status-flags PUT $spoolId ok (pos=$positionId sub=$subPositionId)")
                true
            } else {
                Log.w(TAG, "status-flags PUT $spoolId HTTP ${putResp.code()}: ${putResp.errorBody()?.string()}")
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    // ── SMS bug report upload ─────────────────────────────────────────────────

    /**
     * Metadata create now goes through [OutboxService] (enqueued by BugReportService) — this is
     * just the screenshot pass, a separate decoupled step keyed on server_id so a screenshot
     * failure never blocks the report itself from reaching the backend. Rows that are fully
     * synced (metadata + screenshot, or no screenshot to send) are deleted locally afterward —
     * send-and-forget, nothing left to show the user once it's landed.
     */
    private suspend fun uploadSmsBugReports(api: AtlasApiService) {
        val dao = smsBugReportDao ?: return

        backfillBugReportOutboxOps(dao)
        uploadSmsBugReportScreenshots(api, dao)

        for (report in dao.getFullySynced()) {
            report.screenshot_path?.let { runCatching { File(it).delete() } }
            dao.deleteById(report.id)
        }
    }

    /**
     * One-shot migration backfill: rows left over from before bug-report metadata create moved
     * into the outbox (this app version's predecessor inserted straight into sms_bug_report with
     * no outbox op at all) would otherwise sit forever — nothing reads getUnsyncedMetadata()
     * anymore. hasUnfinishedOp guards against re-enqueuing the same row while its op is still
     * queued or already gave up FAILED; server create-only-upserts on uuid, so even a duplicate
     * enqueue in the rare gap (op DONE but response body unparseable) is harmless.
     */
    private suspend fun backfillBugReportOutboxOps(dao: SmsBugReportDao) {
        val outbox = outboxService ?: return
        for (report in dao.getUnsyncedMetadata()) {
            if (outbox.hasUnfinishedOp(OutboxService.Entity.BUG_REPORT, OutboxService.Op.CREATE, report.id)) continue
            outbox.enqueue(
                entityType = OutboxService.Entity.BUG_REPORT,
                opType = OutboxService.Op.CREATE,
                localEntityId = report.id,
                projectId = report.project_id,
                payload = CreateSmsBugReportRequest(
                    uuid = report.uuid,
                    title = report.title,
                    description = report.description,
                    logs = report.logs,
                    reporterName = report.reporter_name,
                    terminalCode = report.terminal_code,
                    appVersion = report.app_version,
                    deviceModel = report.device_model,
                    screenName = report.screen_name
                )
            )
            Log.i(TAG, "Backfilled outbox CREATE op for pre-migration bug report ${report.id}")
        }
    }

    private suspend fun uploadSmsBugReportScreenshots(api: AtlasApiService, dao: SmsBugReportDao) {
        val pending = dao.getUnsyncedScreenshots()
        if (pending.isEmpty()) return

        Log.i(TAG, "Uploading ${pending.size} bug report screenshot(s)")
        for (report in pending) {
            val serverId = report.server_id ?: continue
            val screenshotPath = report.screenshot_path ?: continue
            val projectCode = projectDao.getById(report.project_id)?.project_code
            if (projectCode.isNullOrBlank()) continue

            val file = File(screenshotPath)
            if (!file.exists()) {
                Log.w(TAG, "Bug report ${report.id} screenshot missing on disk ($screenshotPath) — giving up on it")
                dao.markScreenshotSynced(report.id)
                continue
            }
            try {
                val part = MultipartBody.Part.createFormData(
                    "file", file.name, file.asRequestBody("image/jpeg".toMediaType())
                )
                val response = api.uploadSmsBugReportScreenshot(projectCode, serverId, part)
                if (response.isSuccessful) {
                    dao.markScreenshotSynced(report.id)
                    Log.i(TAG, "Bug report ${report.id} screenshot uploaded")
                } else {
                    Log.e(TAG, "Bug report ${report.id} screenshot upload failed: HTTP ${response.code()}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bug report ${report.id} screenshot upload error: ${e.message}")
            }
        }
    }

    // ── Vehicle route state upload ────────────────────────────────────────────
    //
    // The push (setOnRoute/setOffRoute call sites enqueue their own ROUTE_STATE op — see
    // SendPackingListFragment, ReceivePackingListFragment, PackingListCleanup) now does the
    // normal-path upload via OutboxService.drain(). This is just the one-shot backfill for rows
    // left `route_synced = 0` from before that migration (this app version's predecessor set
    // the flag with no outbox op behind it) — without it those rows would never sync again,
    // since nothing still polls getUnsyncedRouteState() on its own. Mirrors
    // backfillBugReportOutboxOps.

    private suspend fun uploadVehicleRouteState(api: AtlasApiService) {
        val dao = smsVehicleDao ?: return
        val outbox = outboxService ?: return
        val unsynced = dao.getUnsyncedRouteState()
        if (unsynced.isEmpty()) return

        for (vehicle in unsynced) {
            if (outbox.hasUnfinishedOp(OutboxService.Entity.ROUTE_STATE, OutboxService.Op.UPDATE, vehicle.vehicle_id)) continue
            outbox.enqueue(
                entityType = OutboxService.Entity.ROUTE_STATE,
                opType = OutboxService.Op.UPDATE,
                localEntityId = vehicle.vehicle_id,
                projectId = vehicle.project_id,
                payload = RouteStateUpdatePayload(onRoute = vehicle.on_route, destinationId = vehicle.destination)
            )
            Log.i(TAG, "Backfilled outbox ROUTE_STATE op for pre-migration vehicle ${vehicle.vehicle_id}")
        }
    }

    // ── Pending offline relocation retry ─────────────────────────────────────
    //
    // Spools relocated offline (QR scan / RECEIVE) have synced=false but the status-flags PUT
    // is never retried automatically — it only fires inline during the relocation.  This step
    // retries it on the next sync cycle so the server gets the correct position/sub-position.

    private suspend fun uploadPendingRelocations(api: AtlasApiService) {
        val dao = smsSpoolDao ?: return
        val pending = dao.getUnsyncedRelocated()
        if (pending.isEmpty()) return

        Log.i(TAG, "uploadPendingRelocations: ${pending.size} pending spool relocation(s)")
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        val projectCode = projectDao.getById(projectId)?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "uploadPendingRelocations: no project code for id=$projectId, skipping")
            return
        }

        val synced = mutableListOf<Long>()
        for (spool in pending) {
            val ok = uploadSpoolStatusFlags(projectCode, spool.spool_id, spool.position_id, spool.sub_position_id)
            if (ok) synced.add(spool.spool_id)
        }
        if (synced.isNotEmpty()) dao.markSynced(synced)
    }

    // ── Spool GPS location upload ─────────────────────────────────────────────

    private suspend fun uploadSpoolLocations(api: AtlasApiService) {
        if (!spoolLocationUploadMutex.tryLock()) return
        try {
            val dao = smsSpoolLocationDao ?: return
            val projectId = configRepo.getInt("selected_project_id") ?: 6

            // Rows whose spool was deleted locally can never resolve a project, so the scoped
            // query below would leave them pending forever. Drop them before it runs.
            val orphans = dao.deleteUnsyncedOrphans()
            if (orphans > 0) Log.w(TAG, "uploadSpoolLocations: purged $orphans pending fix(es) with no local spool")

            // Scoped to the selected project: postSpoolLocation resolves the spool *within*
            // projectCode, so a row from another project (project switched without a DB reset)
            // would 404 on every cycle and retry forever. It stays pending here and uploads
            // normally once that project is selected again.
            val unsynced = dao.getUnsyncedByProject(projectId)
            if (unsynced.isEmpty()) return

            Log.i(TAG, "Uploading ${unsynced.size} spool location(s)")
            val projectCode = projectDao.getById(projectId)?.project_code
            if (projectCode.isNullOrBlank()) {
                Log.w(TAG, "uploadSpoolLocations: no project code for id=$projectId, skipping")
                return
            }

            val synced = mutableListOf<Long>()
            for (loc in unsynced) {
                try {
                    // The yard travels with the fix. This POST is what stamps scanned_from, i.e. the
                    // Material Tracking column, and sending the zone on its own is what made a whole
                    // scanned batch land in that column as "Unassigned" and only earn its GCP chip as
                    // the per-spool status-flags PUTs trickled in afterwards. The local spool row is
                    // the source of truth here — same value uploadPendingRelocations would push, and
                    // it honours a RECEIVE that unloaded into a yard other than the terminal's pin.
                    val body = SpoolLocationRequest(
                        latitude      = loc.latitude,
                        longitude     = loc.longitude,
                        gpsAccuracyM  = loc.gps_accuracy_m,
                        capturedAt    = loc.captured_at,
                        capturedBy    = loc.captured_by,
                        scannedBy     = configRepo.get("device_code")?.takeIf { it.isNotBlank() },
                        scannedFrom   = configRepo.get("device_location")?.takeIf { it.isNotBlank() },
                        subPositionId = smsSpoolDao?.getById(loc.spool_id)?.sub_position_id
                    )
                    val response = api.postSpoolLocation(projectCode, loc.spool_id, body)
                    when {
                        response.isSuccessful -> {
                            // location_id is a local-only autoincrement key (nothing else references it
                            // as a FK); just mark synced rather than remapping it to the server-assigned
                            // id, which risked colliding with an existing local PK.
                            synced.add(loc.location_id)
                            Log.i(TAG, "Spool location ${loc.location_id} → spool ${loc.spool_id} uploaded")
                        }
                        // Only codes that mean "this row will never be accepted" are dropped:
                        // 400 malformed body, 404 spool unknown in this project, 422 rejected
                        // payload. Marking synced here is destructive (no FAILED state to fall
                        // back on, unlike OutboxService.markFailed), so the rest of 4xx must NOT
                        // land here — 401 (session expired mid-drain, recovered by
                        // reLoginWithStoredCode), 403 (Azure IP-allowlist miss after the device's
                        // public IP rotates — routine on DEV), 408/429 are all retryable, and
                        // dropping them would silently destroy every queued fix.
                        response.code() in setOf(400, 404, 422) -> {
                            synced.add(loc.location_id)
                            Log.e(TAG, "Spool location ${loc.location_id} → spool ${loc.spool_id} rejected: HTTP ${response.code()}, dropping")
                        }
                        else -> Log.w(TAG, "Spool location ${loc.location_id} upload HTTP ${response.code()}, will retry")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Spool location ${loc.location_id} upload error: ${e.message}")
                }
            }
            if (synced.isNotEmpty()) dao.markSynced(synced)
        } finally {
            spoolLocationUploadMutex.unlock()
        }
    }

}
