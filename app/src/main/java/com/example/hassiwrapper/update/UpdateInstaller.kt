package com.example.hassiwrapper.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.receiver.DownloadCompleteReceiver
import java.io.File

object UpdateInstaller {

    const val APK_FILENAME = "atlas-update.apk"
    private const val ACTION_INSTALL_STATUS = "com.example.hassiwrapper.INSTALL_STATUS"

    /**
     * Enqueues an APK download via [DownloadManager].
     *
     * Installation is triggered by [DownloadCompleteReceiver] (a static manifest-
     * registered receiver) so it works even when HyperOS / MIUI kills the Activity
     * before the download finishes.
     */
    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return
        val apkFile = File(downloadDir, APK_FILENAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle("ATLAS Access Control")
            .setDescription("Descargando versión ${updateInfo.version}…")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
            .apply {
                if (BuildConfig.GH_RELEASE_TOKEN.isNotEmpty()) {
                    addRequestHeader("Authorization", "Bearer ${BuildConfig.GH_RELEASE_TOKEN}")
                    addRequestHeader("Accept", "application/octet-stream")
                }
            }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // Persist the download ID so the static receiver can match it
        DownloadCompleteReceiver.savePendingDownloadId(context, downloadId)

        Log.d("UpdateInstaller", "Download enqueued (id=$downloadId)")
    }

    /**
     * Installs a previously-downloaded APK.
     * Called by [DownloadCompleteReceiver] and by MainActivity.onResume (as a safety net
     * in case the receiver was suppressed by the OS).
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.w("UpdateInstaller", "APK file not found: ${apkFile.absolutePath}")
            return
        }
        installApkViaSession(context, apkFile)
    }

    /**
     * Checks if there's a downloaded APK waiting to be installed.
     * Called from MainActivity.onResume as a fallback for devices (Xiaomi HyperOS)
     * where the broadcast receiver may not fire reliably.
     */
    fun installPendingApkIfExists(context: Context) {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return,
            APK_FILENAME
        )
        if (apkFile.exists() && apkFile.length() > 0) {
            Log.d("UpdateInstaller", "Found pending APK in onResume, installing…")
            DownloadCompleteReceiver.clearPendingDownloadId(context)
            installApk(context, apkFile)
        }
    }

    private fun installApkViaSession(context: Context, apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("package.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().copyTo(out)
                    session.fsync(out)
                }

                val statusIntent = Intent(ACTION_INSTALL_STATUS).apply {
                    setPackage(context.packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)

                session.commit(pendingIntent.intentSender)
            }

            Log.d("UpdateInstaller", "PackageInstaller session committed (id=$sessionId)")

            // Delete the APK after committing the session so onResume doesn't re-trigger
            apkFile.delete()

        } catch (e: Exception) {
            Log.e("UpdateInstaller", "PackageInstaller.Session failed, using fallback", e)
            installApkFallback(context, apkFile)
        }
    }

    private fun installApkFallback(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context, "${context.packageName}.provider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
