package com.example.hassiwrapper.ui.vehicles

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.MainActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingSpoolEntity
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import android.util.Log
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class LoadSpoolsFragment : Fragment() {

    private data class ScannedSpool(
        val spoolId: Long,
        val spoolCode: String,
        val spoolSuffix: String?,
        val packingListId: Long?,
        val packingListName: String?
    ) {
        val displayCode: String
            get() = if (spoolSuffix.isNullOrBlank()) spoolCode else "$spoolCode-$spoolSuffix"
    }

    private var selectedVehicle: SmsVehicleEntity? = null
    private val scannedSpools = mutableListOf<ScannedSpool>()
    private lateinit var adapter: ScannedSpoolAdapter

    // Step 1 views
    private lateinit var panelVehicle: View
    private lateinit var etPlate: TextInputEditText
    private lateinit var btnScanVehicle: MaterialButton
    private lateinit var btnConfirmVehicle: MaterialButton

    // Step 2 views
    private lateinit var panelSpools: View
    private lateinit var txtSelectedVehicle: TextView
    private lateinit var bannerWarning: MaterialCardView
    private lateinit var txtWarning: TextView
    private lateinit var bannerError: MaterialCardView
    private lateinit var txtError: TextView
    private lateinit var etSpoolCode: TextInputEditText
    private lateinit var etSpoolSuffix: TextInputEditText
    private lateinit var btnScanSpool: MaterialButton
    private lateinit var btnAddSpool: MaterialButton
    private lateinit var rvLoadedSpools: RecyclerView
    private lateinit var txtSpoolsCount: TextView
    private lateinit var btnSave: MaterialButton

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_load_spools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        panelVehicle      = view.findViewById(R.id.panelVehicle)
        etPlate           = view.findViewById(R.id.etPlate)
        btnScanVehicle    = view.findViewById(R.id.btnScanVehicle)
        btnConfirmVehicle = view.findViewById(R.id.btnConfirmVehicle)

        panelSpools        = view.findViewById(R.id.panelSpools)
        txtSelectedVehicle = view.findViewById(R.id.txtSelectedVehicle)
        bannerWarning      = view.findViewById(R.id.bannerWarning)
        txtWarning         = view.findViewById(R.id.txtWarning)
        bannerError        = view.findViewById(R.id.bannerError)
        txtError           = view.findViewById(R.id.txtError)
        etSpoolCode        = view.findViewById(R.id.etSpoolCode)
        etSpoolSuffix      = view.findViewById(R.id.etSpoolSuffix)
        btnScanSpool       = view.findViewById(R.id.btnScanSpool)
        btnAddSpool        = view.findViewById(R.id.btnAddSpool)
        rvLoadedSpools     = view.findViewById(R.id.rvLoadedSpools)
        txtSpoolsCount     = view.findViewById(R.id.txtSpoolsCount)
        btnSave            = view.findViewById(R.id.btnSave)

        adapter = ScannedSpoolAdapter()
        rvLoadedSpools.layoutManager = LinearLayoutManager(requireContext())
        rvLoadedSpools.adapter = adapter
        rvLoadedSpools.isNestedScrollingEnabled = false
        rvLoadedSpools.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, 0)

        btnScanVehicle.setOnClickListener {
            vehicleScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }

        btnConfirmVehicle.setOnClickListener { confirmVehicle() }

        btnScanSpool.setOnClickListener {
            spoolScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }

        btnAddSpool.setOnClickListener { addSpoolManually() }

        btnSave.setOnClickListener { onSaveClicked() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    handleHardwareScan(raw.trim())
                }
            }
        }
    }

    private fun handleHardwareScan(raw: String) {
        if (panelSpools.visibility == View.VISIBLE) {
            parseAndAddSpoolFromQr(raw)
        } else {
            resolveAndSelectVehicle(raw)
        }
    }

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

            addSpool(spool, code, suffix)
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
            addSpool(spool, code, suffix)
            etSpoolCode.text?.clear()
            etSpoolSuffix.text?.clear()
        }
    }

    private fun addSpool(spool: SmsSpoolEntity?, fallbackCode: String, fallbackSuffix: String?) {
        val displayCode = spool?.displayCode
            ?: if (fallbackSuffix.isNullOrBlank()) fallbackCode else "$fallbackCode-$fallbackSuffix"

        if (scannedSpools.any { it.displayCode.equals(displayCode, ignoreCase = true) }) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_spool_duplicate), Toast.LENGTH_SHORT).show()
            return
        }

        if (spool == null) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_spool_not_found), Toast.LENGTH_SHORT).show()
        }

        val item = ScannedSpool(
            spoolId         = spool?.spool_id ?: 0L,
            spoolCode       = spool?.spool_code ?: fallbackCode,
            spoolSuffix     = spool?.spool_suffix ?: fallbackSuffix?.ifBlank { null },
            packingListId   = spool?.packing_list_id,
            packingListName = spool?.packing_list_name
        )
        scannedSpools.add(item)
        adapter.notifyItemInserted(scannedSpools.size - 1)
        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, scannedSpools.size)
        Toast.makeText(requireContext(), getString(R.string.load_spools_spool_added, displayCode), Toast.LENGTH_SHORT).show()
        updateAlerts()
    }

    private fun removeSpoolAt(position: Int) {
        if (position < 0 || position >= scannedSpools.size) return
        scannedSpools.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, scannedSpools.size - position)
        txtSpoolsCount.text = getString(R.string.load_spools_spools_count, scannedSpools.size)
        updateAlerts()
    }

    private fun updateAlerts() {
        val distinctPlIds = scannedSpools.mapNotNull { it.packingListId }.distinct()

        if (distinctPlIds.size > 1) {
            bannerWarning.visibility = View.VISIBLE
            txtWarning.text = getString(R.string.load_spools_warning_multi_pl, distinctPlIds.size)
        } else {
            bannerWarning.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val missingLines = mutableListOf<String>()
            for (plId in distinctPlIds) {
                val allInPl    = ServiceLocator.smsSpoolDao.getByPackingList(plId)
                val scannedInPl = scannedSpools.count { it.packingListId == plId }
                if (scannedInPl < allInPl.size) {
                    val plName = scannedSpools.firstOrNull { it.packingListId == plId }?.packingListName ?: "PL-$plId"
                    missingLines.add("$plName: $scannedInPl/${allInPl.size} spools")
                }
            }
            if (!isAdded) return@launch
            if (missingLines.isNotEmpty()) {
                bannerError.visibility = View.VISIBLE
                txtError.text = getString(R.string.load_spools_error_incomplete, missingLines.joinToString("\n"))
            } else {
                bannerError.visibility = View.GONE
            }
        }
    }

    private fun onSaveClicked() {
        if (scannedSpools.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.load_spools_empty_error), Toast.LENGTH_SHORT).show()
            return
        }
        if (bannerError.visibility == View.VISIBLE) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.load_spools_confirm_incomplete_title))
                .setMessage(getString(R.string.load_spools_confirm_incomplete_msg, txtError.text))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.load_spools_btn_continue)) { _, _ ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.load_spools_confirm_second_title))
                        .setMessage(getString(R.string.load_spools_confirm_second_msg))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(getString(R.string.load_spools_btn_save_anyway)) { _, _ -> saveLoading() }
                        .show()
                }
                .show()
        } else {
            saveLoading()
        }
    }

    private fun saveLoading() {
        val vehicle = selectedVehicle ?: return
        Log.d("LoadSpools", "saveLoading: vehicle_id=${vehicle.vehicle_id} plate=${vehicle.license_plate} spools=${scannedSpools.size}")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val now = LocalDateTime.now().toString()
                val loadingId = ServiceLocator.smsVehicleLoadingDao.insert(
                    SmsVehicleLoadingEntity(
                        vehicle_id    = vehicle.vehicle_id,
                        vehicle_plate = vehicle.license_plate,
                        project_id    = projectId,
                        created_at    = now,
                        synced        = false
                    )
                )
                Log.d("LoadSpools", "loadingId=$loadingId")
                ServiceLocator.smsVehicleLoadingDao.insertSpools(
                    scannedSpools.map { s ->
                        Log.d("LoadSpools", "  spool: id=${s.spoolId} code=${s.spoolCode} plId=${s.packingListId}")
                        SmsVehicleLoadingSpoolEntity(
                            loading_id        = loadingId,
                            spool_id          = s.spoolId,
                            spool_code        = s.spoolCode,
                            spool_suffix      = s.spoolSuffix,
                            packing_list_id   = s.packingListId,
                            packing_list_name = s.packingListName
                        )
                    }
                )
                // DEBUG: dump all scanned spools with their packingListId
                scannedSpools.forEachIndexed { i, s ->
                    Log.d("LoadSpools", "  scannedSpool[$i] spoolId=${s.spoolId} code=${s.spoolCode} packingListId=${s.packingListId} packingListName=${s.packingListName}")
                }

                val plIds = scannedSpools.mapNotNull { it.packingListId }.distinct()
                Log.d("LoadSpools", "plIds to mark ready: $plIds  (scannedSpools.size=${scannedSpools.size})")
                plIds.forEach { plId ->
                    Log.d("LoadSpools", "  calling setReadyToSend+setVehicle(plId=$plId, vehicleId=${vehicle.vehicle_id})")
                    ServiceLocator.smsPackingListDao.setReadyToSend(plId, true)
                    ServiceLocator.smsPackingListDao.setVehicle(plId, vehicle.vehicle_id, vehicle.license_plate)
                    // Verify write
                    val after = ServiceLocator.smsPackingListDao.getById(plId)
                    Log.d("LoadSpools", "  after → pl=${after?.packing_list_id} ready_to_send=${after?.ready_to_send} vehicle_id=${after?.vehicle_id} vehicle_plate=${after?.vehicle_plate}")
                }

                // Notify backend that these PLs are ready to send
                try {
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val service = ServiceLocator.apiClient.getService()
                        plIds.forEach { plId -> service.setPackingListReadyToSend(projectCode, plId, true) }
                    }
                } catch (_: Exception) { /* offline – local flag preserved, sync will retry */ }

                val activity = requireActivity() as? MainActivity
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), getString(R.string.load_spools_saved), Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
                // Fire-and-forget sync so ReceivePackingListFragment sees fresh data
                activity?.lifecycleScope?.launch { activity.syncSmsData() }
            } catch (e: Exception) {
                Log.e("LoadSpools", "saveLoading FAILED", e)
                if (isAdded) Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
