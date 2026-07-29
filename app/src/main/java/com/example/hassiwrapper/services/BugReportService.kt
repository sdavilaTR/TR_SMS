package com.example.hassiwrapper.services

import android.os.Build
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.SmsBugReportDao
import com.example.hassiwrapper.data.db.entities.SmsBugReportEntity
import java.time.Instant
import java.util.UUID

/**
 * Creates local Bug Report rows (title/description from the user, everything else auto-filled) —
 * a durable retry queue picked up by SyncService.uploadSmsBugReports, mirroring SmsIncidentService.
 */
class BugReportService(
    private val bugReportDao: SmsBugReportDao,
    private val configRepo: ConfigRepository
) {
    suspend fun createBugReport(
        title: String,
        description: String,
        logs: String?,
        screenshotPath: String?,
        screenName: String?
    ): SmsBugReportEntity {
        val projectId = configRepo.getInt("selected_project_id") ?: 6
        val report = SmsBugReportEntity(
            uuid = UUID.randomUUID().toString(),
            project_id = projectId,
            title = title,
            description = description,
            logs = logs,
            screenshot_path = screenshotPath,
            reporter_name = configRepo.get("assigned_operator_name")?.takeIf { it.isNotBlank() },
            terminal_code = configRepo.get("device_code")?.takeIf { it.isNotBlank() },
            app_version = BuildConfig.BUILD_TAG,
            device_model = Build.MODEL,
            screen_name = screenName,
            created_at = Instant.now().toString(),
            synced = false
        )
        val id = bugReportDao.insert(report)
        return report.copy(id = id)
    }
}
