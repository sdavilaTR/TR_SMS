package com.example.hassiwrapper.ui.packinglists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.UpdatePackingListRequest
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class EditPackingListFragment : Fragment() {

    private var packingListId = 0L
    private var projectId = 6

    private lateinit var txtCurrentName: TextView
    private lateinit var tilPosition: TextInputLayout
    private lateinit var tilVehicle: TextInputLayout
    private lateinit var actvPosition: AutoCompleteTextView
    private lateinit var actvVehicle: AutoCompleteTextView
    private lateinit var etNotes: TextInputEditText
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var projectCode = ""

    private var selectedPositionId: Int? = null
    private var selectedPositionCode = ""
    private var selectedVehicleId: Long? = null
    private var selectedVehiclePlate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packingListId = arguments?.getLong("packingListId") ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_edit_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        txtCurrentName = view.findViewById(R.id.txtEditPLName)
        tilPosition    = view.findViewById(R.id.tilEditPosition)
        tilVehicle     = view.findViewById(R.id.tilEditVehicle)
        actvPosition   = view.findViewById(R.id.actvEditPosition)
        actvVehicle    = view.findViewById(R.id.actvEditVehicle)
        etNotes        = view.findViewById(R.id.etEditNotes)
        btnUpdate      = view.findViewById(R.id.btnUpdatePL)
        btnCancel      = view.findViewById(R.id.btnCancelEditPL)

        btnUpdate.setOnClickListener { saveEdits() }
        btnCancel.setOnClickListener { findNavController().navigateUp() }
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val project = ServiceLocator.projectDao.getById(projectId)
            projectCode = project?.project_code.orEmpty()

            val pl = ServiceLocator.smsPackingListDao.getById(packingListId) ?: run {
                findNavController().navigateUp(); return@launch
            }
            txtCurrentName.text = pl.packing_list_name.ifBlank { "PL $packingListId" }
            etNotes.setText(pl.notes.orEmpty())

            val positions = ServiceLocator.smsPositionDao.getAll()
            val vehicles  = ServiceLocator.smsVehicleDao.getByProject(projectId)
            setupPositionDropdown(positions, pl.position_id)
            setupVehicleDropdown(vehicles, pl.vehicle_id)
        }
    }

    private fun setupPositionDropdown(positions: List<SmsPositionEntity>, currentId: Int?) {
        val labels = positions.map { it.name.ifBlank { it.code } }
        actvPosition.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        actvPosition.setOnItemClickListener { _, _, pos, _ ->
            selectedPositionId   = positions[pos].position_id
            selectedPositionCode = positions[pos].code.ifBlank { positions[pos].name }
            tilPosition.error    = null
        }
        val idx = positions.indexOfFirst { it.position_id == currentId }
        if (idx >= 0) {
            actvPosition.setText(labels[idx], false)
            selectedPositionId   = positions[idx].position_id
            selectedPositionCode = positions[idx].code.ifBlank { positions[idx].name }
        }
    }

    private fun setupVehicleDropdown(vehicles: List<SmsVehicleEntity>, currentVehicleId: Long?) {
        val labels = vehicles.map { v ->
            v.vehicle_name?.takeIf { it.isNotBlank() }?.let { "$it – ${v.license_plate}" } ?: v.license_plate
        }
        actvVehicle.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        actvVehicle.setOnItemClickListener { _, _, pos, _ ->
            selectedVehicleId    = vehicles[pos].vehicle_id
            selectedVehiclePlate = vehicles[pos].license_plate
        }
        actvVehicle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && actvVehicle.text.isNullOrBlank()) {
                selectedVehicleId    = null
                selectedVehiclePlate = null
            }
        }
        val idx = vehicles.indexOfFirst { it.vehicle_id == currentVehicleId }
        if (idx >= 0) {
            actvVehicle.setText(labels[idx], false)
            selectedVehicleId    = vehicles[idx].vehicle_id
            selectedVehiclePlate = vehicles[idx].license_plate
        }
    }

    private fun buildUpdatedName(originalName: String): String {
        val seq      = originalName.substringAfterLast("-").padStart(3, '0')
        val posCode  = selectedPositionCode.uppercase().trim()
        val proj     = projectCode.uppercase().trim()
        val plate    = selectedVehiclePlate?.uppercase()?.trim()?.replace("-", "")
        return if (!plate.isNullOrBlank())
            "PL-$proj-$posCode-$plate-$seq"
        else
            "PL-$proj-$posCode-$seq"
    }

    private fun saveEdits() {
        tilPosition.error = null
        if (actvVehicle.text.isNullOrBlank()) {
            selectedVehicleId    = null
            selectedVehiclePlate = null
        }
        if (selectedPositionId == null) {
            tilPosition.error = getString(R.string.new_packing_list_error_field_required)
            return
        }
        btnUpdate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pl = ServiceLocator.smsPackingListDao.getById(packingListId) ?: return@launch
                val notes      = etNotes.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val newName    = if (selectedPositionCode.isNotBlank() && projectCode.isNotBlank())
                    buildUpdatedName(pl.packing_list_name)
                else
                    pl.packing_list_name
                val updated = pl.copy(
                    packing_list_name  = newName,
                    vehicle_id         = selectedVehicleId,
                    vehicle_plate      = selectedVehiclePlate,
                    position_id        = selectedPositionId,
                    notes              = notes,
                    synced             = false
                )
                ServiceLocator.smsPackingListDao.insertAll(listOf(updated))

                try {
                    if (projectCode.isNotBlank()) {
                        val positionName = selectedPositionId?.let { pid ->
                            ServiceLocator.smsPositionDao.getAll().find { it.position_id == pid }?.name
                        }
                        val updateBody = UpdatePackingListRequest(
                            packingListId    = packingListId,
                            packingListName  = newName,
                            vehicle          = selectedVehiclePlate,
                            position         = positionName,
                            positionId       = selectedPositionId,
                            packingDate      = pl.packing_date.takeIf { it.isNotBlank() },
                            notes            = notes,
                            createdBy        = pl.created_by,
                            updatedBy        = null,
                            projectCode      = projectCode,
                            totalSpoolsCount = pl.total_spools_count ?: 0
                        )
                        val resp = ServiceLocator.apiClient.getService().updatePackingList(projectCode, updateBody)
                        if (resp.isSuccessful) {
                            ServiceLocator.smsPackingListDao.markSynced(listOf(packingListId))
                        }
                    }
                } catch (_: Exception) { /* offline — SyncService reintentará */ }

                Toast.makeText(requireContext(), getString(R.string.pl_edit_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnUpdate.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.pl_edit_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
