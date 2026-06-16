package com.example.hassiwrapper.network

import com.example.hassiwrapper.network.dto.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface mirroring all backend endpoints from api.js.
 */
interface AtlasApiService {

    // ── Health ────────────────────────────────────────────
    @GET("/health")
    suspend fun health(): Response<Void>

    // ── Auth ─────────────────────────────────────────────
    @POST("/v1/Auth/login")
    suspend fun login(@Body body: LoginRequest): Response<String>

    // ── Sync: Download ───────────────────────────────────
    @GET("/api/trac/sync/download")
    suspend fun downloadSync(@Query("projectId") projectId: Int? = null): Response<SyncDownloadResponse>

    // ── Sync: Upload Access Logs ─────────────────────────
    @POST("/api/trac/sync/upload")
    suspend fun uploadAccessLogs(@Body body: UploadLogsRequest): Response<UploadResponse>

    // ── Sync: Upload Incidents ───────────────────────────
    @POST("/api/trac/sync/upload-incidents")
    suspend fun uploadIncidents(@Body body: UploadIncidentsRequest): Response<UploadResponse>

    // ── Sync: Upload Work Sessions ───────────────────────
    @POST("/api/trac/sync/upload-sessions")
    suspend fun uploadSessions(@Body body: UploadSessionsRequest): Response<UploadResponse>

    // ── Sync: Upload HSE Observations ─────────────────────
    @POST("/api/trac/sync/upload-observations")
    suspend fun uploadObservations(@Body body: UploadObservationsRequest): Response<UploadResponse>

    // ── Sync: Upload HSE Observation Photo (multipart, one-by-one) ────────
    @Multipart
    @POST("/api/trac/sync/upload-observation-photos")
    suspend fun uploadObservationPhoto(
        @Part("observation_uuid") observationUuid: okhttp3.RequestBody,
        @Part("sort_order") sortOrder: okhttp3.RequestBody,
        @Part file: MultipartBody.Part
    ): Response<UploadResponse>

    // ── Sync: Register Device ────────────────────────────
    @POST("/api/trac/sync/register-device")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Void>

    // ── Photo Upload ────────────────────────────────────
    @Multipart
    @POST("/api/trac/projects/{projectId}/persons/{personId}/photo")
    suspend fun uploadWorkerPhoto(
        @Path("projectId") projectId: Int,
        @Path("personId") personId: String,
        @Part file: MultipartBody.Part
    ): Response<PhotoUploadResponse>

    // ── Training Compliance ────────────────────────────
    @GET("/api/atlas/projects/{projectId}/training/person/{badgeNumber}/compliance")
    suspend fun getTrainingCompliance(
        @Path("projectId") projectId: Int,
        @Path("badgeNumber") badgeNumber: String
    ): Response<List<TrainingComplianceDto>>

    // ── Document Compliance ────────────────────────────
    @GET("/api/atlas/projects/{projectId}/persons/{personUuid}/documents/compliance")
    suspend fun getDocumentCompliance(
        @Path("projectId") projectId: Int,
        @Path("personUuid") personUuid: String
    ): Response<List<DocumentComplianceDto>>

    // ── Bulk Compliance (sync) ──────────────────────────
    @GET("/api/trac/sync/compliance-bulk")
    suspend fun getBulkCompliance(@Query("projectId") projectId: Int): Response<BulkComplianceResponse>

    // ── Document Download ────────────────────────────
    @GET("/api/atlas/projects/{projectId}/persons/{personUuid}/documents/{documentId}/download")
    suspend fun downloadDocument(
        @Path("projectId") projectId: Int,
        @Path("personUuid") personUuid: String,
        @Path("documentId") documentId: Long
    ): Response<okhttp3.ResponseBody>

    // ── Heartbeat ───────────────────────────────────────
    @POST("/api/trac/sync/heartbeat")
    suspend fun sendHeartbeat(@Body payload: HeartbeatPayload): Response<Void>

    // ── SMS Spools ──────────────────────────────────────
    // GET /api/atlas/projects/{projectCode}/spools  →  raw JSON (parsed in fragment after logging)
    @GET("/api/atlas/projects/{projectCode}/spools")
    suspend fun getSpools(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    @POST("/api/atlas/projects/{projectCode}/spools")
    suspend fun createSpool(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: CreateSpoolRequest
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/spools")
    suspend fun updateSpool(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: com.example.hassiwrapper.network.dto.UpdateSpoolRequest
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/spools/{spoolId}")
    suspend fun deleteSpool(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: Long
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/spools/{spoolId}/hard")
    suspend fun hardDeleteSpool(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: Long
    ): Response<okhttp3.ResponseBody>

    // ── SMS Packing Lists ──────────────────────────────
    @GET("/api/atlas/projects/{projectCode}/packing-lists")
    suspend fun getPackingLists(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    @POST("/api/atlas/projects/{projectCode}/packing-lists")
    suspend fun createPackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: CreatePackingListRequest
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/packing-lists/{id}/spools")
    suspend fun getPackingListSpools(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("id") packingListId: String
    ): Response<okhttp3.ResponseBody>

    @POST("/api/atlas/projects/{projectCode}/packing-lists/{plId}/spools")
    suspend fun addSpoolToPackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("plId") packingListId: Long,
        @Body body: AssignSpoolRequest
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/packing-lists/{plId}/spools/{spoolId}")
    suspend fun removeSpoolFromPackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("plId") packingListId: Long,
        @retrofit2.http.Path("spoolId") spoolId: Long
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/packing-lists")
    suspend fun updatePackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: com.example.hassiwrapper.network.dto.UpdatePackingListRequest
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/packing-lists/{plId}")
    suspend fun deletePackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("plId") packingListId: Long
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/packing-lists/ready-to-send")
    suspend fun getPackingListsReadyToSend(
        @retrofit2.http.Path("projectCode") projectCode: String
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/packing-lists/{packingListId}/ready-to-send")
    suspend fun setPackingListReadyToSend(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("packingListId") packingListId: Long,
        @retrofit2.http.Query("value") value: Boolean = true
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/packing-lists/{plId}/hard")
    suspend fun hardDeletePackingList(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("plId") packingListId: Long
    ): Response<okhttp3.ResponseBody>

    // ── SMS Vehicles ───────────────────────────────────
    @GET("/api/atlas/projects/{projectCode}/vehicles")
    suspend fun getVehicles(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    @POST("/api/atlas/projects/{projectCode}/vehicles")
    suspend fun createVehicle(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: CreateVehicleRequest
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/vehicles")
    suspend fun updateVehicle(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: UpdateVehicleRequest
    ): Response<okhttp3.ResponseBody>

    @DELETE("/api/atlas/projects/{projectCode}/vehicles/{vehicleId}/hard")
    suspend fun hardDeleteVehicle(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("vehicleId") vehicleId: Long
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/vehicles/on-route")
    suspend fun getVehiclesOnRoute(
        @retrofit2.http.Path("projectCode") projectCode: String
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/vehicles/{vehicleId}/destination")
    suspend fun getVehicleDestination(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("vehicleId") vehicleId: Long
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/vehicles/{vehicleId}/on-route")
    suspend fun setVehicleOnRoute(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("vehicleId") vehicleId: Long,
        @retrofit2.http.Query("destinationId") destinationId: Int?
    ): Response<okhttp3.ResponseBody>

    @PUT("/api/atlas/projects/{projectCode}/vehicles/{vehicleId}/off-route")
    suspend fun setVehicleOffRoute(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("vehicleId") vehicleId: Long
    ): Response<okhttp3.ResponseBody>

    // ── SMS Areas ─────────────────────────────────────
    @GET("/api/atlas/projects/{projectCode}/areas")
    suspend fun getAreas(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    // ── SMS Specs ─────────────────────────────────────
    @GET("/api/atlas/projects/{projectCode}/specs")
    suspend fun getSpecs(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    // ── SMS Subcontractors ────────────────────────────
    @GET("/api/atlas/projects/{projectCode}/subcontractors")
    suspend fun getSubcontractors(@retrofit2.http.Path("projectCode") projectCode: String): Response<okhttp3.ResponseBody>

    // ── SMS Spool detail endpoints ────────────────────
    @POST("/api/atlas/projects/{projectCode}/spools/{spoolId}/property")
    suspend fun createSpoolProperty(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: Long,
        @Body body: CreateSpoolPropertyRequest
    ): Response<okhttp3.ResponseBody>

    @POST("/api/atlas/projects/{projectCode}/spools/{spoolId}/status-flags")
    suspend fun createSpoolStatusFlags(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: Long,
        @Body body: CreateSpoolStatusFlagsRequest
    ): Response<okhttp3.ResponseBody>

    // ── SMS Incidents ─────────────────────────────────
    // Upsert on body.uuid so best-effort retries don't duplicate.
    @POST("/api/atlas/projects/{projectCode}/incidents")
    suspend fun createSmsIncident(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: com.example.hassiwrapper.network.dto.CreateSmsIncidentRequest
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/spools/{spoolId}/events")
    suspend fun getSpoolEvents(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: String
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/spools/{spoolId}/property")
    suspend fun getSpoolProperty(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: String
    ): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/projects/{projectCode}/spools/{spoolId}/status-flags")
    suspend fun getSpoolStatusFlags(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @retrofit2.http.Path("spoolId") spoolId: String
    ): Response<okhttp3.ResponseBody>

    // ── SMS Global Lookups (project-agnostic) ─────────
    @GET("/api/atlas/sms/bore-sizes")
    suspend fun getBoreSizes(): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/sms/iso-types")
    suspend fun getIsoTypes(): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/sms/positions")
    suspend fun getPositions(): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/sms/spool-statuses")
    suspend fun getSpoolStatuses(): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/sms/units")
    suspend fun getUnits(): Response<okhttp3.ResponseBody>

    @GET("/api/atlas/sms/incomplete-statuses")
    suspend fun getIncompleteStatuses(): Response<okhttp3.ResponseBody>

    // ── SMS Vehicle Loadings ──────────────────────────────
    @POST("/api/atlas/projects/{projectCode}/vehicle-loadings")
    suspend fun uploadVehicleLoading(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: VehicleLoadingUploadDto
    ): Response<okhttp3.ResponseBody>

    // ── SMS Transfers ──────────────────────────────────────
    @POST("/api/atlas/projects/{projectCode}/transfers")
    suspend fun uploadTransfer(
        @retrofit2.http.Path("projectCode") projectCode: String,
        @Body body: TransferUploadDto
    ): Response<okhttp3.ResponseBody>

}
