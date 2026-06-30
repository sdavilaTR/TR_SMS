package com.example.hassiwrapper.services

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object GpsHelper {

    private const val TAG = "GpsHelper"
    private const val TIMEOUT_MS = 8_000L

    /**
     * Returns (latitude, longitude, accuracy_m) or null if location unavailable.
     * Tries FusedLocationProvider first; falls back to LocationManager (like HeartbeatManager).
     */
    suspend fun getCurrentLocation(context: Context): Triple<Double, Double, Float>? {
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

        return withTimeoutOrNull(TIMEOUT_MS) {
            fusedLocation(context, hasFine) ?: fallbackLocation(context, hasFine)
        } ?: fallbackLocation(context, hasFine)
    }

    private suspend fun fusedLocation(context: Context, hasFine: Boolean): Triple<Double, Double, Float>? {
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

    private fun fallbackLocation(context: Context, hasFine: Boolean): Triple<Double, Double, Float>? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = if (hasFine) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            loc?.toTriple()
        } catch (e: Exception) {
            Log.w(TAG, "Fallback location unavailable: ${e.message}")
            null
        }
    }

    private fun Location.toTriple() = Triple(latitude, longitude, accuracy)
}
