package com.example.hassiwrapper.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.example.hassiwrapper.update.UpdateInstaller

/**
 * Listens for install status broadcasts emitted by [PackageInstaller.Session.commit].
 *
 * Responsibilities:
 *  - Launch the system confirmation Activity on [PackageInstaller.STATUS_PENDING_USER_ACTION].
 *  - Promote the just-installed APK to "previous" on success (rollback copy).
 *  - Surface the real installer error message on failure instead of the opaque
 *    "problem parsing the package" the Android UI shows when the user taps the
 *    DownloadManager notification.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "—"

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    Log.d(TAG, "Launched system install confirmation")
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION but no EXTRA_INTENT")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Install succeeded — promoting update APK to previous")
                UpdateInstaller.clearSessionInFlight(context)
                UpdateInstaller.promoteUpdateToPrevious(context)
            }

            else -> {
                Log.e(TAG, "Install failed — status=$status message=$message")
                UpdateInstaller.clearSessionInFlight(context)
                Toast.makeText(
                    context,
                    "Error al instalar: $message",
                    Toast.LENGTH_LONG
                ).show()
                // Remove the bad APK so the device doesn't keep re-triggering it.
                // The previous APK stays intact for manual rollback.
                UpdateInstaller.deleteFailedUpdate(context)
            }
        }
    }

    companion object {
        private const val TAG = "InstallStatusRcv"
        const val ACTION_INSTALL_STATUS = "com.example.hassiwrapper.INSTALL_STATUS"
    }
}
