package com.example.hassiwrapper.ui.packinglists

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.example.hassiwrapper.network.dto.SpoolDto
import com.example.hassiwrapper.network.dto.UpdatePackingListRequest
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

class PackingListDetailFragment : Fragment() {

    companion object {
        val locallyDeletedPLIds = mutableSetOf<Long>()
    }

    private var packingListId = 0L

    private lateinit var txtName: TextView
    private lateinit var txtDate: TextView
    private lateinit var txtPosition: TextView
    private lateinit var txtVehicle: TextView
    private lateinit var txtNotes: TextView
    private lateinit var layoutSpools: LinearLayout
    private lateinit var btnAddSpool: MaterialButton
    private lateinit var btnEdit: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnHardDelete: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packingListId = arguments?.getLong("packingListId") ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_packing_list_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        txtName      = view.findViewById(R.id.txtDetailPLName)
        txtDate      = view.findViewById(R.id.txtDetailDate)
        txtPosition  = view.findViewById(R.id.txtDetailPosition)
        txtVehicle   = view.findViewById(R.id.txtDetailVehicle)
        txtNotes     = view.findViewById(R.id.txtDetailNotes)
        layoutSpools = view.findViewById(R.id.layoutSpools)
        btnAddSpool  = view.findViewById(R.id.btnAddSpool)
        btnEdit       = view.findViewById(R.id.btnEditPL)
        btnDelete     = view.findViewById(R.id.btnDeletePL)
        btnHardDelete = view.findViewById(R.id.btnHardDeletePL)
        progress      = view.findViewById(R.id.progressDetail)

        if (ProfileManager.currentProfile() == ProfileManager.Profile.DEV) {
            btnHardDelete.visibility = View.VISIBLE
            btnHardDelete.setOnClickListener { confirmHardDelete() }
        }

        btnEdit.setOnClickListener {
            val bundle = Bundle().apply { putLong("packingListId", packingListId) }
            findNavController().navigate(R.id.action_packingListDetailFragment_to_editPackingListFragment, bundle)
        }
        btnDelete.setOnClickListener { confirmDelete() }
        btnAddSpool.setOnClickListener { showAddSpoolDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val pl = ServiceLocator.smsPackingListDao.getById(packingListId)
            if (pl == null) {
                findNavController().navigateUp()
                return@launch
            }
            txtName.text     = pl.packing_list_name.ifBlank { "PL ${pl.packing_list_id}" }
            txtDate.text     = pl.packing_date.take(10).ifBlank { "—" }
            txtNotes.text    = pl.notes?.ifBlank { "—" } ?: "—"
            txtVehicle.text  = pl.vehicle_plate?.ifBlank { "—" } ?: "—"
            val position     = pl.position_id?.let { ServiceLocator.smsPositionDao.getById(it) }
            txtPosition.text = position?.let { it.name.ifBlank { it.code } } ?: "—"
            progress.visibility = View.GONE
            loadSpools(pl)
        }
    }

    private fun loadSpools(pl: SmsPackingListEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            layoutSpools.removeAllViews()
            layoutSpools.addView(makeStatusText(getString(R.string.pl_detail_loading_spools)))
            try {
                var spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                if (spools.isEmpty()) {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val resp = ServiceLocator.apiClient.getService()
                            .getPackingListSpools(projectCode, pl.packing_list_id.toString())
                        if (resp.isSuccessful) {
                            val raw = resp.body()?.string().orEmpty()
                            val entities = parseSpoolEntities(raw, projectId, pl.packing_list_id)
                            val activeSpools = entities.filter { it.is_active }
                            if (activeSpools.isNotEmpty()) ServiceLocator.smsSpoolDao.insertAll(activeSpools)
                        }
                        spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                    }
                }
                layoutSpools.removeAllViews()
                if (spools.isEmpty()) {
                    layoutSpools.addView(makeStatusText(getString(R.string.pl_detail_no_spools)))
                } else {
                    spools.forEach { addSpoolRow(it) }
                }
            } catch (e: Exception) {
                layoutSpools.removeAllViews()
                layoutSpools.addView(makeStatusText(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun makeStatusText(msg: String): TextView = TextView(requireContext()).apply {
        text = msg
        textSize = 13f
        setTextColor(requireContext().getColor(R.color.on_surface_variant))
    }

    private fun addSpoolRow(spool: SmsSpoolEntity) {
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp4 }
        }
        val txt = TextView(requireContext()).apply {
            text = "• ${spool.displayCode}" + (spool.line_code?.let { " — $it" } ?: "")
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.pl_detail_btn_remove_spool)
            isAllCaps = false
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.error))
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { confirmRemoveSpool(spool) }
        }
        row.addView(txt)
        row.addView(btn)
        layoutSpools.addView(row)
    }

    private fun showAddSpoolDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val available = ServiceLocator.smsSpoolDao.getWithoutPackingList(projectId)
            if (available.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.pl_detail_no_available_spools), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = available.map { s ->
                s.displayCode + (s.line_code?.let { " — $it" } ?: "")
            }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.pl_detail_add_spool_title))
                .setItems(labels) { _, which -> doAddSpool(available[which].spool_id) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun doAddSpool(spoolId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ServiceLocator.smsSpoolDao.updatePackingList(spoolId, packingListId)
                val nextSeq = ServiceLocator.smsSpoolDao.getByPackingList(packingListId).size
                val tempId = spoolId xor packingListId
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                ServiceLocator.smsPackingListSpoolDao.insert(
                    SmsPackingListSpoolEntity(
                        packing_list_spool_id = tempId,
                        packing_list_id = packingListId,
                        spool_id = spoolId,
                        sequence_number = nextSeq,
                        added_at = java.time.LocalDateTime.now().toString()
                    )
                )
                val newCount = ServiceLocator.smsSpoolDao.getByPackingList(packingListId).size
                ServiceLocator.smsPackingListDao.getById(packingListId)?.let { pl ->
                    ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(total_spools_count = newCount, synced = false)))
                }
                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        ServiceLocator.apiClient.getService().addSpoolToPackingList(
                            projectCode, packingListId,
                            AssignSpoolRequest(spoolId, "API", nextSeq)
                        )
                        syncSpoolCountWithServer(projectCode, newCount)
                    }
                } catch (_: Exception) { /* offline */ }

                Toast.makeText(requireContext(), getString(R.string.pl_detail_spool_added), Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRemoveSpool(spool: SmsSpoolEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pl_detail_remove_spool_title))
            .setMessage(getString(R.string.pl_detail_remove_spool_msg, spool.displayCode))
            .setPositiveButton(android.R.string.ok) { _, _ -> doRemoveSpool(spool.spool_id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRemoveSpool(spoolId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ServiceLocator.smsSpoolDao.updatePackingList(spoolId, null)
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                val newCount = ServiceLocator.smsSpoolDao.getByPackingList(packingListId).size
                ServiceLocator.smsPackingListDao.getById(packingListId)?.let { pl ->
                    ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(total_spools_count = newCount, synced = false)))
                }
                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        ServiceLocator.apiClient.getService()
                            .removeSpoolFromPackingList(projectCode, packingListId, spoolId)
                        syncSpoolCountWithServer(projectCode, newCount)
                    }
                } catch (_: Exception) { /* offline */ }

                Toast.makeText(requireContext(), getString(R.string.pl_detail_spool_removed), Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun syncSpoolCountWithServer(projectCode: String, newCount: Int) {
        val pl = ServiceLocator.smsPackingListDao.getById(packingListId) ?: return
        val positionName = pl.position_id?.let { pid ->
            ServiceLocator.smsPositionDao.getAll().find { it.position_id == pid }?.name
        }
        val body = UpdatePackingListRequest(
            packingListId    = packingListId,
            packingListName  = pl.packing_list_name,
            vehicle          = pl.vehicle_plate,
            position         = positionName,
            positionId       = pl.position_id,
            packingDate      = pl.packing_date.takeIf { it.isNotBlank() },
            notes            = pl.notes,
            createdBy        = pl.created_by,
            updatedBy        = null,
            projectCode      = projectCode,
            totalSpoolsCount = newCount
        )
        val resp = ServiceLocator.apiClient.getService().updatePackingList(projectCode, body)
        if (resp.isSuccessful) {
            ServiceLocator.smsPackingListDao.markSynced(listOf(packingListId))
        }
    }

    private fun confirmHardDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pl_detail_confirm_hard_delete_title))
            .setMessage(getString(R.string.pl_detail_confirm_hard_delete_msg))
            .setPositiveButton(android.R.string.ok) { _, _ -> hardDeletePL() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hardDeletePL() {
        btnHardDelete.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                locallyDeletedPLIds.add(packingListId)
                val spools = ServiceLocator.smsSpoolDao.getByPackingList(packingListId)
                spools.forEach { ServiceLocator.smsSpoolDao.updatePackingList(it.spool_id, null) }
                ServiceLocator.smsPackingListSpoolDao.deleteByPackingList(packingListId)
                ServiceLocator.smsPackingListDao.deleteById(packingListId)

                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val r = ServiceLocator.apiClient.getService().hardDeletePackingList(projectCode, packingListId)
                        if (!r.isSuccessful && isAdded) {
                            Toast.makeText(requireContext(), "API hard delete: HTTP ${r.code()}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (_: Exception) { /* offline */ }
                ServiceLocator.smsPackingListDao.deleteById(packingListId)

                Toast.makeText(requireContext(), getString(R.string.pl_detail_hard_deleted), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnHardDelete.isEnabled = true
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseSpoolEntities(
        raw: String, projectId: Int, packingListId: Long
    ): List<SmsSpoolEntity> {
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> listOf("data", "items", "results", "spools").asSequence()
                    .mapNotNull { el.asJsonObject.get(it) }
                    .firstOrNull { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
            val gson = Gson()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SpoolDto::class.java)
                    val entity = dto.toEntity(defaultPackingListId = packingListId)
                    if (entity.spool_id == 0L) null else entity.copy(project_id = projectId)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pl_detail_confirm_delete_title))
            .setMessage(getString(R.string.pl_detail_confirm_delete_msg))
            .setPositiveButton(android.R.string.ok) { _, _ -> deletePL() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePL() {
        btnDelete.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                locallyDeletedPLIds.add(packingListId)
                val spools = ServiceLocator.smsSpoolDao.getByPackingList(packingListId)
                spools.forEach { ServiceLocator.smsSpoolDao.updatePackingList(it.spool_id, null) }
                ServiceLocator.smsPackingListSpoolDao.deleteByPackingList(packingListId)
                ServiceLocator.smsPackingListDao.deleteById(packingListId)

                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        ServiceLocator.apiClient.getService().deletePackingList(projectCode, packingListId)
                    }
                } catch (_: Exception) { /* offline */ }
                // Re-delete locally in case auto-sync re-imported the record while the API call was in-flight
                ServiceLocator.smsPackingListDao.deleteById(packingListId)

                Toast.makeText(requireContext(), getString(R.string.pl_detail_deleted), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnDelete.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.pl_detail_error_delete, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
