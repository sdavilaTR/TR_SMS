package com.example.hassiwrapper.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.hassiwrapper.BuildConfig
import java.io.File

object UpdateInstaller {

    // Fixed filename — no version suffix, avoids accumulating stale APKs on the device
    private const val APK_FILENAME = "atlas-update.apk"

    /**
     * Enqueues an APK download via [DownloadManager] and automatically launches the
     * system installer when the download completes successfully.
     */
    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return
        val apkFile = File(downloadDir, APK_FILENAME)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle("ATLAS Access Control")
            .setDescription("Descargando versión ${updateInfo.version}…")
            // setDestinationInExternalFilesDir is more reliable than setDestinationUri(file://)
            // on Android 10+ and avoids needing WRITE_EXTERNAL_STORAGE permission.
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
            .apply {
                // Private repo: GitHub returns 302 → S3 signed URL.
                // DownloadManager strips the Authorization header on cross-origin redirect,
                // so the token never reaches S3.
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

                // Check actual status before attempting install
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApk(ctx, apkFile)
                    } else {
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        Toast.makeText(
                            ctx,
                            "Error al descargar la actualización (código $reason)",
                            Toast.LENGTH_LONG
                        ).show()
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
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "Archivo de actualización no encontrado", Toast.LENGTH_LONG).show()
            return
        }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
    }
}
