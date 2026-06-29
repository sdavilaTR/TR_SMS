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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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

    private lateinit var btnRelocateMode: MaterialButton
    private lateinit var layoutRelocate: LinearLayout
    private lateinit var tilRelocateDest: com.google.android.material.textfield.TextInputLayout
    private lateinit var actvRelocateDest: AutoCompleteTextView
    private lateinit var txtRelocateCount: TextView
    private lateinit var btnRelocateFinish: MaterialButton

    /** A pickable sub-position. [subPositionId] is null only in the CSV fallback
     *  (when the position has no sub-position catalog seeded yet). */
    private data class AssignOption(val label: String, val subPositionId: Long?)

    // ── relocate mode state ──────────────────────────────────────────────────
    private var relocateActive = false
    private var relocateLocationType: String? = null // "LAYDOWN" or "SITE"
    private var relocatePositionId: Int? = null
    private var relocateOptions: List<AssignOption> = emptyList()
    private var relocateDestName: String? = null
    private var relocateSubPositionId: Long? = null
    private var relocateCount = 0

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

        btnRelocateMode   = view.findViewById(R.id.btnRelocateMode)
        layoutRelocate    = view.findViewById(R.id.layoutRelocate)
        tilRelocateDest   = view.findViewById(R.id.tilRelocateDest)
        actvRelocateDest  = view.findViewById(R.id.actvRelocateDest)
        txtRelocateCount  = view.findViewById(R.id.txtRelocateCount)
        btnRelocateFinish = view.findViewById(R.id.btnRelocateFinish)

        btnRelocateMode.setOnClickListener {
            if (relocateActive) stopRelocateMode() else startRelocateMode()
        }
        btnRelocateFinish.setOnClickListener { stopRelocateMode() }
        actvRelocateDest.setOnItemClickListener { _, _, position, _ ->
            val option = relocateOptions.getOrNull(position)
            relocateDestName = option?.label
            relocateSubPositionId = option?.subPositionId
            relocateCount = 0
            updateRelocateCount()
        }

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
            is QrResult.Spool        -> {
                if (relocateActive) relocateSpool(result.spoolCode, result.spoolSuffix)
                else lookupSpool(result.spoolCode, result.spoolSuffix)
            }
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

    // ── relocate mode ─────────────────────────────────────────────────────────

    private fun startRelocateMode() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = ServiceLocator.configRepo.get("device_location")?.trim()?.uppercase()
            if (location != "LAYDOWN" && location != "SITE") {
                Toast.makeText(requireContext(), R.string.qr_scanner_relocate_no_location, Toast.LENGTH_LONG).show()
                return@launch
            }

            val projectId  = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val positionId = ServiceLocator.smsPositionDao.getByCode(location)?.position_id

            // Prefer the real sub-position catalog for this position; fall back to the
            // legacy CSV (label-only, no sub_position_id) when none is seeded yet.
            val catalog = positionId?.let {
                ServiceLocator.smsSubPositionDao.getByPosition(projectId, it)
            }.orEmpty()

            relocateOptions = if (catalog.isNotEmpty()) {
                catalog.map { AssignOption(it.full_path.ifBlank { it.name.ifBlank { it.code } }, it.sub_position_id) }
            } else {
                val csv = if (location == "LAYDOWN")
                    (ServiceLocator.configRepo.get("laydown_sections") ?: "1A,2A,1B,2B,1C,2C,1D,2D")
                else
                    (ServiceLocator.configRepo.get("site_units") ?: "1,2,3,4")
                csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { AssignOption(it, null) }
            }
            tilRelocateDest.hint = getString(
                if (location == "LAYDOWN") R.string.qr_scanner_relocate_label_area
                else R.string.qr_scanner_relocate_label_unit
            )

            if (relocateOptions.isEmpty()) {
                Toast.makeText(requireContext(), R.string.qr_scanner_relocate_no_destinations, Toast.LENGTH_LONG).show()
                return@launch
            }
            actvRelocateDest.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, relocateOptions.map { it.label })
            )

            relocateLocationType = location
            relocatePositionId = positionId
            relocateDestName = null
            relocateSubPositionId = null
            relocateCount = 0
            actvRelocateDest.setText("", false)
            relocateActive = true
            layoutRelocate.visibility = View.VISIBLE
            btnRelocateMode.setText(R.string.qr_scanner_btn_relocate_active)
            updateRelocateCount()
        }
    }

    private fun stopRelocateMode() {
        relocateActive = false
        relocateLocationType = null
        relocatePositionId = null
        relocateDestName = null
        relocateSubPositionId = null
        relocateCount = 0
        layoutRelocate.visibility = View.GONE
        btnRelocateMode.setText(R.string.qr_scanner_btn_relocate)
    }

    private fun updateRelocateCount() {
        val dest = relocateDestName ?: return
        txtRelocateCount.text = getString(R.string.qr_scanner_relocate_count, dest, relocateCount)
    }

    private fun relocateSpool(spoolCode: String, spoolSuffix: String?) {
        if (relocateDestName == null) {
            txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)
            Toast.makeText(requireContext(), R.string.qr_scanner_relocate_select_dest_first, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val spools = ServiceLocator.smsSpoolDao.getByCode(spoolCode)
                val spool = if (spoolSuffix != null)
                    spools.find { it.spool_suffix == spoolSuffix } ?: spools.firstOrNull()
                else
                    spools.firstOrNull()

                if (spool == null) {
                    showError(
                        getString(R.string.qr_scanner_result_spool_not_found),
                        getString(R.string.qr_scanner_result_id_detail, spoolCode)
                    )
                    return@launch
                }

                val destName = relocateDestName ?: return@launch
                if (relocateLocationType == "LAYDOWN") {
                    ServiceLocator.smsSpoolDao.updateArea(spool.spool_id, null, destName)
                } else {
                    ServiceLocator.smsSpoolDao.updateUnit(spool.spool_id, null, destName)
                }
                relocatePositionId?.let { posId ->
                    ServiceLocator.smsSpoolDao.updatePosition(spool.spool_id, posId)
                }
                ServiceLocator.smsSpoolDao.updateSubPosition(spool.spool_id, relocateSubPositionId)
                // Push position + sub-position to the server (authoritative PUT status-flags).
                // Only when a real catalog sub-position is selected; best-effort, non-blocking.
                relocateSubPositionId?.let { subId ->
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        ServiceLocator.syncService.uploadSpoolStatusFlags(
                            projectCode, spool.spool_id, relocatePositionId, subId
                        )
                    }
                }

                relocateCount++
                updateRelocateCount()
                txtScanStatus.text = getString(R.string.qr_scanner_status_waiting)
                showResult(spool.displayCode, getString(R.string.qr_scanner_relocate_success, spool.displayCode, destName))
                (requireActivity() as? MainActivity)?.playSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "relocateSpool failed", e)
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

