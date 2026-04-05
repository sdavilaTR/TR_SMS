package com.example.hassiwrapper.ui.scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.example.hassiwrapper.R
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView

/**
 * Full-screen portrait-locked scanner with a square rounded viewfinder.
 * Returns the scanned content via [EXTRA_RESULT].
 */
class CustomScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT = "scan_result"
    }

    private lateinit var barcodeView: BarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        barcodeView = findViewById(R.id.barcodeView)
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
}
