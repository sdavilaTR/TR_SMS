package com.example.hassiwrapper.network

import com.example.hassiwrapper.network.dto.*
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
    suspend fun downloadSync(): Response<SyncDownloadResponse>

    // ── Sync: Upload Access Logs ─────────────────────────
    @POST("/api/trac/sync/upload")
    suspend fun uploadAccessLogs(@Body body: UploadLogsRequest): Response<UploadResponse>

    // ── Sync: Upload Incidents ───────────────────────────
    @POST("/api/trac/sync/upload-incidents")
    suspend fun uploadIncidents(@Body body: UploadIncidentsRequest): Response<UploadResponse>

    // ── Sync: Upload Work Sessions ───────────────────────
    @POST("/api/trac/sync/upload-sessions")
    suspend fun uploadSessions(@Body body: UploadSessionsRequest): Response<UploadResponse>

    // ── Sync: Register Device ────────────────────────────
    @POST("/api/trac/sync/register-device")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Void>
}
