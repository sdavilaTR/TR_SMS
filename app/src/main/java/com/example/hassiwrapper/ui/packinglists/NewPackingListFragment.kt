package com.example.hassiwrapper.ui.packinglists

import android.app.AlertDialog
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
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.example.hassiwrapper.network.dto.CreatePackingListRequest
import com.google.gson.JsonParser
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
    private lateinit var etNotes: TextInputEditText
    private lateinit var btnAddSpools: MaterialButton
    private lateinit var btnSave: MaterialButton

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_packing_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        txtNamePreview = view.findViewById(R.id.txtPLNamePreview)
        tilPosition    = view.findViewById(R.id.tilPLPosition)
        tilVehicle     = view.findViewById(R.id.tilPLVehicle)
        actvPosition   = view.findViewById(R.id.actvPLPosition)
        actvVehicle    = view.findViewById(R.id.actvPLVehicle)
        etNotes        = view.findViewById(R.id.etPLNotes)
        btnAddSpools   = view.findViewById(R.id.btnAddSpools)
        btnSave        = view.findViewById(R.id.btnSavePL)

        preselectedVehicleId    = arguments?.getLong("vehicleId", 0L) ?: 0L
        preselectedVehiclePlate = arguments?.getString("vehiclePlate")

        btnAddSpools.setOnClickListener { showSpoolPickerDialog() }
        btnSave.setOnClickListener { savePackingList() }

        loadData()
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
                val plId  = (ServiceLocator.smsPackingListDao.getMaxId() ?: 0L) + 1L
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

                var serverPlId = plId
                try {
                    if (projectCode.isNotBlank()) {
                        val body = CreatePackingListRequest(
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
                        val resp = ServiceLocator.apiClient.getService().createPackingList(projectCode, body)
                        val rawBody = resp.body()?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val parsedId = parseCreatedPlId(rawBody)
                            if (parsedId != null && parsedId > 0L && parsedId != plId) {
                                ServiceLocator.smsPackingListDao.deleteById(plId)
                                ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(packing_list_id = parsedId, synced = true)))
                                serverPlId = parsedId
                            } else {
                                ServiceLocator.smsPackingListDao.markSynced(listOf(plId))
                            }
                        }
                    }
                } catch (_: Exception) { /* offline — SyncService reintentará */ }

                selectedSpoolIds.forEachIndexed { index, spoolId ->
                    ServiceLocator.smsSpoolDao.updatePackingList(spoolId, serverPlId)
                    ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                    ServiceLocator.smsPackingListSpoolDao.insert(
                        SmsPackingListSpoolEntity(
                            packing_list_spool_id = spoolId xor serverPlId,
                            packing_list_id       = serverPlId,
                            spool_id              = spoolId,
                            sequence_number       = index + 1,
                            added_at              = now
                        )
                    )
                    try {
                        if (projectCode.isNotBlank()) {
                            ServiceLocator.apiClient.getService()
                                .addSpoolToPackingList(projectCode, serverPlId, AssignSpoolRequest(spoolId, "API", index + 1))
                        }
                    } catch (_: Exception) { /* offline */ }
                }

                Toast.makeText(requireContext(), getString(R.string.new_packing_list_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.new_packing_list_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseCreatedPlId(raw: String): Long? {
        return try {
            val el = JsonParser.parseString(raw)
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
}
