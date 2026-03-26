package com.example.hassiwrapper.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Native Honeywell DataWedge scanner integration for EDA52.
 *
 * Replaces ALL the fragile HID / IME / keyboard wedge hacks from scanner.js.
 * Uses DataWedge intent output to receive barcode data directly.
 *
 * Setup: DataWedge must be configured to output scan results as intents:
 *   Action: com.honeywell.action.BARCODE_SCAN (or custom)
 *   Category: android.intent.category.DEFAULT
 *   Delivery: Broadcast
 *
 * This class also sends an intent to configure DataWedge programmatically.
 */
class DataWedgeManager(private val context: Context) {

    companion object {
        private const val TAG = "DataWedgeManager"

        // Honeywell DataWedge actions
        private const val ACTION_BARCODE_DATA = "com.honeywell.sample.action.BARCODE"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_AIMID = "aimid"
        private const val EXTRA_CHARSET = "charset"

        // Honeywell standard decode intent (fallback)
        private const val ACTION_DECODE = "com.honeywell.decode.intent.action.EDIT_DATA"

        // Intermec intent (legacy fallback)
        private const val ACTION_INTERMEC = "com.intermec.decode.intent.action.EDIT_DATA"

        // DataWedge API intents for programmatic configuration
        private const val DW_PACKAGE = "com.honeywell.dataterminal"
        private const val DW_ACTION_CREATE_PROFILE = "com.honeywell.action.CONFIGURE_DATAWEDGE"
    }

    private val _scanFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val scanFlow: SharedFlow<String> = _scanFlow

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Intent received: action=$action")

            val scannedData = when (action) {
                ACTION_BARCODE_DATA -> getStringOrBytes(intent.extras, EXTRA_DATA)
                ACTION_DECODE, ACTION_INTERMEC -> {
                    getStringOrBytes(intent.extras, "data")
                        ?: getStringOrBytes(intent.extras, "barcode_data")
                }
                else -> {
                    intent.getStringExtra("com.symbol.datawedge.data_string")
                        ?: getStringOrBytes(intent.extras, "data")
                        ?: getStringOrBytes(intent.extras, "barcode_data")
                        ?: extractFromBundle(intent.extras)
                }
            }

            if (!scannedData.isNullOrEmpty()) {
                Log.d(TAG, "Scanned: $scannedData")
                _scanFlow.tryEmit(scannedData.trim())
            }
        }
    }

    /** Read a bundle extra that may be either a String or a byte[] (charset from intent or UTF-8). */
    private fun getStringOrBytes(extras: Bundle?, key: String): String? {
        extras ?: return null
        return when (val value = extras.get(key)) {
            is String -> value.takeIf { it.isNotEmpty() }
            is ByteArray -> {
                val charsetName = extras.getString(EXTRA_CHARSET)?.takeIf { it.isNotEmpty() } ?: "UTF-8"
                runCatching { String(value, charset(charsetName)) }.getOrElse { String(value, Charsets.UTF_8) }
                    .takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }

    /** Extract scan data from bundle extras (some DataWedge versions use different keys). */
    private fun extractFromBundle(extras: Bundle?): String? {
        extras ?: return null
        for (key in extras.keySet()) {
            val value = extras.get(key)
            val str = when (value) {
                is String -> value.takeIf { it.length >= 3 }
                is ByteArray -> runCatching { String(value, Charsets.UTF_8) }.getOrNull()?.takeIf { it.length >= 3 }
                else -> null
            }
            if (str != null) {
                Log.d(TAG, "Found data in extra '$key': $str")
                return str
            }
        }
        return null
    }

    /** Register the broadcast receiver for scan intents. */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_BARCODE_DATA)
            addAction(ACTION_DECODE)
            addAction(ACTION_INTERMEC)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver, filter)
        }
        Log.d(TAG, "Scanner receiver registered")

        // Try to configure DataWedge
        configureDataWedge()
    }

    /** Unregister the broadcast receiver. */
    fun unregister() {
        try {
            context.unregisterReceiver(scanReceiver)
            Log.d(TAG, "Scanner receiver unregistered")
        } catch (_: IllegalArgumentException) {
            // Ignored — not registered
        }
    }

    /**
     * Configure DataWedge to send barcode data via intent output.
     * This ensures the profile is set up correctly without manual user config.
     */
    private fun configureDataWedge() {
        try {
            // Honeywell DataWedge: set intent output
            val profileIntent = Intent().apply {
                action = "com.honeywell.aidc.action.ACTION_CLAIM_SCANNER"
                setPackage("com.honeywell.decode.DecodeService")
                putExtra("com.honeywell.aidc.extra.EXTRA_PROFILE", "HassiWrapper")
                putExtra("com.honeywell.aidc.extra.EXTRA_PROPERTIES", Bundle().apply {
                    putString("DPR_DATA_INTENT", "true")
                    putString("DPR_DATA_INTENT_ACTION", ACTION_BARCODE_DATA)
                })
            }
            context.sendBroadcast(profileIntent)
            Log.d(TAG, "DataWedge configuration sent")
        } catch (e: Exception) {
            Log.w(TAG, "DataWedge configuration failed (manual config may be needed): ${e.message}")
        }
    }
}
