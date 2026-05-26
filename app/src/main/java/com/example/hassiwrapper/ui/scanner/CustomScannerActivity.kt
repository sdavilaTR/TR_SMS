package com.example.hassiwrapper.ui.scanner

import android.app.Activity
import android.content.Intent
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent

import androidx.appcompat.app.AppCompatActivity
import com.example.hassiwrapper.R
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.camera.CameraSettings

/**
 * Full-screen portrait-locked scanner with a square rounded viewfinder.
 * Returns the scanned content via [EXTRA_RESULT].
 */
class CustomScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
        const val EXTRA_FRONT_CAMERA = "front_camera"
        private const val TAG = "CustomScannerActivity"
    }

    private lateinit var barcodeView: BarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        barcodeView = findViewById(R.id.barcodeView)

        val useFront = intent.getBooleanExtra(EXTRA_FRONT_CAMERA, false)
        val cameraId = findCameraId(frontFacing = useFront)

        val settings = CameraSettings()
        settings.requestedCameraId = cameraId
        barcodeView.cameraSettings = settings

        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { code ->
                    barcodeView.pause()
                    val data = Intent().apply { putExtra(EXTRA_RESULT, code) }
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }
            }
        })

        findViewById<android.widget.ImageView>(R.id.btnClose).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    /** Use Camera1 API to find the camera ID — must match ZXing which also uses Camera1. */
    @Suppress("DEPRECATION")
    private fun findCameraId(frontFacing: Boolean): Int {
        val targetFacing = if (frontFacing)
            Camera.CameraInfo.CAMERA_FACING_FRONT
        else
            Camera.CameraInfo.CAMERA_FACING_BACK
        return try {
            val n = Camera.getNumberOfCameras()
            val info = Camera.CameraInfo()
            for (i in 0 until n) {
                Camera.getCameraInfo(i, info)
                Log.d(TAG, "Camera1 ID=$i facing=${info.facing} (0=back,1=front)")
                if (info.facing == targetFacing) return i
            }
            if (frontFacing) 1 else 0
        } catch (e: Exception) {
            Log.w(TAG, "Camera1 enumeration failed: ${e.message}")
            if (frontFacing) 1 else 0
        }
    }
}
