package com.example.hassiwrapper.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class TracDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        applyOwnerDefaults(context)
    }

    override fun onDisabled(context: Context, intent: Intent) = Unit

    companion object {
        private const val TAG = "TracDeviceAdmin"

        /**
         * Called both from onEnabled (first activation) and from MainActivity.onCreate
         * (to fix state on already-provisioned devices). Ensures the Device Owner
         * policy never accidentally blocks camera or runtime permissions.
         */
        fun applyOwnerDefaults(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, TracDeviceAdmin::class.java)
            try {
                // Ensure camera is never disabled by Device Admin policy.
                dpm.setCameraDisabled(admin, false)
            } catch (e: Exception) {
                Log.w(TAG, "setCameraDisabled(false) failed: ${e.message}")
            }
            try {
                // Grant camera permission silently so the runtime dialog never blocks
                // camera access in a kiosk/Device Owner context.
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    android.Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "setPermissionGrantState(CAMERA) failed: ${e.message}")
            }
            try {
                // Without this allowlist, startLockTask() falls back to plain screen
                // pinning (consent dialog, status bar fully forced off) instead of the
                // silent Device Owner Lock Task mode that respects setLockTaskFeatures.
                dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            } catch (e: Exception) {
                Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
            }
            try {
                // Allow status bar system info (clock, battery, connectivity) while
                // in Lock Task (kiosk) mode; Device Owner hides it by default otherwise.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
            } catch (e: Exception) {
                Log.w(TAG, "setLockTaskFeatures failed: ${e.message}")
            }
        }
    }
}
