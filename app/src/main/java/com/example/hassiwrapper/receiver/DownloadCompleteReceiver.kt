package com.example.hassiwrapper.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import com.example.hassiwrapper.update.UpdateInstaller
import java.io.File

/**
 * Static receiver declared in AndroidManifest so that HyperOS / MIUI
 * aggressive process management cannot prevent it from firing.
 *
 * When a download completes, we check if it matches a download ID we
 * previously stored and trigger the PackageInstaller session.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DownloadCompleteRcv"
        private const val PREF_NAME = "update_download"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"

        fun savePendingDownloadId(context: Context, downloadId: Long) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .apply()
        }

        fun clearPendingDownloadId(context: Context) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_DOWNLOAD_ID)
                .apply()
        }

        fun getPendingDownloadId(context: Context): Long {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_DOWNLOAD_ID, -1L)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val expectedId = getPendingDownloadId(context)

        if (completedId == -1L || completedId != expectedId) return

        Log.d(TAG, "Download $completedId completed, verifying status…")
        clearPendingDownloadId(context)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(completedId)
        val cursor = dm.query(query)

        try {
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val apkFile = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        UpdateInstaller.APK_FILENAME
                    )
                    Log.d(TAG, "Download successful, launching install for ${apkFile.absolutePath}")
                    UpdateInstaller.installApk(context, apkFile)
                } else {
                    val reason = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    )
                    Log.e(TAG, "Download failed — status=$status reason=$reason")
                }
            }
        } finally {
            cursor.close()
        }
    }
}
