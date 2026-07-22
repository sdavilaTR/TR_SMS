package com.example.hassiwrapper.services

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolLocationEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object GpsHelper {

    private const val TAG = "GpsHelper"
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_FALLBACK_AGE_MS = 5 * 60_000L

    private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    /** ISO-8601 timestamp with a fixed-width millisecond fraction, so lexicographic string
     * ordering (used by Room's ORDER BY captured_at) always matches chronological order —
     * unlike Instant.toString(), which omits the fraction when nanos == 0. */
    fun capturedAtNow(): String = ISO_MILLIS.format(Instant.now())

    /**
     * Returns (latitude, longitude, accuracy_m) or null if location unavailable.
     * Tries FusedLocationProvider first; falls back to LocationManager (like HeartbeatManager).
     */
    suspend fun getCurrentLocation(context: Context): Triple<Double, Double, Float?>? {
        val hasFine = ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "No location permission")
            return null
        }

        val fused = withTimeoutOrNull(TIMEOUT_MS) { fusedLocation(context, hasFine) }
        return fused ?: fallbackLocation(context, hasFine)
    }

    /**
     * Captures a best-effort GPS fix and persists it as this spool's current location
     * (silently a no-op if location is unavailable/denied). Single entry point for every
     * spool-scan flow across the app (QR Scanner, global scanner-button scan, packing-list
     * add/detail, new incident, send/receive batch) so a new scan flow can't forget it.
     *
     * Also checks the fix against any GEOLOCATION-mode area geofence for the selected project
     * (see [GeofenceHelper]) and returns the result — current call sites ignore it (detection-only
     * for now, no scan is blocked), but it's available for a future warning/incident UI.
     */
    suspend fun captureAndSaveSpoolLocation(context: Context, spoolId: Long): GeofenceHelper.CheckResult? {
        val gps = getCurrentLocation(context) ?: return null
        val (lat, lon, acc) = gps
        val loc = SmsSpoolLocationEntity(
            spool_id       = spoolId,
            latitude       = lat,
            longitude      = lon,
            gps_accuracy_m = acc,
            captured_at    = capturedAtNow(),
            captured_by    = ServiceLocator.configRepo.get("device_name")
        )
        ServiceLocator.smsSpoolLocationDao.insert(loc)
        ServiceLocator.smsSpoolLocationDao.pruneOldest(spoolId)
        Log.d(TAG, "GPS saved for spool $spoolId: lat=$lat lon=$lon acc=$acc")

        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val geofenceResult = GeofenceHelper.checkProjectGeofences(projectId, lat, lon)
        if (geofenceResult is GeofenceHelper.CheckResult.Outside) {
            Log.w(TAG, "Spool $spoolId scanned outside geofenced area(s): ${geofenceResult.areaNames.joinToString()}")
        }
        return geofenceResult
    }

    private suspend fun fusedLocation(context: Context, hasFine: Boolean): Triple<Double, Double, Float?>? {
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val location = suspendCancellableCoroutine<Location?> { cont ->
                val cts = com.google.android.gms.tasks.CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }
                fusedClient.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
            }
            location?.toTriple()
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocationProvider unavailable: ${e.message}")
            null
        }
    }

    private fun fallbackLocation(context: Context, hasFine: Boolean): Triple<Double, Double, Float?>? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = if (hasFine) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            val age = loc?.let { System.currentTimeMillis() - it.time }
            if (loc == null || age == null || age > MAX_FALLBACK_AGE_MS) {
                if (loc != null) Log.w(TAG, "Discarding stale cached location (age=${age}ms)")
                null
            } else {
                loc.toTriple()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback location unavailable: ${e.message}")
            null
        }
    }

    private fun Location.toTriple() = Triple(latitude, longitude, if (hasAccuracy()) accuracy else null)
}
