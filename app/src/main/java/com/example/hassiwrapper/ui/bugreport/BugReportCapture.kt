package com.example.hassiwrapper.ui.bugreport

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.core.view.drawToBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Screenshot + log capture for the "Reportar incidencia" bug-report flow. */
object BugReportCapture {
    private const val TAG = "BugReportCapture"
    private const val MAX_DIM = 1920
    private const val JPEG_QUALITY = 80
    private const val MAX_LOG_BYTES = 500 * 1024

    /**
     * Captures the current window as a bitmap. Tries [PixelCopy] first (API 26+, the only
     * reliable way to grab hardware-accelerated content like the spool map's SurfaceView);
     * falls back to [View.drawToBitmap] on the root view if PixelCopy isn't available/fails.
     */
    suspend fun captureScreenshot(window: Window, rootView: View): Bitmap {
        val pixelCopyResult = runCatching { captureViaPixelCopy(window, rootView) }.getOrNull()
        if (pixelCopyResult != null) return pixelCopyResult

        Log.w(TAG, "PixelCopy capture unavailable/failed, falling back to drawToBitmap")
        return rootView.drawToBitmap()
    }

    private suspend fun captureViaPixelCopy(window: Window, view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val locationInWindow = IntArray(2).also { view.getLocationInWindow(it) }
        val rect = android.graphics.Rect(
            locationInWindow[0], locationInWindow[1],
            locationInWindow[0] + view.width, locationInWindow[1] + view.height
        )
        return suspendCoroutine { cont ->
            try {
                PixelCopy.request(window, rect, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) cont.resume(bitmap) else cont.resume(null)
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "PixelCopy.request threw: ${e.message}")
                cont.resume(null)
            }
        }
    }

    /** Downscales to [MAX_DIM] and JPEG-compresses, mirroring NewIncidentFragment.compressAndSave. */
    fun saveScreenshot(context: Context, bitmap: Bitmap, uuid: String): String {
        val scaleFactor = MAX_DIM.toFloat() / maxOf(bitmap.width, bitmap.height)
        val out = if (scaleFactor < 1f) {
            val w = (bitmap.width * scaleFactor).toInt()
            val h = (bitmap.height * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap

        val dir = File(context.filesDir, "bug_reports/$uuid").apply { mkdirs() }
        val dest = File(dir, "screenshot.jpg")
        FileOutputStream(dest).use { fos -> out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos) }
        return dest.absolutePath
    }

    /**
     * Tail of this app's own logcat buffer, capped at ~500KB. No permission needed: Android
     * restricts unprivileged apps to their own process's logcat output since API 16, so `logcat -d`
     * here returns exactly this app's existing scattered Log.d/e calls — no custom logger needed.
     */
    fun captureLogsTail(maxBytes: Int = MAX_LOG_BYTES): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time").start()
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor()
            val text = String(bytes, Charsets.UTF_8)
            if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) text
            else text.substring((text.length - maxBytes).coerceAtLeast(0))
        } catch (e: Exception) {
            Log.w(TAG, "logcat capture failed: ${e.message}")
            ""
        }
    }
}
