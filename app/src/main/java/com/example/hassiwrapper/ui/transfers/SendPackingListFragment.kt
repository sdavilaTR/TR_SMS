package com.example.hassiwrapper.ui.transfers

import android.util.Log
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolLocationEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolPropertyEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingSpoolEntity
import com.example.hassiwrapper.jDbl
import com.example.hassiwrapper.jInt
import com.example.hassiwrapper.jStr
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.example.hassiwrapper.network.dto.CreatePackingListRequest
import com.example.hassiwrapper.services.GpsHelper
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.LocalDateTime

private val BAKED_IN_SUFFIX_SHAPE = Regex("""(?i)^SP\d+$""")

class SendPackingListFragment : Fragment() {

    private data class ScannedSpool(
        val spoolId: Long,
        val spoolCode: String,
        val spoolSuffix: String?,
        val revision: String? = null,
        val packingListId: Long?,
        val packingListName: String?,
        val weightKg: Double? = null
    ) {
        val displayCode: String
            get() {
                val suffix = spoolSuffix?.takeIf { it.isNotBlank() }
                val alreadyBaked = suffix != null && BAKED_IN_SUFFIX_SHAPE.matches(suffix) &&
                    spoolCode.endsWith("-$suffix", ignoreCase = true)
                val base = if (suffix == null || alreadyBaked) spoolCode else "$spoolCode-$suffix"
                return if (revision.isNullOrBlank()) base else "$base-$revision"
            }
    }

    private var selectedVehicle: SmsVehicleEntity? = null
    private val scannedSpools = mutableListOf<ScannedSpool>()
    private lateinit var adapter: ScannedSpoolAdapter
    private var destination = ""

    // Location-blocked message
    private lateinit var txtLocationBlocked: TextView

    // Step 1: vehicle
    private lateinit var panelVehicle: View
    private lateinit var etPlate: TextInputEditText
    private lateinit var btnScanVehicle: MaterialButton
    private lateinit var btnConfirmVehicle: MaterialButton

    // Step 2: spools
    private lateinit var panelSpools: View
    private lateinit var txtSelectedVehicle: TextView
    private lateinit var cardWeightMeter: MaterialCardView
    private lateinit var txtWeightMeter: TextView
    private lateinit var progressWeight: android.widget.ProgressBar
    private lateinit var etSpoolCode: TextInputEditText
    private lateinit var etSpoolSuffix: TextInputEditText
    private lateinit var btnScanSpool: MaterialButton
    private lateinit var btnAddSpool: MaterialButton
    private lateinit var rvLoadedSpools: RecyclerView
    private lateinit var txtSpoolsCount: TextView
    private lateinit var btnContinueToSignature: MaterialButton

    // Step 3: destination + signature
    private lateinit var panelDestination: View
    private lateinit var panelDestinationChoice: View
    private lateinit var rgDestination: RadioGroup
    private lateinit var txtSendDestination: TextView
    private lateinit var btnConfirmSend: MaterialButton
    private lateinit var panelUploadProgress: View
    private lateinit var txtUploadStatus: TextView

    private val vehicleScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.trim() ?: return@registerForActivityResult
            resolveAndSelectVehicle(raw)
        }
    }

    private val spoolScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.trim() ?: return@registerForActivityResult
            parseAndAddSpoolFromQr(raw)
        }
    }

    // Send-confirm captures a best-effort GPS fix; request location permission once up
    // front so it's not silently unavailable for the lifetime of the app (see GpsHelper).
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* best-effort — GpsHelper silently skips capture if denied */ }

    private var pendingCameraAction: (() -> Unit)? = null
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) pendingCameraAction?.invoke() }

    private fun launchScannerWithPermission(action: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCameraAction = action
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_send_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackNestedScrollView).onSwipeBack = { findNavController().navigateUp() }

        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        txtLocationBlocked = view.findViewById(R.id.txtLocationBlocked)

        panelVehicle      = view.findViewById(R.id.panelVehicle)
        etPlate           = view.findViewById(R.id.etPlate)
        btnScanVehicle    = view.findViewById(R.id.btnScanVehicle)
        btnConfirmVehicle = view.findViewById(R.id.btnConfirmVehicle)

        panelSpools           = view.findViewById(R.id.panelSpools)
        txtSelectedVehicle    = view.findViewById(R.id.txtSelectedVehicle)
        cardWeightMeter       = view.findViewById(R.id.cardWeightMeter)
        txtWeightMeter        = view.findViewById(R.id.txtWeightMeter)
        progressWeight        = view.findViewById(R.id.progressWeight)
        etSpoolCode           = view.findViewById(R.id.etSpoolCode)
        etSpoolSuffix         = view.findViewById(R.id.etSpoolSuffix)
        btnScanSpool          = view.findViewById(R.id.btnScanSpool)
        btnAddSpool           = view.findViewById(R.id.btnAddSpool)
        rvLoadedSpools        = view.findViewById(R.id.rvLoadedSpools)
        txtSpoolsCount        = view.findViewById(R.id.txtSpoolsCount)
        btnContinueToSignature = view.findViewById(R.id.btnContinueToSignature)

        panelDestination         = view.findViewById(R.id.panelDestination)
        panelDestinationChoice   = view.findViewById(R.id.panelDestinationChoice)
        rgDestination            = view.findViewById(R.id.rgDestination)
        txtSendDestination       = view.findViewById(R.id.txtSendDestination)
        btnConfirmSend           = view.findViewById(R.id.btnConfirmSend)
        panelUploadProgress      = view.findViewById(R.id.panelUploadProgress)
        txtUploadStatus          = view.findViewById(R.id.txtUploadStatus)

        adapter = ScannedSpoolAdapter()
        rvLoadedSpools.layoutManager = LinearLayoutManager(requireContext())
        rvLoadedSpools.adapter = adapter
        rvLoadedSpools.isNestedScrollingEnabled = false
        rvLoadedSpools.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, 0)

        btnScanVehicle.setOnClickListener {
            launchScannerWithPermission { vehicleScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java)) }
        }
        btnConfirmVehicle.setOnClickListener { confirmVehicle() }

        btnScanSpool.setOnClickListener {
            launchScannerWithPermission { spoolScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java)) }
        }
        btnAddSpool.setOnClickListener { addSpoolManually() }

        btnContinueToSignature.setOnClickListener { goToDestinationPanel() }
        btnConfirmSend.setOnClickListener { onConfirmSend() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    handleHardwareScan(raw.trim())
                }
            }
        }

        checkLocationGate()
    }

    private fun checkLocationGate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            if (location != "WORKSHOP" && location != "LAYDOWN") {
                panelVehicle.visibility = View.GONE
                txtLocationBlocked.visibility = View.VISIBLE
            }
        }
    }

    private fun handleHardwareScan(raw: String) {
        when {
            panelSpools.visibility == View.VISIBLE  -> parseAndAddSpoolFromQr(raw)
            panelVehicle.visibility == View.VISIBLE -> resolveAndSelectVehicle(raw)
        }
    }

    // ───────────────────────── Step 1: Vehicle ─────────────────────────

    private fun resolveAndSelectVehicle(raw: String) {
        if (parseQr(raw) is QrResult.Spool) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_qr_is_spool), Toast.LENGTH_LONG).show()
            return
        }
        val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
        viewLifecycleOwner.lifecycleScope.launch {
            if (urlVehicleId != null) {
                val vehicle = ServiceLocator.smsVehicleDao.getById(urlVehicleId)
                if (vehicle != null) {
                    etPlate.setText(vehicle.license_plate)
                    selectVehicle(vehicle)
                } else Toast.makeText(requireContext(), getString(R.string.load_spools_vehicle_not_found), Toast.LENGTH_LONG).show()
            } else {
                val vehicle = ServiceLocator.smsVehicleDao.getByLicensePlate(raw)
                if (vehicle != null) {
                    etPlate.setText(vehicle.license_plate)
                    selectVehicle(vehicle)
                } else etPlate.setText(raw)
            }
        }
    }

    private fun selectVehicle(vehicle: SmsVehicleEntity) {
        selectedVehicle = vehicle
        txtSelectedVehicle.text = getString(R.string.load_spools_vehicle_selected, vehicle.license_plate)
        panelVehicle.visibility = View.GONE
        panelSpools.visibility = View.VISIBLE
        updateWeightMeter()
    }

    private fun confirmVehicle() {
        val plate = etPlate.text?.toString()?.trim().orEmpty()
        if (plate.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_enter_plate), Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val vehicle = ServiceLocator.smsVehicleDao.getByLicensePlate(plate)
            if (vehicle == null) {
                Toast.makeText(requireContext(), getString(R.string.load_spools_vehicle_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            selectVehicle(vehicle)
        }
    }

    // ───────────────────────── Step 2: Spools ─────────────────────────

    private fun parseAndAddSpoolFromQr(raw: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

            val qrResult = parseQr(raw)
            val (code, suffix) = when (qrResult) {
                is QrResult.Spool -> qrResult.spoolCode to qrResult.spoolSuffix
                else -> {
                    val lastDash = raw.lastIndexOf('-')
                    if (lastDash > 0) raw.substring(0, lastDash) to raw.substring(lastDash + 1)
                    else raw to null
                }
            }

            val spool = if (!suffix.isNullOrBlank()) {
                ServiceLocator.smsSpoolDao.findByCodeAndSuffix(projectId, code, suffix)
                    ?: ServiceLocator.smsSpoolDao.findByCode(projectId, code)
            } else {
                ServiceLocator.smsSpoolDao.findByCode(projectId, code)
            }

            val weight = resolveSpoolWeight(spool)
            addSpool(spool, weight)
        }
    }

    private fun addSpoolManually() {
        val code   = etSpoolCode.text?.toString()?.trim().orEmpty()
        val suffix = etSpoolSuffix.text?.toString()?.trim()?.ifBlank { null }
        if (code.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_enter_code), Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val spool = if (suffix != null) {
                ServiceLocator.smsSpoolDao.findByCodeAndSuffix(projectId, code, suffix)
                    ?: ServiceLocator.smsSpoolDao.findByCode(projectId, code)
            } else {
                ServiceLocator.smsSpoolDao.findByCode(projectId, code)
            }
            val weight = resolveSpoolWeight(spool)
            addSpool(spool, weight)
            etSpoolCode.text?.clear()
            etSpoolSuffix.text?.clear()
        }
    }

    private fun addSpool(spool: SmsSpoolEntity?, weightKg: Double? = null) {
        if (spool == null) {
            // Not adding it: a phantom entry (spoolId=0) used to get added anyway and then
            // silently dropped at confirm time (filtered by spoolId != 0L everywhere it's
            // actually persisted) — spool looked "added" in the UI but never reached the
            // packing list. Bail here instead, matching every other scan-to-PL flow.
            Toast.makeText(requireContext(), getString(R.string.load_spools_spool_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val displayCode = spool.displayCode

        if (scannedSpools.any { it.displayCode.equals(displayCode, ignoreCase = true) }) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_spool_duplicate), Toast.LENGTH_SHORT).show()
            return
        }

        val item = ScannedSpool(
            spoolId         = spool.spool_id,
            spoolCode       = spool.spool_code,
            spoolSuffix     = spool.spool_suffix,
            revision        = spool.revision,
            packingListId   = spool.packing_list_id,
            packingListName = spool.packing_list_name,
            weightKg        = weightKg
        )
        scannedSpools.add(item)
        adapter.notifyItemInserted(scannedSpools.size - 1)
        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, scannedSpools.size)
        Toast.makeText(requireContext(), getString(R.string.load_spools_spool_added, displayCode), Toast.LENGTH_SHORT).show()
        updateWeightMeter()
    }

    private fun removeSpoolAt(position: Int) {
        if (position < 0 || position >= scannedSpools.size) return
        scannedSpools.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, scannedSpools.size - position)
        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, scannedSpools.size)
        updateWeightMeter()
    }

    /** Local property table is rarely synced for normal spools, so fall back
     *  to fetching+caching from the API (same pattern as SpoolDetailBottomSheet).
     *  Silently returns null offline — that spool just won't count toward the meter. */
    private suspend fun resolveSpoolWeight(spool: SmsSpoolEntity?): Double? {
        if (spool == null) return null
        ServiceLocator.smsSpoolPropertyDao.getBySpool(spool.spool_id)?.weight_kg?.let { return it }

        return try {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code ?: return null
            val service = ServiceLocator.apiClient.getService()
            val resp = service.getSpoolProperty(projectCode, spool.spool_id.toString())
            if (!resp.isSuccessful) return null
            val raw = resp.body()?.string().orEmpty()
            val el = com.google.gson.JsonParser.parseString(raw)
            if (!el.isJsonObject) return null
            val obj = el.asJsonObject.let { o ->
                if (o.has("data") && !o.get("data").isJsonNull && o.get("data").isJsonObject) o.getAsJsonObject("data") else o
            }
            val weight = obj.jDbl("weightKg", "weight_kg") ?: return null
            ServiceLocator.smsSpoolPropertyDao.insertAll(listOf(
                SmsSpoolPropertyEntity(
                    spool_id        = spool.spool_id,
                    diameter_inches = obj.jDbl("diameterInches", "diameter_inches"),
                    diameter        = obj.jDbl("diameter"),
                    bore_size_id    = obj.jInt("boreSizeId", "bore_size_id"),
                    weight_kg       = weight,
                    updated_at      = obj.jStr("updatedAt", "updated_at").orEmpty()
                )
            ))
            weight
        } catch (_: Exception) { null }
    }

    private fun updateWeightMeter() {
        val vehicle = selectedVehicle
        val totalKg = scannedSpools.sumOf { it.weightKg ?: 0.0 }

        if (vehicle == null || scannedSpools.isEmpty()) {
            cardWeightMeter.visibility = View.GONE
            return
        }

        val capacityKg = vehicle.capacity_weight_kg
        if (capacityKg == null || capacityKg <= 0.0) {
            cardWeightMeter.visibility = View.VISIBLE
            txtWeightMeter.text = getString(R.string.load_spools_weight_no_capacity, formatKg(totalKg))
            txtWeightMeter.setTextColor(requireContext().getColor(R.color.on_surface))
            progressWeight.progress = 0
            progressWeight.progressTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.green))
            return
        }

        val ratio = totalKg / capacityKg
        val percent = (ratio * 100).toInt()
        val colorRes = when {
            ratio >= 1.0  -> R.color.error
            ratio >= 0.8  -> R.color.warning
            else          -> R.color.green
        }
        val color = requireContext().getColor(colorRes)

        cardWeightMeter.visibility = View.VISIBLE
        txtWeightMeter.text = getString(R.string.load_spools_weight_meter, formatKg(totalKg), formatKg(capacityKg), percent)
        txtWeightMeter.setTextColor(color)
        progressWeight.progress = percent.coerceIn(0, 100)
        progressWeight.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun formatKg(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.1f", value)

    private fun parseCreatedPlId(raw: String): Long? {
        return try {
            val el = com.google.gson.JsonParser.parseString(raw)
            val obj = when {
                el.isJsonObject && el.asJsonObject.has("data") && !el.asJsonObject.get("data").isJsonNull ->
                    el.asJsonObject.getAsJsonObject("data")
                el.isJsonObject -> el.asJsonObject
                else -> return null
            }
            obj.get("packingListId")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("packing_list_id")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        } catch (_: Exception) { null }
    }

    // ───────────────────────── Step 3: Destination ─────────────────────────

    private fun goToDestinationPanel() {
        if (scannedSpools.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_empty_error), Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val location = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            if (location != "WORKSHOP" && location != "LAYDOWN") return@launch

            panelSpools.visibility = View.GONE
            panelDestination.visibility = View.VISIBLE

            if (location == "WORKSHOP") {
                panelDestinationChoice.visibility = View.VISIBLE
                rgDestination.clearCheck()
                txtSendDestination.visibility = View.GONE
                destination = ""
                rgDestination.setOnCheckedChangeListener { _, checkedId ->
                    val destCode = if (checkedId == R.id.rbDestSite) "SITE" else "LAYDOWN"
                    destination = destCode
                    viewLifecycleOwner.lifecycleScope.launch {
                        val pos = ServiceLocator.smsPositionDao.getByCode(destCode)
                        txtSendDestination.text = getString(R.string.transfer_send_to, pos?.name ?: destCode)
                        txtSendDestination.visibility = View.VISIBLE
                    }
                }
            } else {
                panelDestinationChoice.visibility = View.GONE
                val destCode = "SITE"
                val pos = ServiceLocator.smsPositionDao.getByCode(destCode)
                destination = destCode
                txtSendDestination.text = getString(R.string.transfer_send_to, pos?.name ?: destCode)
                txtSendDestination.visibility = View.VISIBLE
            }
        }
    }

    private fun onConfirmSend() {
        if (panelDestinationChoice.visibility == View.VISIBLE && rgDestination.checkedRadioButtonId == -1) {
            Toast.makeText(requireContext(), getString(R.string.transfer_send_destination_required), Toast.LENGTH_SHORT).show()
            return
        }
        val vehicle = selectedVehicle ?: return
        if (!btnConfirmSend.isEnabled) return
        btnConfirmSend.isEnabled = false
        Log.d("SendPL", "onConfirmSend start: vehicle=${vehicle.license_plate} spools=${scannedSpools.size} destination=$destination")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val now = LocalDateTime.now()

                // ───── Part 1: load spools onto vehicle → resolve/create the Packing List ─────
                val distinctPlIds = scannedSpools.mapNotNull { it.packingListId }.distinct()
                val sameSinglePl = distinctPlIds.size == 1 && scannedSpools.all { it.packingListId != null }
                val scannedSpoolIds = scannedSpools.mapNotNull { it.spoolId.takeIf { id -> id != 0L } }.toSet()
                val existingPlSpoolIds = if (sameSinglePl)
                    ServiceLocator.smsPackingListSpoolDao.getSpoolIdsByPackingList(distinctPlIds[0]).toSet()
                else emptySet()
                val allSameSinglePl = sameSinglePl && scannedSpoolIds == existingPlSpoolIds

                val effectivePlId: Long
                val effectivePlName: String

                if (allSameSinglePl) {
                    effectivePlId = distinctPlIds[0]
                    effectivePlName = scannedSpools.first().packingListName ?: "PL-$effectivePlId"
                } else {
                    val newPlId = (ServiceLocator.smsPackingListDao.getMaxId() ?: 0L) + 1L
                    val project     = ServiceLocator.projectDao.getById(projectId)
                    val proj        = project?.project_code?.uppercase()?.trim().orEmpty()
                    val projectCode = project?.project_code.orEmpty()
                    val location    = ServiceLocator.configRepo.get("device_location")?.trim().orEmpty()
                    val position    = location.takeIf { it.isNotBlank() }?.let { ServiceLocator.smsPositionDao.getByCode(it) }
                    val posCode     = position?.code?.uppercase()?.trim().orEmpty()
                    val plate       = vehicle.license_plate.replace("-", "").replace(" ", "").uppercase()
                    val count       = if (position != null)
                        ServiceLocator.smsPackingListDao.countByProjectPositionVehicle(projectId, position.position_id, vehicle.vehicle_id)
                    else
                        ServiceLocator.smsPackingListDao.countByNamePrefix(projectId, listOfNotNull("PL", proj.ifBlank { null }, plate.ifBlank { null }).joinToString("-"))
                    val n           = "%03d".format(count + 1)
                    val plName      = if (posCode.isNotBlank()) "PL-$proj-$posCode-$plate-$n" else "PL-$proj-$plate-$n"
                    val totalWeight = scannedSpools.sumOf { it.weightKg ?: 0.0 }.takeIf { it > 0.0 }
                    val pl = SmsPackingListEntity(
                        packing_list_id    = newPlId,
                        project_id         = projectId,
                        packing_list_name  = plName,
                        vehicle_id         = vehicle.vehicle_id,
                        vehicle_plate      = vehicle.license_plate,
                        position_id        = position?.position_id,
                        packing_date       = now.toString(),
                        total_spools_count = scannedSpools.size,
                        total_weight_kg    = totalWeight,
                        synced             = false,
                        ready_to_send      = true
                    )
                    ServiceLocator.smsPackingListDao.insertAll(listOf(pl))

                    // Try to create the PL on the server right away so its spools can be
                    // linked server-side too — the background SyncService upload only
                    // creates the empty PL and never links spools.
                    var finalPlId = newPlId
                    var createdOnServer = false
                    try {
                        if (projectCode.isNotBlank()) {
                            val service = ServiceLocator.apiClient.getService()
                            val body = CreatePackingListRequest(
                                packingListName  = plName,
                                vehicle          = vehicle.license_plate,
                                vehicleId        = vehicle.vehicle_id,
                                position         = position?.name,
                                positionId       = position?.position_id,
                                packingDate      = now.toString(),
                                notes            = null,
                                createdBy        = "API",
                                projectCode      = projectCode,
                                totalSpoolsCount = scannedSpools.size
                            )
                            val resp = service.createPackingList(projectCode, body)
                            if (resp.isSuccessful) {
                                createdOnServer = true
                                val rawBody = resp.body()?.string().orEmpty()
                                val parsedId = parseCreatedPlId(rawBody)
                                if (parsedId != null && parsedId > 0L && parsedId != newPlId) {
                                    ServiceLocator.smsPackingListDao.deleteById(newPlId)
                                    ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(packing_list_id = parsedId, synced = true)))
                                    finalPlId = parsedId
                                } else {
                                    ServiceLocator.smsPackingListDao.markSynced(listOf(newPlId))
                                }
                            } else {
                                Log.w("SendPL", "PL server create failed: HTTP ${resp.code()}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SendPL", "PL server create error (offline?): ${e.message}")
                    }

                    val maxPlSpoolId = ServiceLocator.smsPackingListSpoolDao.getMaxId() ?: 0L
                    val plSpoolEntries = scannedSpools.filter { it.spoolId != 0L }.mapIndexed { idx, s ->
                        SmsPackingListSpoolEntity(
                            packing_list_spool_id = maxPlSpoolId + idx + 1,
                            packing_list_id       = finalPlId,
                            spool_id              = s.spoolId,
                            sequence_number       = idx + 1,
                            added_at              = now.toString()
                        )
                    }

                    val vacatedPlIds = scannedSpools
                        .mapNotNull { it.packingListId }
                        .filter { it != finalPlId }
                        .distinct()

                    scannedSpools.filter { it.spoolId != 0L }.forEachIndexed { idx, s ->
                        ServiceLocator.smsSpoolDao.updatePackingList(s.spoolId, finalPlId)
                        ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(s.spoolId)
                        if (createdOnServer) {
                            try {
                                ServiceLocator.apiClient.getService()
                                    .addSpoolToPackingList(projectCode, finalPlId, AssignSpoolRequest(s.spoolId, "API", idx + 1))
                            } catch (_: Exception) { /* offline – sync will retry PL upload, spools stay linked locally */ }
                        }
                    }
                    if (plSpoolEntries.isNotEmpty()) {
                        ServiceLocator.smsPackingListSpoolDao.insertAll(plSpoolEntries)
                    }

                    // The spools above just left their previous PL(s) — refresh those PLs'
                    // total_spools_count so their detail screen header matches the actual
                    // remaining spool list.
                    vacatedPlIds.forEach { oldPlId ->
                        val newCount = ServiceLocator.smsSpoolDao.getByPackingList(oldPlId).size
                        ServiceLocator.smsPackingListDao.getById(oldPlId)?.let { oldPl ->
                            ServiceLocator.smsPackingListDao.insertAll(listOf(oldPl.copy(total_spools_count = newCount, synced = false)))
                        }
                    }

                    effectivePlId = finalPlId
                    effectivePlName = plName
                }

                val prevLoadings = ServiceLocator.smsVehicleLoadingDao.getByVehicle(vehicle.vehicle_id, projectId)
                prevLoadings.forEach { prev ->
                    ServiceLocator.smsVehicleLoadingDao.deleteSpoolsByLoading(prev.loading_id)
                    ServiceLocator.smsVehicleLoadingDao.deleteById(prev.loading_id)
                }

                val loadingId = ServiceLocator.smsVehicleLoadingDao.insert(
                    SmsVehicleLoadingEntity(
                        vehicle_id    = vehicle.vehicle_id,
                        vehicle_plate = vehicle.license_plate,
                        project_id    = projectId,
                        created_at    = now.toString(),
                        synced        = false
                    )
                )
                ServiceLocator.smsVehicleLoadingDao.insertSpools(
                    scannedSpools.map { s ->
                        SmsVehicleLoadingSpoolEntity(
                            loading_id        = loadingId,
                            spool_id          = s.spoolId,
                            spool_code        = s.spoolCode,
                            spool_suffix      = s.spoolSuffix,
                            packing_list_id   = effectivePlId,
                            packing_list_name = effectivePlName
                        )
                    }
                )

                ServiceLocator.smsPackingListDao.setReadyToSend(effectivePlId, true)
                ServiceLocator.smsPackingListDao.setVehicle(effectivePlId, vehicle.vehicle_id, vehicle.license_plate)

                try {
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        ServiceLocator.apiClient.getService().setPackingListReadyToSend(projectCode, effectivePlId, true)
                    }
                } catch (_: Exception) { /* offline – local flag preserved, sync will retry */ }

                // ───── Part 2: send the loaded packing list (transfer record) ─────
                val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: "UNKNOWN"
                val sigData   = ""

                val prevTransfers = ServiceLocator.smsTransferDao.getSendByVehicle(vehicle.vehicle_id, projectId)
                if (prevTransfers.isNotEmpty()) {
                    val prevIds = prevTransfers.map { it.transfer_id }
                    ServiceLocator.smsTransferDao.deleteSpoolsByTransferIds(prevIds)
                    ServiceLocator.smsTransferDao.deleteByIds(prevIds)
                }

                val transferId = ServiceLocator.smsTransferDao.insert(
                    SmsTransferEntity(
                        transfer_type        = "SEND",
                        packing_list_id      = effectivePlId,
                        packing_list_name    = effectivePlName,
                        vehicle_id           = vehicle.vehicle_id,
                        vehicle_plate        = vehicle.license_plate,
                        origin_location      = location,
                        destination_location = destination,
                        signature_data       = sigData,
                        created_at           = now.toString(),
                        project_id           = projectId
                    )
                )

                val transferSpools = scannedSpools.filter { it.spoolId != 0L }
                ServiceLocator.smsTransferDao.insertSpools(
                    transferSpools.map { s ->
                        SmsTransferSpoolEntity(
                            transfer_id  = transferId,
                            spool_id     = s.spoolId,
                            spool_code   = s.spoolCode,
                            spool_suffix = s.spoolSuffix,
                            assignment   = null
                        )
                    }
                )

                transferSpools.forEach { s ->
                    ServiceLocator.smsSpoolDao.updateInTransit(s.spoolId, true)
                }

                ServiceLocator.smsPackingListDao.setReadyToSend(effectivePlId, false)

                // Position stays put until the receiving side confirms — only in_transit/on_route
                // flags move at send time. See ReceivePackingListFragment for the position update.
                val destPosition = ServiceLocator.smsPositionDao.getByCode(destination)
                if (destPosition == null) {
                    Log.w("SendPL", "onConfirmSend: no position found for code='$destination'")
                }

                ServiceLocator.smsVehicleDao.setOnRoute(vehicle.vehicle_id, destPosition?.position_id)

                // One GPS fix for the whole send batch — captured at the moment of confirm.
                val gps = GpsHelper.getCurrentLocation(requireContext())
                if (gps != null) {
                    val (lat, lon, acc) = gps
                    val capturedAt = GpsHelper.capturedAtNow()
                    val capturedBy = ServiceLocator.configRepo.get("device_name")
                    transferSpools.forEach { s ->
                        val loc = SmsSpoolLocationEntity(
                            spool_id       = s.spoolId,
                            latitude       = lat,
                            longitude      = lon,
                            gps_accuracy_m = acc,
                            captured_at    = capturedAt,
                            captured_by    = capturedBy
                        )
                        ServiceLocator.smsSpoolLocationDao.insert(loc)
                        ServiceLocator.smsSpoolLocationDao.pruneOldest(s.spoolId)
                    }
                }

                try {
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val resp = ServiceLocator.apiClient.getService()
                            .setVehicleOnRoute(projectCode, vehicle.vehicle_id, destPosition?.position_id)
                        Log.d("SendPL", "setVehicleOnRoute(vehicle=${vehicle.vehicle_id}, dest=${destPosition?.position_id}) → HTTP ${resp.code()} successful=${resp.isSuccessful}")
                    }
                } catch (e: Exception) {
                    Log.e("SendPL", "setVehicleOnRoute failed", e)
                }

                val activity = requireActivity() as? MainActivity

                // Block and upload immediately before navigating — prevents sync from
                // overwriting the just-recorded send state (vehicle on_route, spool positions).
                if (isAdded) {
                    btnConfirmSend.isEnabled = false
                    panelUploadProgress.visibility = android.view.View.VISIBLE
                    txtUploadStatus.text = getString(R.string.transfer_send_uploading)
                }
                val uploadResult = ServiceLocator.syncService.syncSmsUploads()
                if (isAdded) {
                    panelUploadProgress.visibility = android.view.View.GONE
                    btnConfirmSend.isEnabled = true
                }

                if (!isAdded) return@launch
                activity?.playSuccess()
                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.TRANSFERENCIA_ENVIADA,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_TRANSFERENCIA,
                    transferId, effectivePlName,
                    detail = "${vehicle.license_plate} → $destination",
                    projectId = projectId
                )
                val msg = if (uploadResult.success) R.string.transfer_send_success
                          else R.string.transfer_send_success_partial
                Toast.makeText(requireContext(), getString(msg), Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                Log.e("SendPL", "onConfirmSend FAILED", e)
                if (isAdded) {
                    btnConfirmSend.isEnabled = true
                    panelUploadProgress.visibility = android.view.View.GONE
                    Toast.makeText(requireContext(), getString(R.string.transfer_send_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class ScannedSpoolAdapter : RecyclerView.Adapter<ScannedSpoolAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val txtCode:   TextView    = view.findViewById(R.id.txtLoadSpoolCode)
            val txtPl:     TextView    = view.findViewById(R.id.txtLoadPlName)
            val btnRemove: ImageButton = view.findViewById(R.id.btnRemoveSpool)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_load_spool, parent, false))

        override fun getItemCount() = scannedSpools.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = scannedSpools[position]
            h.txtCode.text = item.displayCode
            h.txtPl.text   = item.packingListName ?: getString(R.string.load_spools_no_pl)
            h.btnRemove.setOnClickListener { removeSpoolAt(h.adapterPosition) }
        }
    }
}
