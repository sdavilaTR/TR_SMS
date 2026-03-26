package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.*
import com.example.hassiwrapper.data.db.entities.*
import com.example.hassiwrapper.network.ApiClient
import com.example.hassiwrapper.network.dto.*
import java.time.Instant

/**
 * Full synchronisation service — port of sync.js.
 * Cycle: registerDevice → masterData → upload logs/incidents/sessions → download workers
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
    }

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

    suspend fun getPendingCounts(): PendingCounts {
        return PendingCounts(
            logs = accessLogDao.getPendingCount(),
            incidents = incidentDao.getPendingCount(),
            sessions = workSessionDao.getPendingCount()
        )
    }

    suspend fun fullSync(): SyncResult {
        val result = SyncResult()
        try {
            val api = apiClient.getService()

            // 1. Check connectivity
            val healthResp = api.health()
            if (!healthResp.isSuccessful && healthResp.code() >= 500) {
                return result.copy(error = "Sin conexión con el servidor")
            }

            // 2. Register device
            registerDevice(api)

            // 3. Download master data + workers
            val downloadResp = api.downloadSync()
            if (downloadResp.isSuccessful) {
                val data = downloadResp.body()
                if (data != null) {
                    applyMasterData(data)
                    val workerResult = applyWorkers(data)

                    // 4. Upload pending data
                    val logsUploaded = uploadAccessLogs(api)
                    val incidentsUploaded = uploadIncidents(api)
                    val sessionsUploaded = uploadSessions(api)

                    val now = Instant.now().toString()
                    configRepo.set("last_sync", now)

                    return SyncResult(
                        success = true,
                        logsUploaded = logsUploaded,
                        incidentsUploaded = incidentsUploaded,
                        sessionsUploaded = sessionsUploaded,
                        workersAdded = workerResult.added,
                        workersUpdated = workerResult.updated,
                        workersSkipped = workerResult.skipped
                    )
                }
            }

            // If download failed, still try to upload
            val logsUploaded = uploadAccessLogs(api)
            val incidentsUploaded = uploadIncidents(api)
            val sessionsUploaded = uploadSessions(api)

            return result.copy(
                success = true,
                logsUploaded = logsUploaded,
                incidentsUploaded = incidentsUploaded,
                sessionsUploaded = sessionsUploaded
            )
        } catch (e: Exception) {
            Log.e(TAG, "fullSync failed", e)
            return result.copy(error = e.message ?: "Sync fallido")
        }
    }

    private suspend fun registerDevice(api: com.example.hassiwrapper.network.AtlasApiService) {
        val deviceId = configRepo.get("device_id") ?: return
        if (deviceId == "unknown") return
        val deviceName = configRepo.get("device_name") ?: "Android Terminal"
        try {
            api.registerDevice(RegisterDeviceRequest(deviceId, deviceName))
        } catch (e: Exception) {
            Log.w(TAG, "registerDevice failed (non-fatal): ${e.message}")
        }
    }

    private suspend fun applyMasterData(data: SyncDownloadResponse) {
        // Projects
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
                // Set default project if not set
                if (configRepo.get("current_project_id") == null) {
                    configRepo.setInt("current_project_id", entities[0].project_id)
                }
            }
        }

        // Zones
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

        // Contractors
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

        // Crypto keys
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

    private suspend fun uploadAccessLogs(api: com.example.hassiwrapper.network.AtlasApiService): Int {
        val pending = accessLogDao.getPending()
        if (pending.isEmpty()) return 0

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

        val response = api.uploadAccessLogs(UploadLogsRequest(payload))
        if (response.isSuccessful && response.body()?.success == true) {
            accessLogDao.markSynced(pending.map { it.id })
        }
        return pending.size
    }

    private suspend fun uploadIncidents(api: com.example.hassiwrapper.network.AtlasApiService): Int {
        val pending = incidentDao.getPending()
        if (pending.isEmpty()) return 0

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

        val response = api.uploadIncidents(UploadIncidentsRequest(payload))
        if (response.isSuccessful && response.body()?.success == true) {
            incidentDao.markSynced(pending.map { it.id })
        }
        return pending.size
    }

    private suspend fun uploadSessions(api: com.example.hassiwrapper.network.AtlasApiService): Int {
        val pending = workSessionDao.getPendingClosed()
        if (pending.isEmpty()) return 0

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

        val response = api.uploadSessions(UploadSessionsRequest(payload))
        if (response.isSuccessful && response.body()?.success == true) {
            workSessionDao.markSynced(pending.map { it.id })
        }
        return pending.size
    }
}
