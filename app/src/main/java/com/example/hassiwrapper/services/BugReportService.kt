package com.example.hassiwrapper.services

import android.os.Build
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.SmsBugReportDao
import com.example.hassiwrapper.data.db.entities.SmsBugReportEntity
import com.example.hassiwrapper.network.dto.CreateSmsBugReportRequest
import java.time.Instant
import java.util.UUID

/**
 * Creates local Bug Report rows (title/description from the user, everything else auto-filled)
 * and enqueues the metadata create on [OutboxService] — same attempts/FAILED/retry contract as
 * every other mutation, mirroring SmsIncidentService. The screenshot stays a separate best-effort
 * pass (SyncService.uploadSmsBugReportScreenshots), keyed on the server_id the outbox op sets.
 */
class BugReportService(
    private val bugReportDao: SmsBugReportDao,
    private val configRepo: ConfigRepository,
    private val outboxService: OutboxService
) {
    suspend fun createBugReport(
        title: String,
        description: String,
        logs: String?,
        screenshotPath: String?,
        screenName: String?
    ): SmsBugReportEntity {
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        val reporterName = configRepo.get("assigned_operator_name")?.takeIf { it.isNotBlank() }
        val terminalCode = configRepo.get("device_code")?.takeIf { it.isNotBlank() }
        val uuid = UUID.randomUUID().toString()
        val report = SmsBugReportEntity(
            uuid = uuid,
            project_id = projectId,
            title = title,
            description = description,
            logs = logs,
            screenshot_path = screenshotPath,
            reporter_name = reporterName,
            terminal_code = terminalCode,
            app_version = BuildConfig.BUILD_TAG,
            device_model = Build.MODEL,
            screen_name = screenName,
            created_at = Instant.now().toString(),
            synced = false
        )
        val id = bugReportDao.insert(report)
        outboxService.enqueue(
            entityType = OutboxService.Entity.BUG_REPORT,
            opType = OutboxService.Op.CREATE,
            localEntityId = id,
            projectId = projectId,
            payload = CreateSmsBugReportRequest(
                uuid = uuid,
                title = title,
                description = description,
                logs = logs,
                reporterName = reporterName,
                terminalCode = terminalCode,
                appVersion = BuildConfig.BUILD_TAG,
                deviceModel = Build.MODEL,
                screenName = screenName
            )
        )
        return report.copy(id = id)
    }
}
