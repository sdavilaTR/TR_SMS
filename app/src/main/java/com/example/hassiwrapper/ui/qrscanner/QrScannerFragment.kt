package com.example.hassiwrapper.ui.qrscanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
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
        Log.w(TAG, "=== WEDGE TRIGGER === text='${text.take(80)}' blank=${text.isBlank()}")
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
        etKeyboardWedge.showSoftInputOnFocus = false

        txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)

        // keyboard-wedge: accumulate chars, fire 250ms after last keystroke
        etKeyboardWedge.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                Log.w(TAG, "=== WEDGE TEXT === len=${s?.length} chars=${s?.take(20)}")
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
    }

    override fun onPause() {
        super.onPause()
        wedgeHandler.removeCallbacks(wedgeTrigger)
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
            is QrResult.Spool        -> lookupSpool(result.spoolCode, result.spoolSuffix)
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

    private fun lookupSpool(spoolCode: String, spoolSuffix: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val spools = ServiceLocator.smsSpoolDao.getByCode(spoolCode)
                val spool = if (spoolSuffix != null)
                    spools.find { it.spool_suffix == spoolSuffix } ?: spools.firstOrNull()
                else
                    spools.firstOrNull()
                if (spool != null) {
                    txtScanStatus.text = getString(R.string.qr_scanner_status_spool_found, spool.displayCode)
                    showResult(spool.displayCode, buildSpoolDetail(spool))
                } else {
                    showError(
                        getString(R.string.qr_scanner_result_spool_not_found),
                        getString(R.string.qr_scanner_result_id_detail, spoolCode)
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
                    showResult(vehicle.license_plate, buildVehicleDetail(vehicle))
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
                    showResult(vehicle.license_plate, buildVehicleDetail(vehicle))
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

    private fun showResult(title: String, detail: String) {
        layoutResult.visibility = View.VISIBLE
        txtResultType.text = title
        txtResultDetail.text = detail
    }

    private fun showError(type: String, detail: String) {
        txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)
        showResult(type, detail)
        Toast.makeText(requireContext(), "$type\n$detail", Toast.LENGTH_LONG).show()
    }

    private fun buildSpoolDetail(spool: SmsSpoolEntity) = buildString {
        spool.packing_list_name?.let { appendLine("PL: $it") }
        spool.status?.let { appendLine("Estado: $it") }
        spool.description?.let { appendLine(it) }
        spool.line_code?.let { appendLine("Línea: $it") }
        spool.zone?.let { append("Zona: $it") }
    }.trimEnd()

    private fun buildVehicleDetail(v: SmsVehicleEntity) = buildString {
        v.vehicle_name?.let { appendLine(it) }
        v.vehicle_type?.let { appendLine("Tipo: $it") }
        v.company?.let { append("Empresa: $it") }
    }.trimEnd()

    companion object {
        private const val TAG = "QrScannerFragment"
    }
}

