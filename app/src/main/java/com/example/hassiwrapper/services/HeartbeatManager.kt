package com.example.hassiwrapper.services

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.network.ApiClient
import com.example.hassiwrapper.network.dto.HeartbeatPayload

/**
 * Collects device telemetry (battery, GPS, app version) and sends it to the API.
 * Best-effort: failures are silently ignored so they never block sync or other operations.
 */
class HeartbeatManager(
    private val context: Context,
    private val apiClient: ApiClient,
    private val configRepo: ConfigRepository
) {
    companion object {
        private const val TAG = "HeartbeatManager"
    }

    suspend fun sendHeartbeat() {
        try {
            val location = getLastLocation()
            val payload = HeartbeatPayload(
                batteryLevel = getBatteryLevel(),
                latitude     = location?.latitude,
                longitude    = location?.longitude,
                gpsAccuracyM = location?.accuracy,
                appVersion   = BuildConfig.BUILD_TAG,
                lastSyncUtc  = configRepo.get("last_sync") ?: "",
                osVersion    = "Android ${Build.VERSION.RELEASE}",
                deviceModel  = Build.MODEL
            )
            apiClient.getService().sendHeartbeat(payload)
            Log.d(TAG, "Heartbeat sent (battery=${payload.batteryLevel}%, version=${payload.appVersion})")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed (non-fatal): ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getLastLocation(): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable: ${e.message}")
            null
        }
    }
}
