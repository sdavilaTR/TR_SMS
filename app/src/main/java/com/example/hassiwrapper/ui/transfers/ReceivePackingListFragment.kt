package com.example.hassiwrapper.ui.transfers

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolLocationEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.RouteStateUpdatePayload
import com.example.hassiwrapper.services.GpsHelper
import com.example.hassiwrapper.services.OutboxService
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class ReceivePackingListFragment : Fragment() {

    private data class SpoolReceive(
        val spool: SmsSpoolEntity,
        var confirmed: Boolean = false
    )

    /** A pickable sub-position. [subPositionId] is null only in the CSV fallback
     *  (when the position has no sub-position catalog seeded yet). */
    private data class AssignOption(val label: String, val subPositionId: Long?)

    // Panels
    private lateinit var panelScanVehicle: View
    private lateinit var panelSelectPl: View
    private lateinit var panelConfirmSpools: View

    // Panel A
    private lateinit var txtScannedVehicle: TextView
    private lateinit var etPlate: android.widget.AutoCompleteTextView
    private lateinit var btnConfirmVehicle: MaterialButton
    private lateinit var btnScanVehicle: MaterialButton

    // Panel B
    private lateinit var txtNoPls: TextView
    private lateinit var rvPackingLists: RecyclerView
    private lateinit var plAdapter: PlAdapter

    // Panel C
    private lateinit var txtSpoolsProgress: TextView
    private lateinit var txtBatchAssignmentLabel: TextView
    private lateinit var spinnerBatchAssignment: Spinner
    private lateinit var rvSpoolsToConfirm: RecyclerView
    private lateinit var btnScanSpool: MaterialButton
    private lateinit var btnConfirmReceive: MaterialButton
    private lateinit var spoolAdapter: SpoolReceiveAdapter

    private var selectedVehicle: SmsVehicleEntity? = null
    private var selectedPl: SmsPackingListEntity? = null
    private val spoolReceives = mutableListOf<SpoolReceive>()
    private var assignOptions: List<AssignOption> = emptyList()
    private var receivePositionId: Int? = null
    /** Sub-position picked once for the whole current batch — every confirmed spool in
     *  this scan session goes there (one stop = one destination), not per-spool anymore. */
    private var selectedBatchOption: AssignOption? = null

    /** True from the moment Confirm is pressed until the whole receive has finished writing.
     *
     *  Nothing is persisted until then: the vehicle, the packing list and the scanned spools live
     *  only in memory, so leaving before Confirm loses the progress but writes nothing. Once Confirm
     *  starts it is a long sequence of DB writes plus network calls that is NOT a transaction, and it
     *  runs on viewLifecycleOwner.lifecycleScope — leaving mid-way CANCELS it at the next suspension
     *  point, committing whatever it had already written and skipping the rest. That is exactly the
     *  half-saved receive that gets stuck in the database, so while this is true the screen refuses
     *  to be left. */
    private var submitting = false

    private val vehicleScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.trim() ?: return@registerForActivityResult
            handleVehicleScan(raw)
        }
    }

    private val spoolScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.trim() ?: return@registerForActivityResult
            handleSpoolScan(raw)
        }
    }

    // Receive-confirm captures a best-effort GPS fix; request location permission once up
    // front so it's not silently unavailable for the lifetime of the app (see GpsHelper).
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* best-effort — GpsHelper silently skips capture if denied */ }

    private var pendingCameraAction: (() -> Unit)? = null
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) pendingCameraAction?.invoke() }

    private fun launchScannerWithPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCameraAction = action
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_receive_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Salir por gesto y salir por el boton Atras pasan por el MISMO guardia: si no, cancelar la
        // operacion dependeria de con cual de los dos te fueras.
        (view as com.example.hassiwrapper.ui.common.SwipeBackNestedScrollView).onSwipeBack = { attemptLeave() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = attemptLeave()
            }
        )

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        panelScanVehicle   = view.findViewById(R.id.panelScanVehicle)
        panelSelectPl      = view.findViewById(R.id.panelSelectPl)
        panelConfirmSpools = view.findViewById(R.id.panelConfirmSpools)

        txtScannedVehicle  = view.findViewById(R.id.txtScannedVehicle)
        etPlate            = view.findViewById(R.id.etPlate)
        btnConfirmVehicle  = view.findViewById(R.id.btnConfirmVehicle)
        btnScanVehicle     = view.findViewById(R.id.btnScanVehicle)

        txtNoPls           = view.findViewById(R.id.txtNoPls)
        rvPackingLists     = view.findViewById(R.id.rvPackingLists)

        txtSpoolsProgress  = view.findViewById(R.id.txtSpoolsProgress)
        txtBatchAssignmentLabel = view.findViewById(R.id.txtBatchAssignmentLabel)
        spinnerBatchAssignment  = view.findViewById(R.id.spinnerBatchAssignment)
        rvSpoolsToConfirm  = view.findViewById(R.id.rvSpoolsToConfirm)
        btnScanSpool       = view.findViewById(R.id.btnScanSpool)
        btnConfirmReceive  = view.findViewById(R.id.btnConfirmReceive)

        plAdapter = PlAdapter()
        rvPackingLists.layoutManager = LinearLayoutManager(requireContext())
        rvPackingLists.adapter = plAdapter
        rvPackingLists.isNestedScrollingEnabled = false
        rvPackingLists.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        spoolAdapter = SpoolReceiveAdapter()
        rvSpoolsToConfirm.layoutManager = LinearLayoutManager(requireContext())
        rvSpoolsToConfirm.adapter = spoolAdapter
        rvSpoolsToConfirm.isNestedScrollingEnabled = false
        rvSpoolsToConfirm.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        btnScanVehicle.setOnClickListener {
            launchScannerWithPermission { vehicleScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java)) }
        }
        btnConfirmVehicle.setOnClickListener {
            val plate = etPlate.text?.toString()?.trim().orEmpty()
            if (plate.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.load_spools_enter_plate), Toast.LENGTH_SHORT).show()
            } else {
                handleVehicleScan(plate, isManualEntry = true)
            }
        }
        btnScanSpool.setOnClickListener {
            launchScannerWithPermission { spoolScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java)) }
        }
        btnConfirmReceive.setOnClickListener { onNextToConfirmReceive() }

        etPlate.threshold = 1
        etPlate.setOnItemClickListener { parent, _, pos, _ ->
            handleVehicleScan(parent.getItemAtPosition(pos) as String)
        }
        setupVehicleAutocomplete()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    when {
                        panelScanVehicle.visibility == View.VISIBLE   -> handleVehicleScan(raw.trim())
                        panelConfirmSpools.visibility == View.VISIBLE  -> handleSpoolScan(raw.trim())
                    }
                }
            }
        }

        loadAssignmentOptions()
    }

    private fun loadAssignmentOptions() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            val position  = ServiceLocator.smsPositionDao.getByCode(location)
            receivePositionId = position?.position_id

            // Prefer the real sub-position catalog for this position; fall back to the
            // legacy CSV (label-only, no sub_position_id) when none is seeded yet.
            val catalog = position?.position_id?.let {
                ServiceLocator.smsSubPositionDao.getByPosition(projectId, it)
            }.orEmpty()

            assignOptions = if (catalog.isNotEmpty()) {
                catalog.map { AssignOption(it.full_path.ifBlank { it.name.ifBlank { it.code } }, it.sub_position_id) }
            } else when (location) {
                "LAYDOWN" -> (ServiceLocator.configRepo.get("laydown_sections") ?: "1A,2A,1B,2B,1C,2C,1D,2D")
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }.map { AssignOption(it, null) }
                "SITE" -> (ServiceLocator.configRepo.get("site_units") ?: "1,2,3,4")
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }.map { AssignOption(it, null) }
                else -> emptyList()
            }
        }
    }

    private fun setupVehicleAutocomplete() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val plates = ServiceLocator.smsVehicleDao.getByProject(projectId).map { it.license_plate }
            etPlate.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, plates))
        }
    }

    private fun handleVehicleScan(raw: String, isManualEntry: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            val qr = parseQr(raw)
            val vehicle = when (qr) {
                is QrResult.VehicleBadge -> {
                    Toast.makeText(requireContext(), getString(R.string.qr_scanner_result_badge_unsupported), Toast.LENGTH_LONG).show()
                    return@launch
                }
                is QrResult.VehicleId    -> ServiceLocator.smsVehicleDao.getById(qr.id)
                is QrResult.VehiclePlate -> ServiceLocator.smsVehicleDao.getByLicensePlate(qr.plate)
                else -> ServiceLocator.smsVehicleDao.getByLicensePlate(raw)
            }

            if (vehicle == null) {
                txtScannedVehicle.text = raw
                txtScannedVehicle.visibility = View.VISIBLE
                if (isManualEntry) {
                    offerUnregisteredVehicleIncident(raw)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_not_found), Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            val currentPosition = ServiceLocator.smsPositionDao.getByCode(location)

            if (vehicle.project_id != projectId) {
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_wrong_project), Toast.LENGTH_LONG).show()
                return@launch
            }
            android.util.Log.d("ReceiveDebug", "handleVehicleScan: location='$location' currentPosition=${currentPosition?.position_id} '${currentPosition?.code}' vehicle.on_route=${vehicle.on_route} vehicle.destination=${vehicle.destination}")
            if (!vehicle.on_route) {
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_not_on_route), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (currentPosition != null && vehicle.destination != currentPosition.position_id) {
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_wrong_destination), Toast.LENGTH_LONG).show()
                return@launch
            }

            selectedVehicle = vehicle
            txtScannedVehicle.text = getString(R.string.transfer_vehicle_confirmed, vehicle.license_plate)
            txtScannedVehicle.visibility = View.VISIBLE
            loadPackingListsForVehicle(vehicle)
        }
    }

    /** Manual plate entry didn't resolve to a known vehicle — offer to log it as an incident
     *  so it can be picked up for registration, instead of silently rejecting the input. */
    private fun offerUnregisteredVehicleIncident(plate: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.transfer_unregistered_vehicle_title))
            .setMessage(getString(R.string.transfer_unregistered_vehicle_message, plate))
            .setPositiveButton(R.string.transfer_unregistered_vehicle_create_incident) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    ServiceLocator.smsIncidentService.createVehicleNotRegisteredIncident(plate)
                    Toast.makeText(requireContext(), getString(R.string.transfer_unregistered_vehicle_incident_created), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadPackingListsForVehicle(vehicle: SmsVehicleEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            android.util.Log.d("ReceiveDebug", "loadPackingListsForVehicle: vehicle_id=${vehicle.vehicle_id} plate=${vehicle.license_plate} on_route=${vehicle.on_route} destination=${vehicle.destination}")

            // Same-device: PLs with in_transit spools (set by local Send confirmation).
            // Cross-device: EVERY active PL on this vehicle that still has spools — NOT filtered
            // by ready_to_send. That flag was the cause of sent PLs not showing at receive time:
            // it defaults to 0 on the server INSERT and is only flipped to 1 by a follow-up PUT
            // that silently fails offline, so a real in-transit PL could arrive invisible. The
            // 1-active-PL-per-vehicle rule (unique index) means getByVehicle returns at most one,
            // and a fully-received PL is is_active=0 so it drops out on its own; the count guard
            // is a belt-and-suspenders against a not-yet-cleaned empty PL.
            val byInTransit = ServiceLocator.smsPackingListDao.getWithInTransitSpoolsByVehicle(vehicle.vehicle_id)
            val byVehicle = ServiceLocator.smsPackingListDao.getByVehicle(vehicle.vehicle_id)
                .filter { (it.total_spools_count ?: 0) > 0 }
            val pls = (byInTransit + byVehicle).distinctBy { it.packing_list_id }

            panelScanVehicle.visibility = View.GONE
            panelSelectPl.visibility = View.VISIBLE

            if (pls.isEmpty()) {
                txtNoPls.visibility = View.VISIBLE
                rvPackingLists.visibility = View.GONE
            } else {
                txtNoPls.visibility = View.GONE
                rvPackingLists.visibility = View.VISIBLE
                plAdapter.items = pls
                plAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun onPlSelected(pl: SmsPackingListEntity) {
        selectedPl = pl
        showSpoolPanel(pl)
    }

    private fun showSpoolPanel(pl: SmsPackingListEntity) {
        panelSelectPl.visibility = View.GONE
        panelConfirmSpools.visibility = View.VISIBLE

        selectedBatchOption = null
        if (assignOptions.isNotEmpty()) {
            txtBatchAssignmentLabel.visibility = View.VISIBLE
            spinnerBatchAssignment.visibility = View.VISIBLE
            val labels = listOf(getString(R.string.transfer_receive_subposition_placeholder)) + assignOptions.map { it.label }
            val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerBatchAssignment.adapter = spinnerAdapter
            spinnerBatchAssignment.setSelection(0)
            spinnerBatchAssignment.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    selectedBatchOption = if (pos == 0) null else assignOptions[pos - 1]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        } else {
            txtBatchAssignmentLabel.visibility = View.GONE
            spinnerBatchAssignment.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val inTransit = ServiceLocator.smsSpoolDao.getInTransitByPackingList(pl.packing_list_id)
            val spools = inTransit.ifEmpty {
                ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
            }
            spoolReceives.clear()
            spoolReceives.addAll(spools.map { SpoolReceive(it) })
            spoolAdapter.notifyDataSetChanged()
            updateProgress()
        }
    }

    private fun handleSpoolScan(raw: String) {
        val pl = selectedPl ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val qr = parseQr(raw)
            val (code, suffix) = when (qr) {
                is QrResult.Spool -> qr.spoolCode to qr.spoolSuffix
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

            if (spool == null || spool.packing_list_id != pl.packing_list_id) {
                Toast.makeText(requireContext(), getString(R.string.transfer_spool_not_in_list), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val entry = spoolReceives.find { it.spool.spool_id == spool.spool_id }
            if (entry == null) {
                Toast.makeText(requireContext(), getString(R.string.transfer_spool_not_in_list), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (entry.confirmed) {
                Toast.makeText(requireContext(), getString(R.string.transfer_spool_already_confirmed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            entry.confirmed = true
            val idx = spoolReceives.indexOf(entry)
            spoolAdapter.notifyItemChanged(idx)
            updateProgress()
            Toast.makeText(requireContext(), getString(R.string.transfer_spool_confirmed, spool.displayCode), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateProgress() {
        val total     = spoolReceives.size
        val confirmed = spoolReceives.count { it.confirmed }
        txtSpoolsProgress.text = getString(R.string.transfer_spools_progress, confirmed, total)
    }

    private fun onNextToConfirmReceive() {
        if (spoolReceives.none { it.confirmed }) {
            Toast.makeText(requireContext(), getString(R.string.transfer_receive_no_spools_scanned), Toast.LENGTH_SHORT).show()
            return
        }
        if (assignOptions.isNotEmpty() && selectedBatchOption == null) {
            Toast.makeText(requireContext(), getString(R.string.transfer_receive_subposition_required), Toast.LENGTH_SHORT).show()
            return
        }
        onConfirmReceive()
    }

    private fun onConfirmReceive() {
        val pl      = selectedPl ?: return
        val vehicle = selectedVehicle ?: return
        // Un segundo toque mientras la primera confirmacion sigue en vuelo duplicaba la
        // transferencia entera, con sus filas de spools y sus subidas.
        if (submitting) return
        submitting = true
        btnConfirmReceive.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: "UNKNOWN"
            val now       = LocalDateTime.now().toString()
            val sigData   = ""

            val transferId = ServiceLocator.smsTransferDao.insert(
                SmsTransferEntity(
                    transfer_type        = "RECEIVE",
                    packing_list_id      = pl.packing_list_id,
                    packing_list_name    = pl.packing_list_name,
                    vehicle_id           = vehicle.vehicle_id,
                    vehicle_plate        = vehicle.license_plate,
                    origin_location      = "UNKNOWN",
                    destination_location = location,
                    signature_data       = sigData,
                    created_at           = now,
                    project_id           = projectId
                )
            )

            val confirmedSpools = spoolReceives.filter { it.confirmed }
            val option = selectedBatchOption
            val label  = option?.label

            ServiceLocator.smsTransferDao.insertSpools(
                confirmedSpools.map { sr ->
                    SmsTransferSpoolEntity(
                        transfer_id  = transferId,
                        spool_id     = sr.spool.spool_id,
                        spool_code   = sr.spool.spool_code,
                        spool_suffix = sr.spool.spool_suffix,
                        assignment   = label
                    )
                }
            )

            val receivePosition = ServiceLocator.smsPositionDao.getByCode(location)
            val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
            confirmedSpools.forEach { sr ->
                val zone = if (location == "LAYDOWN") location else sr.spool.zone
                val unit = if (location == "SITE") label else sr.spool.assigned_unit
                ServiceLocator.smsSpoolDao.updateZoneAndUnit(sr.spool.spool_id, zone, unit)
                if (receivePosition != null) {
                    ServiceLocator.smsSpoolDao.updatePosition(sr.spool.spool_id, receivePosition.position_id)
                }
                ServiceLocator.smsSpoolDao.updateSubPosition(sr.spool.spool_id, option?.subPositionId)
                // Push position + sub-position to the server (authoritative PUT status-flags),
                // whenever a position was actually resolved — sub-position may legitimately be
                // null (no catalog / not picked). Best-effort, never blocks receive.
                if (!projectCode.isNullOrBlank() && receivePosition != null) {
                    ServiceLocator.syncService.uploadSpoolStatusFlags(
                        projectCode, sr.spool.spool_id, receivePosition.position_id, option?.subPositionId
                    )
                }
            }

            // One GPS fix for the whole receive batch — captured at the moment of confirm
            val gps = GpsHelper.getCurrentLocation(requireContext())
            if (gps != null) {
                val (lat, lon, acc) = gps
                val capturedAt = GpsHelper.capturedAtNow()
                val capturedBy = ServiceLocator.configRepo.get("device_name")
                confirmedSpools.forEach { sr ->
                    val loc = SmsSpoolLocationEntity(
                        spool_id       = sr.spool.spool_id,
                        latitude       = lat,
                        longitude      = lon,
                        gps_accuracy_m = acc,
                        captured_at    = capturedAt,
                        captured_by    = capturedBy
                    )
                    ServiceLocator.smsSpoolLocationDao.insert(loc)
                    ServiceLocator.smsSpoolLocationDao.pruneOldest(sr.spool.spool_id)
                }
            }

            // A truck can unload in stages across several sub-positions. Move the PL to the
            // destination position on every batch; once nothing is left in transit for it, mark it
            // DELIVERED instead of deleting it — the PL survives as a delivery record (deleting it
            // was the reported "PL desaparece" bug, and nobody on the project wanted it gone).
            if (receivePosition != null) {
                ServiceLocator.smsPackingListDao.updatePosition(pl.packing_list_id, receivePosition.position_id, receivePosition.code)
            }

            val remainingInPl = ServiceLocator.smsSpoolDao.getInTransitByPackingList(pl.packing_list_id)
            val plDelivered = remainingInPl.isEmpty()

            if (plDelivered) {
                // Keep the PL as a delivery record. Its spools STAY linked as the manifest — they're
                // already positioned at destination + in_transit cleared above, so nothing is
                // stranded (the PL is active and at destination, so its spools show there). Clearing
                // the vehicle frees the truck for its next load AND drops the PL out of the Receive
                // screen (resolved by vehicle), with no dependency on the fragile ready_to_send flag.
                ServiceLocator.smsPackingListDao.clearVehicleAndDeliver(pl.packing_list_id)
                // App-local only — never touched by server sync, so it survives the 60 s auto-sync
                // REPLACE of sms_packing_list. Drives the Actuales/Históricos split in
                // PackingListsFragment: without this the delivered PL kept showing as "current".
                ServiceLocator.smsPackingListHistoricalDao.markHistorical(
                    com.example.hassiwrapper.data.db.entities.SmsPackingListHistoricalEntity(
                        packing_list_id = pl.packing_list_id,
                        marked_at = now
                    )
                )
                if (pl.synced && !projectCode.isNullOrBlank()) {
                    // Persist the delivery as a PL UPDATE (destination position + vehicle=null),
                    // offline-safe via the outbox — same path as EditPackingListFragment.saveEdits.
                    // Send the actual current count so the server's denormalised total_spools_count
                    // isn't stomped to a stale value; empty `spools` is ignored by UpdatePackingListAsync
                    // (it only writes the PL row), so the server-side manifest is preserved.
                    val actualCount = ServiceLocator.smsSpoolDao.countByPackingList(pl.packing_list_id)
                    ServiceLocator.outboxService.enqueue(
                        com.example.hassiwrapper.services.OutboxService.Entity.PACKING_LIST,
                        com.example.hassiwrapper.services.OutboxService.Op.UPDATE,
                        pl.packing_list_id, projectId,
                        payload = com.example.hassiwrapper.network.dto.UpdatePackingListRequest(
                            packingListId    = pl.packing_list_id,
                            packingListName  = pl.packing_list_name,
                            vehicle          = null,
                            position         = receivePosition?.name,
                            positionId       = receivePosition?.position_id,
                            packingDate      = pl.packing_date.takeIf { it.isNotBlank() },
                            notes            = pl.notes,
                            createdBy        = pl.created_by,
                            updatedBy        = null,
                            projectCode      = projectCode,
                            totalSpoolsCount = actualCount
                        )
                    )
                }
                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.PL_ENTREGADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_PL,
                    pl.packing_list_id, pl.packing_list_name, projectId = projectId
                )
            }

            val remainingInVehicle = ServiceLocator.smsSpoolDao.countInTransitByVehicle(vehicle.vehicle_id)
            if (remainingInVehicle == 0) {
                ServiceLocator.smsVehicleDao.setOffRoute(vehicle.vehicle_id)
                ServiceLocator.outboxService.enqueue(
                    entityType = OutboxService.Entity.ROUTE_STATE,
                    opType = OutboxService.Op.UPDATE,
                    localEntityId = vehicle.vehicle_id,
                    projectId = projectId,
                    payload = RouteStateUpdatePayload(onRoute = false, destinationId = null)
                )
            }

            activity?.lifecycleScope?.launch { ServiceLocator.syncService.syncSmsUploads() }

            if (!isAdded) return@launch
            (requireActivity() as? MainActivity)?.playSuccess()
            ServiceLocator.auditLogService.log(
                com.example.hassiwrapper.services.AuditLogService.TRANSFERENCIA_RECIBIDA,
                com.example.hassiwrapper.services.AuditLogService.ENTITY_TRANSFERENCIA,
                transferId, pl.packing_list_name,
                detail = "${vehicle.license_plate} → $location",
                projectId = projectId
            )
            val msg = if (plDelivered) getString(R.string.transfer_receive_success_complete)
                      else getString(R.string.transfer_receive_success_partial, confirmedSpools.size, label ?: location)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            resetToScanVehicle()
            } finally {
                // En finally y no al final del cuerpo: si la corrutina se cancela o algo revienta a
                // mitad, el flag tiene que bajar igual o la pantalla se queda bloqueada sin salida.
                submitting = false
                if (isAdded) btnConfirmReceive.isEnabled = true
            }
        }
    }

    /** ¿Hay algo que perder? La operacion ha empezado en cuanto se ha resuelto un vehiculo: a partir
     *  de ahi hay una lista elegida y/o spools escaneados que solo viven en memoria. En el panel
     *  inicial, con la matricula aun sin resolver, no hay progreso y salir no cuesta nada. */
    private fun hasProgress(): Boolean =
        selectedVehicle != null || selectedPl != null || spoolReceives.any { it.confirmed }

    /** Unico punto de salida de la pantalla, para el boton Atras y para el gesto.
     *
     *  Con la operacion a medias pide confirmacion en vez de irse sin mas: un toque accidental en
     *  Atras tiraba todo el progreso sin avisar y obligaba a rehacer el escaneo entero. */
    private fun attemptLeave() {
        if (submitting) {
            Toast.makeText(requireContext(), getString(R.string.transfer_busy_wait), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasProgress()) {
            findNavController().navigateUp()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.transfer_cancel_title)
            .setMessage(R.string.transfer_cancel_message)
            // El boton negativo es el de quedarse: es el que cae bajo el pulgar por defecto y el que
            // no destruye nada, asi que un segundo toque accidental no confirma la cancelacion.
            .setNegativeButton(R.string.transfer_cancel_dismiss, null)
            .setPositiveButton(R.string.transfer_cancel_confirm) { _, _ ->
                discardOperation()
                findNavController().navigateUp()
            }
            .show()
    }

    /** Tira el estado en memoria de la operacion. No hay nada que deshacer en la base de datos:
     *  hasta Confirmar no se ha escrito ni una fila. */
    private fun discardOperation() {
        selectedVehicle = null
        selectedPl = null
        selectedBatchOption = null
        spoolReceives.clear()
    }

    /** Back to Panel A so the operator can scan the vehicle again at the next sub-position
     *  stop — the truck may still be carrying spools for other destinations. */
    private fun resetToScanVehicle() {
        selectedVehicle = null
        selectedPl = null
        selectedBatchOption = null
        spoolReceives.clear()
        spoolAdapter.notifyDataSetChanged()
        txtScannedVehicle.text = ""
        txtScannedVehicle.visibility = View.GONE
        etPlate.setText("")
        panelConfirmSpools.visibility = View.GONE
        panelSelectPl.visibility = View.GONE
        panelScanVehicle.visibility = View.VISIBLE
    }

    private inner class PlAdapter : RecyclerView.Adapter<PlAdapter.VH>() {
        var items: List<SmsPackingListEntity> = emptyList()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val txtName:       TextView = view.findViewById(R.id.txtPlName)
            val txtVehicle:    TextView = view.findViewById(R.id.txtPlVehicle)
            val txtSpoolCount: TextView = view.findViewById(R.id.txtPlSpoolCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transfer_pl, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val pl = items[position]
            h.txtName.text       = pl.packing_list_name
            h.txtVehicle.text    = getString(R.string.transfer_vehicle_label, pl.vehicle_plate ?: "—")
            h.txtSpoolCount.text = getString(R.string.spools_count_format, pl.total_spools_count ?: 0)
            h.itemView.setOnClickListener { onPlSelected(pl) }
        }
    }

    private inner class SpoolReceiveAdapter : RecyclerView.Adapter<SpoolReceiveAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imgCheck: ImageView = view.findViewById(R.id.imgCheckMark)
            val txtCode:  TextView  = view.findViewById(R.id.txtSpoolCode)
            val spinner:  Spinner   = view.findViewById(R.id.spinnerAssignment)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_receive_spool, parent, false))

        override fun getItemCount() = spoolReceives.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val sr = spoolReceives[position]
            h.txtCode.text = sr.spool.displayCode
            h.imgCheck.setImageResource(
                if (sr.confirmed) android.R.drawable.checkbox_on_background
                else android.R.drawable.checkbox_off_background
            )
            // Assignment is now picked once per batch (see spinnerBatchAssignment), not per spool.
            h.spinner.visibility = View.GONE
        }
    }
}
