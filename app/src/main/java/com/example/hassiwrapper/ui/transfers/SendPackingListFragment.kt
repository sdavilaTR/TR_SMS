package com.example.hassiwrapper.ui.transfers

import android.util.Log
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferSpoolEntity
import com.example.hassiwrapper.ui.common.SignatureView
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class SendPackingListFragment : Fragment() {

    private data class SpoolConfirmation(
        val spool: SmsSpoolEntity,
        var confirmed: Boolean = false
    )

    // Panels
    private lateinit var panelSelectPl: View
    private lateinit var panelConfirmVehicle: View
    private lateinit var panelConfirmSpools: View
    private lateinit var panelSignature: View

    // Panel A
    private lateinit var rvPackingLists: RecyclerView
    private lateinit var txtNoPls: TextView
    private lateinit var plAdapter: PlAdapter

    // Panel B
    private lateinit var txtExpectedVehicle: TextView
    private lateinit var txtVehicleStatus: TextView
    private lateinit var btnScanVehicle: MaterialButton
    private lateinit var btnNextToSpools: MaterialButton

    // Panel C
    private lateinit var txtSpoolsProgress: TextView
    private lateinit var rvSpoolsToConfirm: RecyclerView
    private lateinit var btnScanSpool: MaterialButton
    private lateinit var btnNextToSignature: MaterialButton
    private lateinit var spoolAdapter: SpoolConfirmAdapter

    // Panel D
    private lateinit var signatureView: SignatureView
    private lateinit var btnClearSignature: MaterialButton
    private lateinit var btnConfirmSend: MaterialButton

    // Manual fallback inputs
    private lateinit var etManualVehicle: TextInputEditText
    private lateinit var btnManualVehicle: MaterialButton
    private lateinit var etManualSpool: TextInputEditText
    private lateinit var btnManualSpool: MaterialButton

    private lateinit var txtSendDestination: TextView

    private var selectedPl: SmsPackingListEntity? = null
    private var vehicleConfirmed = false
    private val spoolConfirmations = mutableListOf<SpoolConfirmation>()
    private var destination = ""

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_send_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        panelSelectPl        = view.findViewById(R.id.panelSelectPl)
        panelConfirmVehicle  = view.findViewById(R.id.panelConfirmVehicle)
        panelConfirmSpools   = view.findViewById(R.id.panelConfirmSpools)
        panelSignature       = view.findViewById(R.id.panelSignature)

        txtNoPls             = view.findViewById(R.id.txtNoPls)
        rvPackingLists       = view.findViewById(R.id.rvPackingLists)

        txtExpectedVehicle   = view.findViewById(R.id.txtExpectedVehicle)
        txtVehicleStatus     = view.findViewById(R.id.txtVehicleStatus)
        btnScanVehicle       = view.findViewById(R.id.btnScanVehicle)
        btnNextToSpools      = view.findViewById(R.id.btnNextToSpools)

        txtSpoolsProgress    = view.findViewById(R.id.txtSpoolsProgress)
        rvSpoolsToConfirm    = view.findViewById(R.id.rvSpoolsToConfirm)
        btnScanSpool         = view.findViewById(R.id.btnScanSpool)
        btnNextToSignature   = view.findViewById(R.id.btnNextToSignature)

        signatureView        = view.findViewById(R.id.signatureView)
        btnClearSignature    = view.findViewById(R.id.btnClearSignature)
        btnConfirmSend       = view.findViewById(R.id.btnConfirmSend)
        txtSendDestination   = view.findViewById(R.id.txtSendDestination)

        etManualVehicle      = view.findViewById(R.id.etManualVehicle)
        btnManualVehicle     = view.findViewById(R.id.btnManualVehicle)
        etManualSpool        = view.findViewById(R.id.etManualSpool)
        btnManualSpool       = view.findViewById(R.id.btnManualSpool)

        plAdapter = PlAdapter()
        rvPackingLists.layoutManager = LinearLayoutManager(requireContext())
        rvPackingLists.adapter = plAdapter
        rvPackingLists.isNestedScrollingEnabled = false
        rvPackingLists.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        spoolAdapter = SpoolConfirmAdapter()
        rvSpoolsToConfirm.layoutManager = LinearLayoutManager(requireContext())
        rvSpoolsToConfirm.adapter = spoolAdapter
        rvSpoolsToConfirm.isNestedScrollingEnabled = false
        rvSpoolsToConfirm.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        btnScanVehicle.setOnClickListener {
            vehicleScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }
        btnManualVehicle.setOnClickListener {
            val text = etManualVehicle.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) { etManualVehicle.setText(""); handleVehicleScan(text) }
        }
        btnNextToSpools.setOnClickListener { showSpoolPanel() }
        btnScanSpool.setOnClickListener {
            spoolScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }
        btnManualSpool.setOnClickListener {
            val text = etManualSpool.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) { etManualSpool.setText(""); handleSpoolScan(text) }
        }
        btnNextToSignature.setOnClickListener { onNextToSignature() }
        btnClearSignature.setOnClickListener { signatureView.clear() }
        btnConfirmSend.setOnClickListener { onConfirmSend() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    when {
                        panelConfirmVehicle.visibility == View.VISIBLE -> handleVehicleScan(raw.trim())
                        panelConfirmSpools.visibility == View.VISIBLE  -> handleSpoolScan(raw.trim())
                    }
                }
            }
        }

        loadPackingLists()
    }

    private fun loadPackingLists() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            Log.d("SendPL", "loadPackingLists: projectId=$projectId device_location='$location'")

            if (location == "SITE" || location.isBlank()) {
                Log.d("SendPL", "loadPackingLists: early return — location is SITE or blank")
                txtNoPls.visibility = View.VISIBLE
                txtNoPls.text = getString(R.string.transfer_send_no_pls)
                rvPackingLists.visibility = View.GONE
                return@launch
            }

            val currentPosition = ServiceLocator.smsPositionDao.getByCode(location)
            Log.d("SendPL", "loadPackingLists: currentPosition for '$location' = ${currentPosition?.position_id} '${currentPosition?.name}'")
            if (currentPosition == null) {
                Log.w("SendPL", "loadPackingLists: position not found for '$location' — positions table has ${ServiceLocator.smsPositionDao.getAll().size} rows")
                txtNoPls.visibility = View.VISIBLE
                txtNoPls.text = getString(R.string.transfer_send_no_pls)
                rvPackingLists.visibility = View.GONE
                return@launch
            }

            val candidates = ServiceLocator.smsPackingListDao
                .getReadyToSendByPosition(projectId, currentPosition.position_id)
            Log.d("SendPL", "loadPackingLists: candidates ready_to_send+position=${candidates.size}")
            candidates.forEach { pl ->
                Log.d("SendPL", "  candidate pl=${pl.packing_list_id} '${pl.packing_list_name}' vehicle_id=${pl.vehicle_id} ready=${pl.ready_to_send} position_id=${pl.position_id}")
            }

            val sendable = candidates.filter { pl ->
                pl.vehicle_id != null && hasSendableSpools(pl.packing_list_id)
            }
            Log.d("SendPL", "loadPackingLists: sendable (vehicle!=null && has non-transit spools)=${sendable.size}")

            // DEBUG: also show all ready_to_send PLs regardless of position
            val allReady = ServiceLocator.smsPackingListDao.getReadyToSend(projectId)
            Log.d("SendPL", "loadPackingLists: ALL ready_to_send PLs for project=$projectId → ${allReady.size}")
            allReady.forEach { pl ->
                Log.d("SendPL", "  ALL ready pl=${pl.packing_list_id} '${pl.packing_list_name}' position_id=${pl.position_id} vehicle_id=${pl.vehicle_id}")
            }

            if (sendable.isEmpty()) {
                txtNoPls.visibility = View.VISIBLE
                rvPackingLists.visibility = View.GONE
            } else {
                txtNoPls.visibility = View.GONE
                rvPackingLists.visibility = View.VISIBLE
                plAdapter.items = sendable
                plAdapter.notifyDataSetChanged()
            }
        }
    }

    private suspend fun hasSendableSpools(packingListId: Long): Boolean {
        val spools = ServiceLocator.smsSpoolDao.getByPackingList(packingListId)
        Log.d("SendPL", "hasSendableSpools(pl=$packingListId): ${spools.size} spools found, in_transit=${spools.map { it.in_transit }}")
        return spools.any { !it.in_transit }
    }

    private fun onPlSelected(pl: SmsPackingListEntity) {
        selectedPl = pl
        showVehiclePanel(pl)
    }

    private fun showVehiclePanel(pl: SmsPackingListEntity) {
        panelSelectPl.visibility = View.GONE
        panelConfirmVehicle.visibility = View.VISIBLE
        txtExpectedVehicle.text = getString(R.string.transfer_vehicle_expected, pl.vehicle_plate ?: "—")
        txtVehicleStatus.text = ""
        vehicleConfirmed = false
        btnNextToSpools.isEnabled = false
    }

    private fun handleVehicleScan(raw: String) {
        val pl = selectedPl ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val qr = parseQr(raw)
            val scannedPlate = when (qr) {
                is QrResult.VehicleBadge -> {
                    Toast.makeText(requireContext(), getString(R.string.qr_scanner_result_badge_unsupported), Toast.LENGTH_LONG).show()
                    return@launch
                }
                is QrResult.VehicleId -> ServiceLocator.smsVehicleDao.getById(qr.id)?.license_plate
                is QrResult.VehiclePlate -> qr.plate
                else -> raw
            } ?: return@launch

            val expectedPlate = pl.vehicle_plate ?: ""
            if (scannedPlate.equals(expectedPlate, ignoreCase = true)) {
                vehicleConfirmed = true
                txtVehicleStatus.text = getString(R.string.transfer_vehicle_confirmed, scannedPlate)
                btnNextToSpools.isEnabled = true
            } else {
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_mismatch), Toast.LENGTH_LONG).show()
                txtVehicleStatus.text = getString(R.string.transfer_vehicle_scanned, scannedPlate)
            }
        }
    }

    private fun showSpoolPanel() {
        val pl = selectedPl ?: return
        panelConfirmVehicle.visibility = View.GONE
        panelConfirmSpools.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                .filter { !it.in_transit }
            spoolConfirmations.clear()
            spoolConfirmations.addAll(spools.map { SpoolConfirmation(it) })
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

            val entry = spoolConfirmations.find { it.spool.spool_id == spool.spool_id }
            if (entry == null) {
                Toast.makeText(requireContext(), getString(R.string.transfer_spool_not_in_list), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (entry.confirmed) {
                Toast.makeText(requireContext(), getString(R.string.transfer_spool_already_confirmed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            entry.confirmed = true
            val idx = spoolConfirmations.indexOf(entry)
            spoolAdapter.notifyItemChanged(idx)
            updateProgress()
            Toast.makeText(requireContext(), getString(R.string.transfer_spool_confirmed, spool.displayCode), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateProgress() {
        val total     = spoolConfirmations.size
        val confirmed = spoolConfirmations.count { it.confirmed }
        txtSpoolsProgress.text = getString(R.string.transfer_spools_progress, confirmed, total)
    }

    private fun onNextToSignature() {
        val unconfirmed = spoolConfirmations.count { !it.confirmed }
        if (unconfirmed > 0) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.transfer_confirm_incomplete_title))
                .setMessage(getString(R.string.transfer_confirm_incomplete_msg, unconfirmed))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.load_spools_btn_continue)) { _, _ -> goToSignaturePanel() }
                .show()
        } else {
            goToSignaturePanel()
        }
    }

    private fun goToSignaturePanel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            val destCode = when (location) {
                "WORKSHOP" -> "LAYDOWN"
                "LAYDOWN"  -> "SITE"
                else       -> return@launch
            }
            val destPosition = ServiceLocator.smsPositionDao.getByCode(destCode)
            destination = destCode
            txtSendDestination.text = getString(R.string.transfer_send_to, destPosition?.name ?: destCode)
            panelConfirmSpools.visibility = View.GONE
            panelSignature.visibility = View.VISIBLE
        }
    }

    private fun onConfirmSend() {
        if (signatureView.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.transfer_signature_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val pl = selectedPl ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: "UNKNOWN"
            val now       = LocalDateTime.now().toString()
            val sigData   = signatureView.getBase64Png()

            val transferId = ServiceLocator.smsTransferDao.insert(
                SmsTransferEntity(
                    transfer_type        = "SEND",
                    packing_list_id      = pl.packing_list_id,
                    packing_list_name    = pl.packing_list_name,
                    vehicle_id           = pl.vehicle_id ?: 0L,
                    vehicle_plate        = pl.vehicle_plate ?: "",
                    origin_location      = location,
                    destination_location = destination,
                    signature_data       = sigData,
                    created_at           = now,
                    project_id           = projectId
                )
            )

            val confirmedSpools = spoolConfirmations.filter { it.confirmed }
            ServiceLocator.smsTransferDao.insertSpools(
                confirmedSpools.map { sc ->
                    SmsTransferSpoolEntity(
                        transfer_id  = transferId,
                        spool_id     = sc.spool.spool_id,
                        spool_code   = sc.spool.spool_code,
                        spool_suffix = sc.spool.spool_suffix,
                        assignment   = null
                    )
                }
            )

            confirmedSpools.forEach { sc ->
                ServiceLocator.smsSpoolDao.updateInTransit(sc.spool.spool_id, true)
            }

            val destPosition = ServiceLocator.smsPositionDao.getByCode(destination)
            ServiceLocator.smsVehicleDao.setOnRoute(pl.vehicle_id ?: 0L, destPosition?.position_id)

            if (!isAdded) return@launch
            Toast.makeText(requireContext(), getString(R.string.transfer_send_success), Toast.LENGTH_LONG).show()
            findNavController().navigateUp()
        }
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

    private inner class SpoolConfirmAdapter : RecyclerView.Adapter<SpoolConfirmAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val imgCheck:  ImageView = view.findViewById(R.id.imgCheckMark)
            val txtCode:   TextView  = view.findViewById(R.id.txtSpoolCode)
            val txtPl:     TextView  = view.findViewById(R.id.txtSpoolPl)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transfer_spool, parent, false))

        override fun getItemCount() = spoolConfirmations.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val sc = spoolConfirmations[position]
            h.txtCode.text  = sc.spool.displayCode
            h.txtPl.text    = sc.spool.packing_list_name ?: ""
            h.imgCheck.setImageResource(
                if (sc.confirmed) android.R.drawable.checkbox_on_background
                else android.R.drawable.checkbox_off_background
            )
        }
    }
}
