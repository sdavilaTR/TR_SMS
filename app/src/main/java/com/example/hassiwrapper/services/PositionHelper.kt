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
     * valid location assigned, or the spool is already there and already carries the
     * terminal's sub-position. Mirrors GpsHelper.captureAndSaveSpoolLocation as the
     * single entry point for the plain "scan to check a spool" flows (QR Scanner
     * lookup, global hw scan).
     *
     * A terminal pinned to a sub-position (device_sub_position_id — e.g. JAFURAH "Laydown
     * GCP 5") stamps that sub-position onto the spool. Without this, a plain scan only ever
     * cleared sub_position_id, so a GCP 5 terminal's own spools never matched its own
     * sub-position filters: its guest KPI, Inventario and map all read back zero even though
     * the zone was full (see SpoolMapFragment / HomeFragment.loadGuestZoneStats). An explicit
     * relocate was the only thing that ever set the field.
     */
    suspend fun applyTerminalPosition(spoolId: Long): Unit = mutex.withLock {
        val location = ServiceLocator.configRepo.get("device_location")?.trim()?.uppercase()
        if (location == null || location !in VALID_DEVICE_LOCATIONS) return@withLock

        val positionId = ServiceLocator.smsPositionDao.getByCode(location)?.position_id ?: return@withLock
        val spool = ServiceLocator.smsSpoolDao.getById(spoolId) ?: return@withLock

        // Only honour the pin when it actually belongs to the position we're moving to — a stale
        // pin left over from a previous device_location would otherwise attach a sub-position to
        // the wrong parent. Falls back to clearing (null), the previous behaviour.
        val pinnedSubPositionId = ServiceLocator.configRepo.get("device_sub_position_id")?.toLongOrNull()
        val subPositionId = pinnedSubPositionId
            ?.let { ServiceLocator.smsSubPositionDao.getById(it) }
            ?.takeIf { it.position_id == positionId && it.is_active }
            ?.sub_position_id

        // Position unchanged AND sub-position already correct — nothing to write. The
        // sub-position half matters: a spool sitting at the right position with a null (or
        // sibling's) sub_position_id still needs the stamp.
        if (spool.position_id == positionId && spool.zone?.uppercase() == location &&
            spool.sub_position_id == subPositionId) return@withLock

        ServiceLocator.smsSpoolDao.setPositionClearingSubPosition(spoolId, positionId, location, subPositionId)
        Log.d(TAG, "spool $spoolId position -> $location (id=$positionId, subPosition=$subPositionId)")

        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
        if (!projectCode.isNullOrBlank()) {
            ServiceLocator.syncService.uploadSpoolStatusFlags(projectCode, spoolId, positionId, subPositionId)
        }
    }
}
