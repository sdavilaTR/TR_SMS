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
import com.example.hassiwrapper.receiver.InstallStatusReceiver
import java.io.File

object UpdateInstaller {

    const val APK_FILENAME = "atlas-update.apk"
    const val PREVIOUS_APK_FILENAME = "atlas-previous.apk"

    private const val TAG = "UpdateInstaller"

    // ── Public file helpers (also used by the status receiver) ─────────────

    fun getUpdateApkFile(context: Context): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(dir, APK_FILENAME)
    }

    fun getPreviousApkFile(context: Context): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return File(dir, PREVIOUS_APK_FILENAME)
    }

    fun hasPreviousApk(context: Context): Boolean {
        val f = getPreviousApkFile(context) ?: return false
        return f.exists() && f.length() > 0
    }

    // ── Download flow ──────────────────────────────────────────────────────

    /**
     * Enqueues an APK download via [DownloadManager]. The previous "previous-version"
     * APK is NOT touched — it stays as a rollback target until a newer install succeeds.
     */
    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        val apkFile = getUpdateApkFile(context) ?: return
        // Only clear the in-flight update file; leave atlas-previous.apk alone.
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

        DownloadCompleteReceiver.savePendingDownloadId(context, downloadId)

        Log.d(TAG, "Download enqueued (id=$downloadId)")
    }

    // ── Install entry points ───────────────────────────────────────────────

    /**
     * Installs a previously-downloaded APK. Called by [DownloadCompleteReceiver] and by
     * MainActivity.onResume (safety net for devices where the receiver is suppressed).
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.w(TAG, "APK not found or empty: ${apkFile.absolutePath}")
            return
        }
        if (!isValidApk(apkFile)) {
            val preview = readFilePreview(apkFile, 200)
            Log.e(TAG, "Downloaded file is not a valid APK. First 200 bytes: $preview")
            Toast.makeText(
                context,
                "La descarga no es un APK válido — revise el token de actualización",
                Toast.LENGTH_LONG
            ).show()
            apkFile.delete()
            return
        }
        installApkViaSession(context, apkFile)
    }

    /**
     * Checks if there's a downloaded APK waiting to be installed.
     * Called from MainActivity.onResume as a fallback for devices (Xiaomi HyperOS,
     * some Honeywell firmware) where the broadcast receiver may not fire reliably.
     */
    fun installPendingApkIfExists(context: Context) {
        val apkFile = getUpdateApkFile(context) ?: return
        if (apkFile.exists() && apkFile.length() > 0) {
            Log.d(TAG, "Found pending APK in onResume, installing…")
            DownloadCompleteReceiver.clearPendingDownloadId(context)
            installApk(context, apkFile)
        }
    }

    /**
     * Manually triggered from Settings. Re-installs the last successfully installed
     * APK (kept as rollback). No-op with a toast if there is no previous APK.
     */
    fun reinstallPreviousVersion(context: Context) {
        val previous = getPreviousApkFile(context)
        if (previous == null || !previous.exists() || previous.length() == 0L) {
            Toast.makeText(
                context,
                "No hay versión anterior disponible",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!isValidApk(previous)) {
            Log.e(TAG, "Previous APK corrupt, refusing to install")
            Toast.makeText(
                context,
                "La versión anterior está corrupta",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Log.d(TAG, "Reinstalling previous APK: ${previous.absolutePath}")
        installApkViaSession(context, previous)
    }

    // ── Called by InstallStatusReceiver ────────────────────────────────────

    /** On successful install, the current update APK becomes the rollback copy. */
    fun promoteUpdateToPrevious(context: Context) {
        val current = getUpdateApkFile(context) ?: return
        val previous = getPreviousApkFile(context) ?: return
        if (!current.exists()) return
        try {
            if (previous.exists()) previous.delete()
            val renamed = current.renameTo(previous)
            if (!renamed) {
                // Cross-mount fallback: copy + delete
                current.inputStream().use { input ->
                    previous.outputStream().use { output -> input.copyTo(output) }
                }
                current.delete()
            }
            Log.d(TAG, "Promoted ${current.name} → ${previous.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote update APK to previous", e)
        }
    }

    /** Remove a failed update APK. Leaves atlas-previous.apk untouched. */
    fun deleteFailedUpdate(context: Context) {
        val current = getUpdateApkFile(context) ?: return
        if (current.exists()) {
            val ok = current.delete()
            Log.d(TAG, "Deleted failed update APK (success=$ok)")
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

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

                val statusIntent = Intent(InstallStatusReceiver.ACTION_INSTALL_STATUS).apply {
                    setPackage(context.packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)

                session.commit(pendingIntent.intentSender)
            }

            Log.d(TAG, "PackageInstaller session committed (id=$sessionId) — waiting for status broadcast")
            // NOTE: the APK file is NOT deleted here. InstallStatusReceiver does it:
            //  - on SUCCESS: promoted to atlas-previous.apk (rollback copy)
            //  - on FAILURE: deleted (see deleteFailedUpdate)

        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller.Session failed, using fallback", e)
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

    /** APKs are ZIPs — they start with PK\x03\x04. Cheap guard against HTML/JSON saved as .apk. */
    private fun isValidApk(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readFilePreview(file: File, maxBytes: Int): String {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf).coerceAtLeast(0)
                String(buf, 0, n, Charsets.UTF_8).replace(Regex("[\\r\\n]+"), " ")
            }
        } catch (_: Exception) {
            "<unreadable>"
        }
    }
}
