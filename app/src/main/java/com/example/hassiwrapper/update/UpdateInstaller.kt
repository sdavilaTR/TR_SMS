package com.example.hassiwrapper.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.hassiwrapper.BuildConfig
import java.io.File

object UpdateInstaller {

    private const val APK_FILENAME = "atlas-update.apk"
    private const val ACTION_INSTALL_STATUS = "com.example.hassiwrapper.INSTALL_STATUS"

    /**
     * Enqueues an APK download via [DownloadManager] and installs it automatically
     * via [PackageInstaller.Session] once the download completes.
     *
     * Using PackageInstaller.Session instead of ACTION_VIEW avoids Play Store
     * interception and the multi-step file-manager flow — the user sees a single
     * system confirmation dialog and taps Install.
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

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApkViaSession(ctx, apkFile)
                    } else {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                        Log.e("UpdateInstaller", "Download failed — status=$status reason=$reason url=$uri")
                        Toast.makeText(ctx, "Error al descargar (código $reason)", Toast.LENGTH_LONG).show()
                    }
                }
                cursor.close()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    /**
     * Installs the APK via [PackageInstaller.Session].
     *
     * This bypasses the Play Store / file-manager interception that happens with
     * ACTION_VIEW and shows a single system "Install?" dialog instead.
     * On Android 12+ with device-owner privileges the install can be fully silent.
     */
    private fun installApkViaSession(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "Archivo de actualización no encontrado", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Request silent install — honoured when the app holds
                    // INSTALL_PACKAGES (device-owner / privileged system app).
                    // Gracefully falls back to a single confirmation dialog otherwise.
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

        } catch (e: Exception) {
            Log.e("UpdateInstaller", "PackageInstaller.Session failed, using fallback", e)
            installApkFallback(context, apkFile)
        }
    }

    /** Fallback: open APK via ACTION_VIEW if the session API is unavailable. */
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
