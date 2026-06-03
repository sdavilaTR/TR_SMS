package com.example.hassiwrapper.ui.transfers

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
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
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.ui.common.SignatureView
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class ReceivePackingListFragment : Fragment() {

    private data class SpoolReceive(
        val spool: SmsSpoolEntity,
        var confirmed: Boolean = false,
        var assignment: String = ""
    )

    // Panels
    private lateinit var panelScanVehicle: View
    private lateinit var panelSelectPl: View
    private lateinit var panelConfirmSpools: View
    private lateinit var panelSignature: View

    // Panel A
    private lateinit var txtScannedVehicle: TextView
    private lateinit var btnScanVehicle: MaterialButton

    // Panel B
    private lateinit var txtNoPls: TextView
    private lateinit var rvPackingLists: RecyclerView
    private lateinit var plAdapter: PlAdapter

    // Panel C
    private lateinit var txtSpoolsProgress: TextView
    private lateinit var rvSpoolsToConfirm: RecyclerView
    private lateinit var btnScanSpool: MaterialButton
    private lateinit var btnNextToSignature: MaterialButton
    private lateinit var spoolAdapter: SpoolReceiveAdapter

    // Panel D
    private lateinit var signatureView: SignatureView
    private lateinit var btnClearSignature: MaterialButton
    private lateinit var btnConfirmReceive: MaterialButton

    // Manual fallback inputs
    private lateinit var etManualVehicle: TextInputEditText
    private lateinit var btnManualVehicle: MaterialButton
    private lateinit var etManualSpool: TextInputEditText
    private lateinit var btnManualSpool: MaterialButton

    private lateinit var txtReceiveDestination: TextView

    private var selectedVehicle: SmsVehicleEntity? = null
    private var selectedPl: SmsPackingListEntity? = null
    private val spoolReceives = mutableListOf<SpoolReceive>()
    private var assignmentOptions: List<String> = emptyList()

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
        inflater.inflate(R.layout.fragment_receive_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        panelScanVehicle   = view.findViewById(R.id.panelScanVehicle)
        panelSelectPl      = view.findViewById(R.id.panelSelectPl)
        panelConfirmSpools = view.findViewById(R.id.panelConfirmSpools)
        panelSignature     = view.findViewById(R.id.panelSignature)

        txtScannedVehicle  = view.findViewById(R.id.txtScannedVehicle)
        btnScanVehicle     = view.findViewById(R.id.btnScanVehicle)

        txtNoPls           = view.findViewById(R.id.txtNoPls)
        rvPackingLists     = view.findViewById(R.id.rvPackingLists)

        txtSpoolsProgress  = view.findViewById(R.id.txtSpoolsProgress)
        rvSpoolsToConfirm  = view.findViewById(R.id.rvSpoolsToConfirm)
        btnScanSpool       = view.findViewById(R.id.btnScanSpool)
        btnNextToSignature = view.findViewById(R.id.btnNextToSignature)

        signatureView          = view.findViewById(R.id.signatureView)
        btnClearSignature      = view.findViewById(R.id.btnClearSignature)
        btnConfirmReceive      = view.findViewById(R.id.btnConfirmReceive)
        txtReceiveDestination  = view.findViewById(R.id.txtReceiveDestination)

        etManualVehicle    = view.findViewById(R.id.etManualVehicle)
        btnManualVehicle   = view.findViewById(R.id.btnManualVehicle)
        etManualSpool      = view.findViewById(R.id.etManualSpool)
        btnManualSpool     = view.findViewById(R.id.btnManualSpool)

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
            vehicleScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }
        btnManualVehicle.setOnClickListener {
            val text = etManualVehicle.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) { etManualVehicle.setText(""); handleVehicleScan(text) }
        }
        btnScanSpool.setOnClickListener {
            spoolScanLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }
        btnManualSpool.setOnClickListener {
            val text = etManualSpool.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) { etManualSpool.setText(""); handleSpoolScan(text) }
        }
        btnNextToSignature.setOnClickListener { onNextToSignature() }
        btnClearSignature.setOnClickListener { signatureView.clear() }
        btnConfirmReceive.setOnClickListener { onConfirmReceive() }

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
            val location = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            assignmentOptions = when (location) {
                "LAYDOWN" -> {
                    val csv = ServiceLocator.configRepo.get("laydown_sections") ?: "1A,2A,1B,2B,1C,2C,1D,2D"
                    csv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
                "SITE" -> {
                    val csv = ServiceLocator.configRepo.get("site_units") ?: "1,2,3,4"
                    csv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
                else -> emptyList()
            }
        }
    }

    private fun handleVehicleScan(raw: String) {
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
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_not_found), Toast.LENGTH_LONG).show()
                txtScannedVehicle.text = raw
                return@launch
            }

            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            val currentPosition = ServiceLocator.smsPositionDao.getByCode(location)

            if (vehicle.project_id != projectId) {
                Toast.makeText(requireContext(), getString(R.string.transfer_vehicle_wrong_project), Toast.LENGTH_LONG).show()
                return@launch
            }
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
            loadPackingListsForVehicle(vehicle)
        }
    }

    private fun loadPackingListsForVehicle(vehicle: SmsVehicleEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val pls = ServiceLocator.smsPackingListDao.getWithInTransitSpoolsByVehicle(vehicle.vehicle_id)

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

        viewLifecycleOwner.lifecycleScope.launch {
            val spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                .filter { it.in_transit }
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

    private fun onNextToSignature() {
        val unconfirmed = spoolReceives.count { !it.confirmed }
        val proceed: () -> Unit = { goToSignaturePanel() }
        if (unconfirmed > 0) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.transfer_confirm_incomplete_title))
                .setMessage(getString(R.string.transfer_confirm_incomplete_msg, unconfirmed))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.load_spools_btn_continue)) { _, _ -> proceed() }
                .show()
        } else {
            proceed()
        }
    }

    private fun goToSignaturePanel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val location = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: ""
            val pos = ServiceLocator.smsPositionDao.getByCode(location)
            txtReceiveDestination.text = getString(R.string.transfer_receive_at, pos?.name ?: location)
            panelConfirmSpools.visibility = View.GONE
            panelSignature.visibility = View.VISIBLE
        }
    }

    private fun onConfirmReceive() {
        if (signatureView.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.transfer_signature_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val pl      = selectedPl ?: return
        val vehicle = selectedVehicle ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val location  = ServiceLocator.configRepo.get("device_location")?.uppercase() ?: "UNKNOWN"
            val now       = LocalDateTime.now().toString()
            val sigData   = signatureView.getBase64Png()

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

            // Collect assignment from adapters before processing
            val assignments = spoolAdapter.getAssignments()

            ServiceLocator.smsTransferDao.insertSpools(
                confirmedSpools.map { sr ->
                    SmsTransferSpoolEntity(
                        transfer_id  = transferId,
                        spool_id     = sr.spool.spool_id,
                        spool_code   = sr.spool.spool_code,
                        spool_suffix = sr.spool.spool_suffix,
                        assignment   = assignments[sr.spool.spool_id]
                    )
                }
            )

            confirmedSpools.forEach { sr ->
                val assign = assignments[sr.spool.spool_id]
                val zone   = if (location == "LAYDOWN") assign else sr.spool.zone
                val unit   = if (location == "SITE") assign else sr.spool.assigned_unit
                ServiceLocator.smsSpoolDao.updateZoneAndUnit(sr.spool.spool_id, zone, unit)
            }

            ServiceLocator.smsVehicleDao.setOffRoute(vehicle.vehicle_id)

            if (!isAdded) return@launch
            Toast.makeText(requireContext(), getString(R.string.transfer_receive_success), Toast.LENGTH_LONG).show()
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

    private inner class SpoolReceiveAdapter : RecyclerView.Adapter<SpoolReceiveAdapter.VH>() {
        private val spinnerSelections = mutableMapOf<Long, Int>()

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

            if (sr.confirmed && assignmentOptions.isNotEmpty()) {
                h.spinner.visibility = View.VISIBLE
                val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, assignmentOptions)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                h.spinner.adapter = spinnerAdapter
                val savedSel = spinnerSelections[sr.spool.spool_id] ?: 0
                h.spinner.setSelection(savedSel)
                h.spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        spinnerSelections[sr.spool.spool_id] = pos
                        sr.assignment = assignmentOptions[pos]
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            } else {
                h.spinner.visibility = View.GONE
            }
        }

        fun getAssignments(): Map<Long, String?> {
            return spoolReceives.associate { sr ->
                sr.spool.spool_id to if (sr.confirmed && assignmentOptions.isNotEmpty()) {
                    val sel = spinnerSelections[sr.spool.spool_id] ?: 0
                    assignmentOptions.getOrNull(sel)
                } else null
            }
        }
    }
}
