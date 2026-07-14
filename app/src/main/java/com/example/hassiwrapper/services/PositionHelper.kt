package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.VALID_DEVICE_LOCATIONS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PositionHelper {

    private const val TAG = "PositionHelper"

    // Serializes the whole read-modify-write-and-upload sequence below. Honeywell
    // DataWedge is known to double-fire a scan broadcast; without this, two concurrent
    // calls for the same spool each GET-then-PUT status flags against stale state and
    // the second PUT can clobber fields the first one just set (lost update).
    private val mutex = Mutex()

    /**
     * Moves a scanned spool's position to this terminal's configured location
     * (Settings → device_location), best-effort. No-op when the terminal has no
     * valid location assigned, or the spool is already there. Clears sub_position_id
     * on a real move since sub-positions belong to a specific parent position.
     * Mirrors GpsHelper.captureAndSaveSpoolLocation as the single entry point for
     * the plain "scan to check a spool" flows (QR Scanner lookup, global hw scan).
     */
    suspend fun applyTerminalPosition(spoolId: Long): Unit = mutex.withLock {
        val location = ServiceLocator.configRepo.get("device_location")?.trim()?.uppercase()
        if (location == null || location !in VALID_DEVICE_LOCATIONS) return@withLock

        val positionId = ServiceLocator.smsPositionDao.getByCode(location)?.position_id ?: return@withLock
        val spool = ServiceLocator.smsSpoolDao.getById(spoolId) ?: return@withLock
        if (spool.position_id == positionId) return@withLock

        ServiceLocator.smsSpoolDao.setPositionClearingSubPosition(spoolId, positionId)
        Log.d(TAG, "spool $spoolId position -> $location (id=$positionId)")

        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
        if (!projectCode.isNullOrBlank()) {
            ServiceLocator.syncService.uploadSpoolStatusFlags(projectCode, spoolId, positionId, null)
        }
    }
}
