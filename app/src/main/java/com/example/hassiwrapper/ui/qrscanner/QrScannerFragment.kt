package com.example.hassiwrapper.ui.qrscanner

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class QrScannerFragment : Fragment() {

    private lateinit var txtScanStatus: TextView
    private lateinit var btnScanCamera: MaterialButton
    private lateinit var layoutResult: LinearLayout
    private lateinit var txtResultType: TextView
    private lateinit var txtResultDetail: TextView
    private lateinit var etKeyboardWedge: EditText

    // ── keyboard-wedge: timeout reassembles multi-line QR payloads ───────────
    private val wedgeHandler = Handler(Looper.getMainLooper())
    private val wedgeTrigger = Runnable {
        val text = etKeyboardWedge.text?.toString()?.trimEnd('\n', '\r', ' ').orEmpty()
        etKeyboardWedge.setText("")
        etKeyboardWedge.requestFocus()
        if (text.isNotBlank()) {
            Log.d(TAG, "Keyboard-wedge scan (${text.length} chars): ${text.take(120)}")
            handleScanResult(text)
        }
    }

    // ── camera launcher ──────────────────────────────────────────────────────

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val code = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)
            code?.let { handleScanResult(it) }
        }
        etKeyboardWedge.requestFocus()
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    // ── direct intent receiver (Intermec / Honeywell intent-output mode) ─────

    private val directScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            Log.d(TAG, "Direct receiver: action=${intent.action}")
            intent.extras?.keySet()?.forEach { key ->
                Log.d(TAG, "  extra[$key] = ${intent.extras?.get(key)}")
            }
            val data = intent.getStringExtra("data")
                ?: intent.getStringExtra("barcode_data")
                ?: intent.getStringExtra("com.symbol.datawedge.data_string")
                ?: run {
                    intent.extras?.keySet()?.firstNotNullOfOrNull { k ->
                        when (val v = intent.extras?.get(k)) {
                            is String -> v.takeIf { it.length >= 3 }
                            is ByteArray -> runCatching { String(v, Charsets.UTF_8) }.getOrNull()?.takeIf { it.length >= 3 }
                            else -> null
                        }
                    }
                } ?: return
            Log.d(TAG, "Direct receiver data: $data")
            handleScanResult(data)
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_qr_scanner, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        txtScanStatus    = view.findViewById(R.id.txtScanStatus)
        btnScanCamera    = view.findViewById(R.id.btnScanCamera)
        layoutResult     = view.findViewById(R.id.layoutResult)
        txtResultType    = view.findViewById(R.id.txtResultType)
        txtResultDetail  = view.findViewById(R.id.txtResultDetail)
        etKeyboardWedge  = view.findViewById(R.id.etKeyboardWedge)

        txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)

        // keyboard-wedge: accumulate chars, fire 250ms after last keystroke
        etKeyboardWedge.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if ((s?.length ?: 0) > 0) {
                    wedgeHandler.removeCallbacks(wedgeTrigger)
                    wedgeHandler.postDelayed(wedgeTrigger, 250)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etKeyboardWedge.requestFocus()

        btnScanCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        // scanFlow (works when Intermec is in intent-broadcast mode)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { code ->
                    Log.d(TAG, "scanFlow: $code")
                    handleScanResult(code)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        etKeyboardWedge.requestFocus()
        Log.d(TAG, "onResume: keyboard-wedge ready, registering intent receiver")
        val filter = IntentFilter().apply {
            addAction("com.honeywell.sample.action.BARCODE")
            addAction("com.honeywell.decode.intent.action.EDIT_DATA")
            addAction("com.intermec.decode.intent.action.EDIT_DATA")
            addAction("com.honeywell.decode.intent.action.BARCODE_DATA")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(directScanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            requireContext().registerReceiver(directScanReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        wedgeHandler.removeCallbacks(wedgeTrigger)
        try { requireContext().unregisterReceiver(directScanReceiver) } catch (_: IllegalArgumentException) {}
    }

    // ── camera ────────────────────────────────────────────────────────────────

    private fun launchCamera() {
        cameraLauncher.launch(
            Intent(requireContext(), CustomScannerActivity::class.java)
                .putExtra(CustomScannerActivity.EXTRA_FRONT_CAMERA, false)
        )
    }

    // ── scan handling ─────────────────────────────────────────────────────────

    private fun handleScanResult(raw: String) {
        val code = raw.trim().trimStart('﻿')
        Log.d(TAG, "handleScanResult (${code.length} chars): ${code.take(120)}")
        Log.d(TAG, "first chars: ${code.take(30).map { it.code }}")
        txtScanStatus.text = getString(R.string.qr_scanner_status_processing)
        layoutResult.visibility = View.GONE

        when (val result = parseQr(code)) {
            is QrResult.Spool        -> lookupSpool(result.spoolId)
            is QrResult.VehicleId    -> lookupVehicleById(result.id)
            is QrResult.VehiclePlate -> lookupVehicleByPlate(result.plate)
            is QrResult.VehicleBadge -> showError(
                getString(R.string.qr_scanner_result_vehicle_badge),
                getString(R.string.qr_scanner_result_badge_unsupported)
            )
            is QrResult.Unknown      -> showError(
                getString(R.string.qr_scanner_result_unknown),
                code.take(100)
            )
        }
    }

    private fun lookupSpool(spoolId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val spool = ServiceLocator.smsSpoolDao.getById(spoolId)
                if (spool != null) {
                    txtScanStatus.text = getString(R.string.qr_scanner_status_spool_found, spool.displayCode)
                    layoutResult.visibility = View.GONE
                    SpoolDetailBottomSheet.newInstance(spool.spool_id)
                        .show(childFragmentManager, "spool_detail")
                } else {
                    showError(
                        getString(R.string.qr_scanner_result_spool_not_found),
                        getString(R.string.qr_scanner_result_id_detail, spoolId.toString())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "lookupSpool failed", e)
                showError(getString(R.string.qr_scanner_result_spool_not_found), e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun lookupVehicleById(vehicleId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val vehicle = ServiceLocator.smsVehicleDao.getById(vehicleId)
                if (vehicle != null) {
                    txtScanStatus.text = getString(R.string.qr_scanner_status_vehicle_found, vehicle.license_plate)
                    layoutResult.visibility = View.GONE
                    val bundle = Bundle().apply { putLong("vehicleId", vehicle.vehicle_id) }
                    findNavController().navigate(R.id.action_qrScannerFragment_to_vehicleDetailFragment, bundle)
                } else {
                    showError(
                        getString(R.string.qr_scanner_result_vehicle_not_found),
                        getString(R.string.qr_scanner_result_id_detail, vehicleId.toString())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "lookupVehicleById failed", e)
                showError(getString(R.string.qr_scanner_result_vehicle_not_found), e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun lookupVehicleByPlate(plate: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val vehicle = ServiceLocator.smsVehicleDao.getByLicensePlate(plate)
                if (vehicle != null) {
                    txtScanStatus.text = getString(R.string.qr_scanner_status_vehicle_found, vehicle.license_plate)
                    layoutResult.visibility = View.GONE
                    val bundle = Bundle().apply { putLong("vehicleId", vehicle.vehicle_id) }
                    findNavController().navigate(R.id.action_qrScannerFragment_to_vehicleDetailFragment, bundle)
                } else {
                    showError(
                        getString(R.string.qr_scanner_result_vehicle_not_found),
                        plate
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "lookupVehicleByPlate failed", e)
                showError(getString(R.string.qr_scanner_result_vehicle_not_found), e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun showError(type: String, detail: String) {
        txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)
        layoutResult.visibility = View.VISIBLE
        txtResultType.text = type
        txtResultDetail.text = detail
        Toast.makeText(requireContext(), "$type\n$detail", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "QrScannerFragment"
    }
}

// ── QR format parser ──────────────────────────────────────────────────────────

private sealed class QrResult {
    data class Spool(val spoolId: Long, val suffix: String?) : QrResult()
    data class VehicleId(val id: Long) : QrResult()
    data class VehiclePlate(val plate: String) : QrResult()
    data class VehicleBadge(val uuid: String) : QrResult()
    data class Unknown(val raw: String) : QrResult()
}

private fun parseQr(text: String): QrResult {
    val upper = text.uppercase()
    if (upper.startsWith("JAFURAH PACKING LIST") || upper.startsWith("RIYAS PACKING LIST")) {
        val lines = text.lines()
        val idLine = lines.find { it.trimStart().uppercase().startsWith("ID:") }
        val spoolId = idLine?.substringAfter(":")?.trim()?.toLongOrNull()
        val suffix = lines.find { it.trimStart().uppercase().startsWith("SUFFIX:") }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }
        android.util.Log.d("QrScannerFragment", "parseQr spool block: idLine=$idLine spoolId=$spoolId")
        return if (spoolId != null) QrResult.Spool(spoolId, suffix) else QrResult.Unknown(text)
    }
    if (text.startsWith("VEH:")) return QrResult.VehicleBadge(text.removePrefix("VEH:"))
    val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(text)
        ?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (urlVehicleId != null) return QrResult.VehicleId(urlVehicleId)
    return QrResult.VehiclePlate(text)
}
