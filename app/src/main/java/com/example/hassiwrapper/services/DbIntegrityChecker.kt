package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.AtlasDatabase

/**
 * Lightweight `PRAGMA quick_check` run once per app start (caller must already be on a
 * background dispatcher — this does blocking I/O). Result is cached in config so
 * [HeartbeatManager] can report it every cycle without re-scanning the DB — a full
 * quick_check on a MERAM/JAFURAH-sized DB (100k+ spool rows) is not something to repeat
 * every ~60s heartbeat.
 */
object DbIntegrityChecker {
    private const val TAG = "DbIntegrityChecker"
    const val CONFIG_KEY_OK = "db_integrity_ok"
    const val CONFIG_KEY_CHECKED_AT = "db_integrity_checked_at"

    suspend fun checkAndRecord(database: AtlasDatabase, configRepo: ConfigRepository) {
        val ok = try {
            database.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use { c ->
                if (c.moveToFirst()) c.getString(0) == "ok" else false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Couldn't even run the check — leave any previous recorded result alone rather
            // than reporting "unknown" as if it were a fresh corruption signal.
            Log.w(TAG, "quick_check failed to run: ${e.message}")
            return
        }
        configRepo.set(CONFIG_KEY_OK, ok.toString())
        configRepo.set(CONFIG_KEY_CHECKED_AT, java.time.Instant.now().toString())
        if (ok) Log.d(TAG, "PRAGMA quick_check: ok")
        else Log.e(TAG, "PRAGMA quick_check: DB CORRUPTION DETECTED")
    }
}
