package com.example.hassiwrapper.ui.packinglists

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
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
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.example.hassiwrapper.network.dto.CreatePackingListRequest
import com.example.hassiwrapper.network.dto.UpdatePackingListRequest
import com.example.hassiwrapper.services.GpsHelper
import com.example.hassiwrapper.services.OutboxService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NewPackingListFragment : Fragment() {

    private lateinit var txtNamePreview: TextView
    private lateinit var tilPosition: TextInputLayout
    private lateinit var tilVehicle: TextInputLayout
    private lateinit var actvPosition: AutoCompleteTextView
    private lateinit var actvVehicle: AutoCompleteTextView
    private lateinit var etWedge: EditText
    private lateinit var etNotes: TextInputEditText
    private lateinit var btnAddSpools: MaterialButton
    private lateinit var btnScanCamera: MaterialButton
    private lateinit var btnSave: MaterialButton

    private val wedgeHandler = Handler(Looper.getMainLooper())
    private val wedgeTrigger = Runnable {
        val text = etWedge.text?.toString()?.trim().orEmpty()
        etWedge.setText("")
        if (!etNotes.hasFocus()) etWedge.requestFocus()
        if (text.isNotBlank()) handleScanForNewPL(text)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val code = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)
            code?.let { handleScanForNewPL(it.trim()) }
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    private var projectId: Int = 6
    private var projectName: String = ""
    private var projectCode: String = ""

    private var preselectedVehicleId: Long = 0L
    private var preselectedVehiclePlate: String? = null

    private var selectedPositionId: Int? = null
    private var selectedPositionCode: String = ""
    private var selectedVehicleId: Long? = null
    private var selectedVehiclePlate: String? = null

    private var availableSpools: List<SmsSpoolEntity> = emptyList()
    private val selectedSpoolIds = mutableSetOf<Long>()
    /** spoolId -> its current packing list, for spools scanned "move to this PL" during
     *  creation. Detach from the old PL is deferred to [savePackingList] — if the user
     *  cancels out of creating this PL, the spool stays untouched in its original one. */
    private val movedFromPlIds = mutableMapOf<Long, Long>()

    private val notesHandler = Handler(Looper.getMainLooper())
    private var suppressNotesTrigger = false
    private var scanIntercepted = false
    private val clearScanIntercepted = Runnable { scanIntercepted = false }
    private val notesScanTrigger = Runnable {
        val current = etNotes.text?.toString().orEmpty()
        val (scanText, cleaned) = extractSpoolQr(current)
        if (scanText != null) {
            suppressNotesTrigger = true
            etNotes.setText(cleaned)
            if (cleaned.isNotEmpty()) etNotes.setSelection(cleaned.length)
            suppressNotesTrigger = false
            handleScanForNewPL(scanText)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = { findNavController().navigateUp() }
        etWedge        = view.findViewById(R.id.etNewPLWedge)
        txtNamePreview = view.findViewById(R.id.txtPLNamePreview)
        tilPosition    = view.findViewById(R.id.tilPLPosition)
        tilVehicle     = view.findViewById(R.id.tilPLVehicle)
        actvPosition   = view.findViewById(R.id.actvPLPosition)
        actvVehicle    = view.findViewById(R.id.actvPLVehicle)
        etNotes        = view.findViewById(R.id.etPLNotes)
        btnAddSpools   = view.findViewById(R.id.btnAddSpools)
        btnScanCamera  = view.findViewById(R.id.btnScanCameraPL)
        btnSave        = view.findViewById(R.id.btnSavePL)

        etWedge.showSoftInputOnFocus = false
        etWedge.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if ((s?.length ?: 0) > 0) {
                    wedgeHandler.removeCallbacks(wedgeTrigger)
                    wedgeHandler.postDelayed(wedgeTrigger, 300)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etWedge.requestFocus()

        etNotes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) wedgeHandler.post { etWedge.requestFocus() }
        }

        preselectedVehicleId    = arguments?.getLong("vehicleId", 0L) ?: 0L
        preselectedVehiclePlate = arguments?.getString("vehiclePlate")

        // InputFilter intercepts QR delivery to etNotes (DataWedge IME mode or dual mode).
        // scanIntercepted flag blocks subsequent chunks of the same scan from writing to Notes.
        etNotes.filters = etNotes.filters + InputFilter { source, start, end, _, _, _ ->
            val incoming = source.subSequence(start, end).toString()
            val stripped = incoming.trimStart { it == '﻿' || it.isWhitespace() }
            val upper = stripped.uppercase()
            when {
                upper.startsWith("JAFURAH PACKING LIST") || upper.startsWith("RIYAS PACKING LIST") -> {
                    scanIntercepted = true
                    notesHandler.removeCallbacks(clearScanIntercepted)
                    notesHandler.postDelayed(clearScanIntercepted, 500)
                    notesHandler.post { if (isAdded) handleScanForNewPL(stripped) }
                    "" // reject first chunk
                }
                scanIntercepted -> "" // reject all subsequent chunks of same scan
                else -> null // accept normal user typing
            }
        }

        etNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressNotesTrigger) {
                    notesHandler.removeCallbacks(notesScanTrigger)
                    notesHandler.postDelayed(notesScanTrigger, 300)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnAddSpools.setOnClickListener { showSpoolPickerDialog() }
        btnScanCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) launchCamera()
            else requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        btnSave.setOnClickListener { savePackingList() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    handleScanForNewPL(raw.trim())
                }
            }
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (!etNotes.hasFocus()) etWedge.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        notesHandler.removeCallbacks(notesScanTrigger)
        wedgeHandler.removeCallbacks(wedgeTrigger)
    }

    private fun extractSpoolQr(text: String): Pair<String?, String> {
        val upper = text.uppercase()
        for (prefix in listOf("JAFURAH PACKING LIST", "RIYAS PACKING LIST")) {
            val idx = upper.indexOf(prefix)
            if (idx >= 0) {
                val before = text.substring(0, idx).trimEnd()
                val scan   = text.substring(idx).trim()
                return Pair(scan, before)
            }
        }
        return Pair(null, text)
    }

    private fun showInfoDialog(message: String) {
        val dialog = AlertDialog.Builder(requireContext())
            .setMessage(message)
            .create()
        dialog.show()
        requireView().postDelayed({ if (isAdded && dialog.isShowing) dialog.dismiss() }, 2600)
    }

    private fun handleScanForNewPL(raw: String) {
        val result = parseQr(raw)
        if (result !is QrResult.Spool) return
        viewLifecycleOwner.lifecycleScope.launch {
            val pid = ServiceLocator.configRepo.getInt("selected_project_id") ?: projectId
            val spool = if (result.spoolSuffix != null)
                ServiceLocator.smsSpoolDao.findByCodeAndSuffix(pid, result.spoolCode, result.spoolSuffix)
            else
                ServiceLocator.smsSpoolDao.findByCode(pid, result.spoolCode)
            if (spool == null) {
                Toast.makeText(requireContext(), getString(R.string.pl_scan_spool_not_found), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (spool.spool_id in selectedSpoolIds) {
                Toast.makeText(requireContext(), getString(R.string.new_pl_scan_spool_already_selected, spool.displayCode), Toast.LENGTH_SHORT).show()
                return@launch
            }
            GpsHelper.captureAndSaveSpoolLocation(requireContext(), spool.spool_id)
            if (spool.packing_list_id != null) {
                val oldPlId = spool.packing_list_id
                val pl = ServiceLocator.smsPackingListDao.getById(oldPlId)
                val plName = pl?.packing_list_name?.ifBlank { "PL $oldPlId" } ?: "PL $oldPlId"
                confirmMoveSpool(spool, oldPlId, plName)
                return@launch
            }
            selectedSpoolIds.add(spool.spool_id)
            updateSpoolsButton()
            Toast.makeText(requireContext(), getString(R.string.new_pl_scan_spool_added, spool.displayCode), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmMoveSpool(spool: SmsSpoolEntity, oldPlId: Long, oldPlName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pl_move_spool_title))
            .setMessage(getString(R.string.pl_move_spool_msg, spool.displayCode, oldPlName))
            .setPositiveButton(android.R.string.ok) { _, _ -> markSpoolForMove(spool, oldPlId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Only records intent — no DB/outbox writes here. The actual detach from [oldPlId] happens
     *  in [savePackingList], so cancelling out of this screen leaves the spool untouched in its
     *  original Packing List instead of stranding it unassigned from both. */
    private fun markSpoolForMove(spool: SmsSpoolEntity, oldPlId: Long) {
        movedFromPlIds[spool.spool_id] = oldPlId
        selectedSpoolIds.add(spool.spool_id)
        updateSpoolsButton()
        Toast.makeText(requireContext(), getString(R.string.pl_move_spool_done, spool.displayCode), Toast.LENGTH_SHORT).show()
    }

    /** Queue a PL UPDATE op carrying the old list's decremented spool count so the server total stays in sync. */
    private suspend fun enqueueOldPlCountUpdate(oldPlId: Long, projId: Int, newCount: Int) {
        val pl = ServiceLocator.smsPackingListDao.getById(oldPlId) ?: return
        val projCode = ServiceLocator.projectDao.getById(projId)?.project_code.orEmpty()
        val positionName = pl.position_id?.let { pid ->
            ServiceLocator.smsPositionDao.getAll().find { it.position_id == pid }?.name
        }
        ServiceLocator.outboxService.enqueue(
            OutboxService.Entity.PACKING_LIST, OutboxService.Op.UPDATE, oldPlId, projId,
            payload = UpdatePackingListRequest(
                packingListId    = oldPlId,
                packingListName  = pl.packing_list_name,
                vehicle          = pl.vehicle_plate,
                position         = positionName,
                positionId       = pl.position_id,
                packingDate      = pl.packing_date.takeIf { it.isNotBlank() },
                notes            = pl.notes,
                createdBy        = pl.created_by,
                updatedBy        = null,
                projectCode      = projCode,
                totalSpoolsCount = newCount
                // rowVersion intentionally omitted — see EditPackingListFragment.saveEdits.
            )
        )
    }

    private fun launchCamera() {
        cameraLauncher.launch(
            Intent(requireContext(), CustomScannerActivity::class.java)
                .putExtra(CustomScannerActivity.EXTRA_FRONT_CAMERA, false)
        )
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val project = ServiceLocator.projectDao.getById(projectId)
            projectName = project?.project_name.orEmpty()
            projectCode = project?.project_code.orEmpty()

            val positions = ServiceLocator.smsPositionDao.getAll()
            val vehicles  = ServiceLocator.smsVehicleDao.getByProject(projectId)
            availableSpools = ServiceLocator.smsSpoolDao.getWithoutPackingList(projectId)

            setupPositionDropdown(positions)
            setupVehicleDropdown(vehicles)
            updateNamePreview()
        }
    }

    private fun setupPositionDropdown(positions: List<SmsPositionEntity>) {
        val labels = positions.map { it.name.ifBlank { it.code } }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        actvPosition.setAdapter(adapter)
        actvPosition.setOnItemClickListener { _, _, pos, _ ->
            val p = positions[pos]
            selectedPositionId   = p.position_id
            selectedPositionCode = p.code.ifBlank { p.name }
            tilPosition.error    = null
            updateNamePreview()
            etWedge.requestFocus()
        }
    }

    private fun setupVehicleDropdown(vehicles: List<SmsVehicleEntity>) {
        val labels = vehicles.map { v ->
            v.vehicle_name?.takeIf { it.isNotBlank() }?.let { "$it – ${v.license_plate}" } ?: v.license_plate
        }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        actvVehicle.setAdapter(adapter)

        if (preselectedVehicleId > 0L) {
            val idx = vehicles.indexOfFirst { it.vehicle_id == preselectedVehicleId }
            if (idx >= 0) {
                val v = vehicles[idx]
                selectedVehicleId    = v.vehicle_id
                selectedVehiclePlate = v.license_plate
                actvVehicle.setText(labels[idx], false)
                updateNamePreview()
            }
        }

        actvVehicle.setOnItemClickListener { _, _, pos, _ ->
            val v = vehicles[pos]
            selectedVehicleId    = v.vehicle_id
            selectedVehiclePlate = v.license_plate
            updateNamePreview()
            etWedge.requestFocus()
        }
        actvVehicle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && actvVehicle.text.isNullOrBlank()) {
                selectedVehicleId    = null
                selectedVehiclePlate = null
                updateNamePreview()
            }
        }
    }

    private fun updateNamePreview() {
        if (selectedPositionId == null) {
            txtNamePreview.text = "—"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            txtNamePreview.text = buildName()
        }
    }

    private suspend fun buildName(): String {
        val posCode = selectedPositionCode.uppercase().trim()
        val proj    = projectCode.uppercase().trim()
        val plate   = selectedVehiclePlate?.uppercase()?.trim()?.replace("-", "")

        val count = if (selectedVehicleId != null) {
            ServiceLocator.smsPackingListDao.countByProjectPositionVehicle(
                projectId, selectedPositionId!!, selectedVehicleId!!
            )
        } else {
            ServiceLocator.smsPackingListDao.countByProjectPositionNoVehicle(
                projectId, selectedPositionId!!
            )
        }

        val n = "%03d".format(count + 1)
        return if (plate != null)
            "PL-$proj-$posCode-$plate-$n"
        else
            "PL-$proj-$posCode-$n"
    }

    private fun showSpoolPickerDialog() {
        if (availableSpools.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.new_packing_list_no_spools_available), Toast.LENGTH_SHORT).show()
            return
        }
        val labels      = availableSpools.map { it.displayCode }.toTypedArray()
        val checked     = BooleanArray(availableSpools.size) { availableSpools[it].spool_id in selectedSpoolIds }
        val tempSelected = selectedSpoolIds.toMutableSet()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.new_packing_list_section_spools))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val id = availableSpools[which].spool_id
                if (isChecked) tempSelected.add(id) else tempSelected.remove(id)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedSpoolIds.clear()
                selectedSpoolIds.addAll(tempSelected)
                updateSpoolsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSpoolsButton() {
        btnAddSpools.text = if (selectedSpoolIds.isEmpty())
            getString(R.string.new_packing_list_btn_add_spools)
        else
            getString(R.string.new_packing_list_spools_selected, selectedSpoolIds.size)
    }

    private fun savePackingList() {
        tilPosition.error = null
        if (selectedPositionId == null) {
            tilPosition.error = getString(R.string.new_packing_list_error_field_required)
            return
        }
        btnSave.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val plId  = ServiceLocator.outboxService.nextTempPackingListId()
                val name  = buildName()
                val now   = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val notes = etNotes.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                val pl = SmsPackingListEntity(
                    packing_list_id   = plId,
                    project_id        = projectId,
                    packing_list_name = name,
                    vehicle_id        = selectedVehicleId,
                    vehicle_plate     = selectedVehiclePlate,
                    position_id       = selectedPositionId,
                    packing_date      = today,
                    total_spools_count = selectedSpoolIds.size,
                    notes             = notes,
                    is_active         = true,
                    created_at        = now,
                    created_by        = "API",
                    synced            = false
                )
                ServiceLocator.smsPackingListDao.insertAll(listOf(pl))

                ServiceLocator.outboxService.enqueue(
                    OutboxService.Entity.PACKING_LIST, OutboxService.Op.CREATE, plId, projectId,
                    payload = CreatePackingListRequest(
                        packingListName  = name,
                        vehicle          = selectedVehiclePlate,
                        vehicleId        = selectedVehicleId,
                        position         = selectedPositionCode.ifBlank { null },
                        positionId       = selectedPositionId,
                        packingDate      = now,
                        notes            = notes,
                        createdBy        = "API",
                        projectCode      = projectCode,
                        totalSpoolsCount = selectedSpoolIds.size
                    )
                )

                selectedSpoolIds.forEachIndexed { index, spoolId ->
                    ServiceLocator.smsSpoolDao.updatePackingList(spoolId, plId)
                    ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                    ServiceLocator.smsPackingListSpoolDao.insert(
                        SmsPackingListSpoolEntity(
                            packing_list_spool_id = spoolId xor plId,
                            packing_list_id       = plId,
                            spool_id              = spoolId,
                            sequence_number       = index + 1,
                            added_at              = now
                        )
                    )
                    ServiceLocator.outboxService.enqueue(
                        OutboxService.Entity.PL_ASSIGN, OutboxService.Op.ASSIGN, spoolId, projectId,
                        refEntityId = plId,
                        payload = AssignSpoolRequest(spoolId, "API", index + 1)
                    )
                    movedFromPlIds[spoolId]?.let { oldPlId ->
                        ServiceLocator.smsSpoolDao.updateInTransit(spoolId, false)
                        ServiceLocator.outboxService.enqueue(
                            OutboxService.Entity.PL_ASSIGN, OutboxService.Op.UNASSIGN, spoolId, projectId,
                            refEntityId = oldPlId
                        )
                    }
                }

                // Now that every moved spool has actually left its old PL locally, refresh
                // each affected old PL's count once (not per-spool, in case several spools
                // moved from the same one) and push it to the server.
                movedFromPlIds.values.distinct().forEach { oldPlId ->
                    val newOldPlCount = ServiceLocator.smsSpoolDao.getByPackingList(oldPlId).size
                    ServiceLocator.smsPackingListDao.getById(oldPlId)?.let { oldPl ->
                        ServiceLocator.smsPackingListDao.insertAll(listOf(oldPl.copy(total_spools_count = newOldPlCount, synced = false)))
                    }
                    enqueueOldPlCountUpdate(oldPlId, projectId, newOldPlCount)
                }

                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.PL_CREADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_PL,
                    plId, name, projectId = projectId
                )
                Toast.makeText(requireContext(), getString(R.string.new_packing_list_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.new_packing_list_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
