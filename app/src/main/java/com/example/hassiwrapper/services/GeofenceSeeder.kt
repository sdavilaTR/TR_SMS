package com.example.hassiwrapper.services

import android.content.Context
import android.util.Log
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSubPositionEntity

/**
 * Seeds `sms_sub_position.geofence_polygon`/`geofence_mode` from the KML files checked into
 * the repo's `KMLs/` directory, bundled as APK assets via `app/build.gradle`'s extra
 * `assets.srcDirs`. Ships geofences with the app build itself instead of requiring a manual
 * per-device import via Settings — editing/adding a KML and shipping a new build is enough
 * for every updated terminal to pick it up on its next `syncSmsData()` pass.
 *
 * Matches on position code ("WORKSHOP"/"LAYDOWN"/"SITE", already stable app-wide) plus a
 * normalized sub-position code/name — not on `full_path`, whose exact separator format isn't
 * guaranteed across environments. Seeded as [GeofenceHelper.MODE_GEOLOCATION] (real GPS
 * enforcement — user's explicit call 2026-07-28 after a FORCED-mode device test on all 7
 * zones confirmed the bundled polygons match sub_position_id 12-17 + 36 on DEV/JAFURAH).
 */
object GeofenceSeeder {
    private const val TAG = "GeofenceSeeder"
    private const val MODE = GeofenceHelper.MODE_GEOLOCATION

    private data class GcpSeed(val fileName: String, val positionCode: String, val subCode: String)

    private val GCP_SEEDS = listOf("GCP5", "GCP6", "GCP9").flatMap { code ->
        val n = code.removePrefix("GCP")
        listOf(
            GcpSeed("Laydown GCP $n.kml", "LAYDOWN", code),
            GcpSeed("Site GCP $n.kml", "SITE", code)
        )
    }
    private const val WORKSHOP_FILE = "Workshop.kml"
    private const val WORKSHOP_POSITION = "WORKSHOP"

    /** Call once per syncSmsData() pass, after sub-positions have been synced for [projectId]. */
    suspend fun seed(context: Context, projectId: Int) {
        for (s in GCP_SEEDS) {
            applySeed(context, projectId, s.positionCode, s.fileName) { subs ->
                subs.firstOrNull { normalize(it.code) == s.subCode || normalize(it.name) == s.subCode }
            }
        }
        // WORKSHOP has no real subzones — its one sub-position row exists only to carry the
        // combined geofence (same assumption SettingsFragment.offerSubPositionPin makes).
        applySeed(context, projectId, WORKSHOP_POSITION, WORKSHOP_FILE) { subs -> subs.singleOrNull() }
    }

    private fun normalize(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private suspend fun applySeed(
        context: Context,
        projectId: Int,
        positionCode: String,
        fileName: String,
        pick: (List<SmsSubPositionEntity>) -> SmsSubPositionEntity?
    ) {
        val positionId = ServiceLocator.smsPositionDao.getByCode(positionCode)?.position_id ?: return
        val subs = ServiceLocator.smsSubPositionDao.getByPosition(projectId, positionId)
        val target = pick(subs) ?: return

        val polygons = try {
            context.assets.open(fileName).use { KmlParser.parseAllPolygons(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled geofence asset '$fileName' missing or unparseable", e)
            return
        }
        if (polygons.isEmpty()) return
        val serialized = KmlParser.serializeMulti(polygons.map { it.points })

        if (target.geofence_polygon == serialized && target.geofence_mode == MODE) return
        ServiceLocator.smsSubPositionDao.setGeofence(target.sub_position_id, serialized, MODE)
        Log.i(TAG, "Seeded bundled geofence '$fileName' -> $positionCode/${target.code.ifBlank { target.name }} (${polygons.size} polygon(s))")
    }
}
