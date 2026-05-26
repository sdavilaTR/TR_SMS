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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingSpoolEntity
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
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
            val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(raw)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (urlVehicleId != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val vehicle = ServiceLocator.smsVehicleDao.getById(urlVehicleId)
                    if (vehicle != null) {
                        etPlate.setText(vehicle.license_plate)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.load_spools_vehicle_not_found), Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                etPlate.setText(raw)
            }
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
            selectedVehicle = vehicle
            txtSelectedVehicle.text = getString(R.string.load_spools_vehicle_selected, vehicle.license_plate)
            panelVehicle.visibility = View.GONE
            panelSpools.visibility = View.VISIBLE
        }
    }

    private fun parseAndAddSpoolFromQr(raw: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val lastDash = raw.lastIndexOf('-')
            var spool: SmsSpoolEntity? = null

            if (lastDash > 0) {
                val code   = raw.substring(0, lastDash)
                val suffix = raw.substring(lastDash + 1)
                spool = ServiceLocator.smsSpoolDao.findByCodeAndSuffix(projectId, code, suffix)
                if (spool == null) spool = ServiceLocator.smsSpoolDao.findByCode(projectId, code)
            }
            if (spool == null) spool = ServiceLocator.smsSpoolDao.findByCode(projectId, raw)

            val (fallbackCode, fallbackSuffix) = if (lastDash > 0) {
                raw.substring(0, lastDash) to raw.substring(lastDash + 1)
            } else raw to null

            addSpool(spool, fallbackCode, fallbackSuffix)
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
        viewLifecycleOwner.lifecycleScope.launch {
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
            ServiceLocator.smsVehicleLoadingDao.insertSpools(
                scannedSpools.map { s ->
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
            if (!isAdded) return@launch
            Toast.makeText(requireContext(), getString(R.string.load_spools_saved), Toast.LENGTH_LONG).show()
            findNavController().navigateUp()
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
