package com.example.hassiwrapper.ui.createspool

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEventEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolPropertyEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolStatusFlagsEntity
import com.example.hassiwrapper.jBool
import com.example.hassiwrapper.jDbl
import com.example.hassiwrapper.jInt
import com.example.hassiwrapper.jLong
import com.example.hassiwrapper.jStr
import com.example.hassiwrapper.smsJsonArray
import android.util.Log
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.google.gson.JsonParser
import com.example.hassiwrapper.ProfileManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SpoolDetailBottomSheet : BottomSheetDialogFragment() {

    var onSpoolUpdated: (() -> Unit)? = null

    companion object {
        private const val TAG = "SpoolDetail"
        private const val ARG_SPOOL_ID = "spool_id"

        /** Spools deleted locally this session — syncSmsData filters these out to prevent re-insertion. */
        val locallyDeletedSpoolIds = mutableSetOf<Long>()

        fun newInstance(spoolId: Long): SpoolDetailBottomSheet =
            SpoolDetailBottomSheet().apply {
                arguments = Bundle().apply { putLong(ARG_SPOOL_ID, spoolId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_spool_detail_bottom_sheet, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spoolId = arguments?.getLong(ARG_SPOOL_ID) ?: return
        view.findViewById<MaterialButton>(R.id.btnDeleteSpool).setOnClickListener {
            confirmDeleteSpool(spoolId)
        }
        val btnHardDelete = view.findViewById<MaterialButton>(R.id.btnHardDeleteSpool)
        if (ProfileManager.currentProfile() == ProfileManager.Profile.DEV) {
            btnHardDelete.visibility = View.VISIBLE
            btnHardDelete.setOnClickListener { confirmHardDeleteSpool(spoolId) }
        }
        viewLifecycleOwner.lifecycleScope.launch { loadAndBind(view, spoolId) }
    }

    private fun confirmDeleteSpool(spoolId: Long) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.spool_detail_confirm_delete_title))
            .setMessage(getString(R.string.spool_detail_confirm_delete_msg))
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteSpool(spoolId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSpool(spoolId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Read before deleting — needed for API unlink call
                val plId = ServiceLocator.smsSpoolDao.getById(spoolId)?.packing_list_id
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code

                // Local deletion
                locallyDeletedSpoolIds.add(spoolId)
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                ServiceLocator.smsSpoolDao.deleteById(spoolId)
                if (plId != null) refreshPlSpoolCount(plId)

                // API sync — before dismiss() so the coroutine scope stays alive
                if (!projectCode.isNullOrBlank()) {
                    if (plId != null) {
                        try {
                            val r = ServiceLocator.apiClient.getService().removeSpoolFromPackingList(projectCode, plId, spoolId)
                            Log.d(TAG, "removeSpoolFromPL → HTTP ${r.code()}")
                        } catch (e: Exception) { Log.w(TAG, "removeSpoolFromPackingList failed", e) }
                    }
                    try {
                        val r = ServiceLocator.apiClient.getService().deleteSpool(projectCode, spoolId)
                        Log.d(TAG, "deleteSpool → HTTP ${r.code()} body=${r.errorBody()?.string()?.take(200)}")
                        if (!r.isSuccessful && isAdded) {
                            android.widget.Toast.makeText(requireContext(), "API eliminar spool: HTTP ${r.code()}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) { Log.w(TAG, "deleteSpool API failed", e) }
                    // Re-delete locally in case auto-sync re-imported the record while the API call was in-flight
                    ServiceLocator.smsSpoolDao.deleteById(spoolId)
                }

                // UI last — dismiss() cancels viewLifecycleOwner so it must be final
                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_deleted), android.widget.Toast.LENGTH_SHORT).show()
                onSpoolUpdated?.invoke()
                dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "deleteSpool error", e)
                if (isAdded) android.widget.Toast.makeText(requireContext(), "Error al eliminar: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmHardDeleteSpool(spoolId: Long) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.spool_detail_confirm_hard_delete_title))
            .setMessage(getString(R.string.spool_detail_confirm_hard_delete_msg))
            .setPositiveButton(android.R.string.ok) { _, _ -> hardDeleteSpool(spoolId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hardDeleteSpool(spoolId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val plId = ServiceLocator.smsSpoolDao.getById(spoolId)?.packing_list_id
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code

                locallyDeletedSpoolIds.add(spoolId)
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                ServiceLocator.smsSpoolDao.deleteById(spoolId)
                if (plId != null) refreshPlSpoolCount(plId)

                if (!projectCode.isNullOrBlank()) {
                    if (plId != null) {
                        try {
                            ServiceLocator.apiClient.getService().removeSpoolFromPackingList(projectCode, plId, spoolId)
                        } catch (e: Exception) { Log.w(TAG, "hardDelete: removeSpoolFromPL failed", e) }
                    }
                    try {
                        val r = ServiceLocator.apiClient.getService().hardDeleteSpool(projectCode, spoolId)
                        Log.d(TAG, "hardDeleteSpool → HTTP ${r.code()} body=${r.errorBody()?.string()?.take(200)}")
                        if (!r.isSuccessful && isAdded) {
                            android.widget.Toast.makeText(requireContext(), "API hard delete: HTTP ${r.code()}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) { Log.w(TAG, "hardDeleteSpool API failed", e) }
                    ServiceLocator.smsSpoolDao.deleteById(spoolId)
                }

                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_hard_deleted), android.widget.Toast.LENGTH_SHORT).show()
                onSpoolUpdated?.invoke()
                dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "hardDeleteSpool error", e)
                if (isAdded) android.widget.Toast.makeText(requireContext(), "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun loadAndBind(view: View, spoolId: Long) {
        val s = ServiceLocator.smsSpoolDao.getById(spoolId) ?: run {
            Log.e(TAG, "spool $spoolId not found in DB"); return
        }
        Log.d(TAG, "loadAndBind: spool=${s.displayCode} id=${s.spool_id} area=${s.area_id} spec=${s.spec_id} unit=${s.unit_id} iso=${s.iso_type_id} sub=${s.subcontractor_id}")

        fetchSpoolDetail(s)

        // Re-load fresh after fetch (property/flags/events may now be in DB)
        val sRefresh = ServiceLocator.smsSpoolDao.getById(spoolId) ?: s
        val flags = ServiceLocator.smsSpoolStatusFlagsDao.getBySpool(sRefresh.spool_id)
        val statusEntity = flags?.status_id?.let { ServiceLocator.smsSpoolStatusDao.getById(it) }
            ?: sRefresh.status?.takeIf { it.isNotBlank() }?.let { ServiceLocator.smsSpoolStatusDao.getByCode(it) }
        Log.d(TAG, "post-fetch sRefresh: status=${sRefresh.status} priority=${sRefresh.priority} zone=${sRefresh.zone} unit=${sRefresh.assigned_unit} desc=${sRefresh.description} plName=${sRefresh.packing_list_name}")
        Log.d(TAG, "post-fetch DB: flags=$flags events=${ServiceLocator.smsSpoolEventDao.getBySpool(sRefresh.spool_id).size} specs=${ServiceLocator.smsSpecDao.getByProject(sRefresh.project_id).size}")

        view.findViewById<TextView>(R.id.txtDetailTitle).text =
            sRefresh.displayCode.ifBlank { "Spool ${sRefresh.spool_id}" }

        val statusView = view.findViewById<TextView>(R.id.txtDetailStatus)
        val statusText = statusEntity?.run { name.ifBlank { code } } ?: sRefresh.status?.takeIf { it.isNotBlank() }
        if (statusText != null) {
            statusView.text = statusText
            statusView.visibility = View.VISIBLE
        }

        try {
            Log.d(TAG, "binding identification")
            bindIdentification(view, sRefresh)
            Log.d(TAG, "binding properties")
            bindProperties(view, sRefresh)
            Log.d(TAG, "binding status")
            bindStatusFlags(view, flags, statusEntity)
            Log.d(TAG, "binding packing list")
            bindPackingList(view, sRefresh)
            Log.d(TAG, "binding events")
            bindEvents(view, sRefresh)
            Log.d(TAG, "all binds done")
        } catch (e: Exception) {
            Log.e(TAG, "BIND CRASH", e)
        }

        // Hide progress, show content, re-expand sheet now that content is populated
        view.findViewById<android.widget.ProgressBar>(R.id.progressDetail).visibility = View.GONE
        view.findViewById<android.widget.LinearLayout>(R.id.layoutContent).visibility = View.VISIBLE
        view.post {
            if (isAdded) {
                (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
                view.requestLayout()
            }
        }
    }

    private suspend fun bindIdentification(view: View, s: SmsSpoolEntity) {
        val layout = view.findViewById<LinearLayout>(R.id.layoutId)

        s.description?.let { addRow(layout, R.string.spool_detail_field_description, it) }
        s.priority?.let { addRow(layout, R.string.spool_detail_field_priority, it) }
        s.zone?.let { addRow(layout, R.string.spool_detail_field_zone, it) }
        s.assigned_unit?.let { addRow(layout, R.string.spool_detail_field_unit, it) }
        s.line_code?.let { addRow(layout, R.string.spool_detail_field_line, it) }
        s.service?.let { addRow(layout, R.string.spool_detail_field_service, it) }
        s.train?.let { addRow(layout, R.string.spool_detail_field_train, it) }
        s.module?.let { addRow(layout, R.string.spool_detail_field_module, it) }
        s.iso_revision_date?.let { addRow(layout, R.string.spool_detail_field_iso_date, it) }
        if (s.in_transit) addRow(layout, R.string.spool_detail_field_in_transit, getString(R.string.spool_detail_yes))

        // DB-resolved lookups (populated when API returns IDs)
        s.area_id?.let { id -> ServiceLocator.smsAreaDao.getById(id)?.let { addRow(layout, R.string.spool_detail_field_area, it.name) } }
        s.spec_id?.let { id -> ServiceLocator.smsSpecDao.getById(id)?.let {
            addRow(layout, R.string.spool_detail_field_spec, if (it.description.isNullOrBlank()) it.code else "${it.code} — ${it.description}")
        } }
        s.subcontractor_id?.let { id -> ServiceLocator.smsSubcontractorDao.getById(id)?.let { addRow(layout, R.string.spool_detail_field_subcontractor, it.name) } }
        s.iso_type_id?.let { id -> ServiceLocator.smsIsoTypeDao.getById(id)?.let { addRow(layout, R.string.spool_detail_field_iso_type, it.name.ifBlank { it.code }) } }

        s.updated_at?.let { addRow(layout, R.string.spool_detail_field_updated, "$it · ${s.updated_by.orEmpty()}") }
            ?: s.created_at.takeIf { it.isNotBlank() }?.let { addRow(layout, R.string.spool_detail_field_created, "$it · ${s.created_by}") }
    }

    private suspend fun bindProperties(view: View, s: SmsSpoolEntity) {
        val card = view.findViewById<View>(R.id.cardProperties)
        val layout = view.findViewById<LinearLayout>(R.id.layoutProperties)
        val props = ServiceLocator.smsSpoolPropertyDao.getBySpool(s.spool_id)
        if (props != null) {
            props.diameter_inches?.let { addRow(layout, R.string.spool_detail_field_diameter_in, "$it\"") }
            props.diameter?.let { addRow(layout, R.string.spool_detail_field_diameter, it.toString()) }
            props.bore_size_id?.let { bsId ->
                val bs = ServiceLocator.smsBoreSizeDao.getById(bsId)
                addRow(layout, R.string.spool_detail_field_bore_size, bs?.run { name.ifBlank { code } } ?: bsId.toString())
            }
            props.weight_kg?.let { addRow(layout, R.string.spool_detail_field_weight, "$it kg") }
        }
        if (layout.childCount == 0) card.visibility = View.GONE
    }

    private suspend fun bindStatusFlags(
        view: View,
        flags: com.example.hassiwrapper.data.db.entities.SmsSpoolStatusFlagsEntity?,
        statusEntity: com.example.hassiwrapper.data.db.entities.SmsSpoolStatusEntity?
    ) {
        val card = view.findViewById<View>(R.id.cardStatus)
        val layout = view.findViewById<LinearLayout>(R.id.layoutStatus)
        if (flags != null) {
            statusEntity?.let { addRow(layout, R.string.spool_detail_field_status, it.name.ifBlank { it.code }) }
            flags.incomplete_status_id?.let { isId ->
                ServiceLocator.smsIncompleteStatusDao.getById(isId)?.let {
                    addRow(layout, R.string.spool_detail_field_incomplete_status, it.name.ifBlank { it.code })
                }
            }
            flags.position_id?.let { posId ->
                val pos = ServiceLocator.smsPositionDao.getById(posId)
                addRow(layout, R.string.spool_detail_field_position, pos?.run { name.ifBlank { code } } ?: posId.toString())
            }
            if (flags.hold) addRow(layout, R.string.spool_detail_field_hold, getString(R.string.spool_detail_yes))
            if (flags.damaged) addRow(layout, R.string.spool_detail_field_damaged, getString(R.string.spool_detail_yes))
            if (flags.returned_to_factory) addRow(layout, R.string.spool_detail_field_returned, getString(R.string.spool_detail_yes))
            if (flags.position_status_discrepancy) addRow(layout, R.string.spool_detail_field_pos_discrepancy, getString(R.string.spool_detail_yes))
            if (flags.review_discrepancy) addRow(layout, R.string.spool_detail_field_review_discrepancy, getString(R.string.spool_detail_yes))
            flags.last_event_date?.let { addRow(layout, R.string.spool_detail_field_last_event, it) }
            flags.pca_status_date?.let { addRow(layout, R.string.spool_detail_field_pca_status, it) }
            flags.pca_entry_date?.let { addRow(layout, R.string.spool_detail_field_pca_entry, it) }
        }
        if (layout.childCount == 0) card.visibility = View.GONE
    }

    private suspend fun bindPackingList(view: View, s: SmsSpoolEntity) {
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val packingLists = ServiceLocator.smsPackingListDao.getByProject(projectId)
        val currentPl = s.packing_list_id?.let { plId -> packingLists.find { it.packing_list_id == plId } }
        val txtCurrentPl = view.findViewById<TextView>(R.id.txtCurrentPackingList)
        txtCurrentPl.text = currentPl?.packing_list_name ?: getString(R.string.spool_item_pl_none)

        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemovePackingList)
        btnRemove.visibility = if (s.packing_list_id != null) View.VISIBLE else View.GONE
        btnRemove.setOnClickListener {
            doAssign(s.spool_id, null, null, txtCurrentPl, btnRemove)
        }

        view.findViewById<MaterialButton>(R.id.btnAssignPackingList).setOnClickListener {
            showAssignDialog(s, packingLists, txtCurrentPl, btnRemove)
        }
    }

    private suspend fun bindEvents(view: View, s: SmsSpoolEntity) {
        val layout = view.findViewById<LinearLayout>(R.id.layoutEvents)
        val events = ServiceLocator.smsSpoolEventDao.getBySpool(s.spool_id)
        if (events.isNotEmpty()) {
            val dp6 = (6 * resources.displayMetrics.density).toInt()
            events.forEach { ev ->
                layout.addView(TextView(requireContext()).apply {
                    text = buildString {
                        append("${ev.event_date}  ${ev.event_type}")
                        if (!ev.old_value.isNullOrBlank() || !ev.new_value.isNullOrBlank()) {
                            append("  ${ev.old_value.orEmpty()} → ${ev.new_value.orEmpty()}")
                        }
                        ev.source?.let { append("  [$it]") }
                    }
                    textSize = 12f
                    setTextColor(requireContext().getColor(R.color.on_surface))
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .apply { bottomMargin = dp6 }
                })
            }
        } else {
            view.findViewById<TextView>(R.id.txtEventsEmpty).visibility = View.VISIBLE
        }
    }

    private fun showAssignDialog(
        spool: SmsSpoolEntity,
        packingLists: List<SmsPackingListEntity>,
        txtCurrentPl: TextView,
        btnRemove: MaterialButton
    ) {
        val names = packingLists.map { it.packing_list_name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.spool_detail_assign_pl_title)
            .setItems(names) { _, which ->
                val pl = packingLists[which]
                doAssign(spool.spool_id, pl.packing_list_id, pl.packing_list_name, txtCurrentPl, btnRemove)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun refreshPlSpoolCount(plId: Long) {
        val count = ServiceLocator.smsSpoolDao.getByPackingList(plId).size
        ServiceLocator.smsPackingListDao.getById(plId)?.let { pl ->
            ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(total_spools_count = count, synced = false)))
        }
    }

    private fun doAssign(spoolId: Long, newPlId: Long?, plName: String?, txtCurrentPl: TextView, btnRemove: MaterialButton) {
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(TAG, "doAssign START: spoolId=$spoolId newPlId=$newPlId")

            val oldPlId = ServiceLocator.smsSpoolDao.getById(spoolId)?.packing_list_id
            Log.d(TAG, "doAssign: oldPlId=$oldPlId")

            // Local DB: sms_spool
            ServiceLocator.smsSpoolDao.updatePackingList(spoolId, newPlId)
            if (oldPlId != null) refreshPlSpoolCount(oldPlId)
            if (newPlId != null) refreshPlSpoolCount(newPlId)
            val spoolAfterUpdate = ServiceLocator.smsSpoolDao.getById(spoolId)
            Log.d(TAG, "doAssign: sms_spool after update → packing_list_id=${spoolAfterUpdate?.packing_list_id} synced=${spoolAfterUpdate?.synced}")

            // Local DB: junction table
            val junctionBefore = ServiceLocator.smsPackingListSpoolDao.getByPackingList(newPlId ?: oldPlId ?: 0L)
            Log.d(TAG, "doAssign: junction table BEFORE delete (pl=${newPlId ?: oldPlId}): ${junctionBefore.map { "id=${it.packing_list_spool_id} spool=${it.spool_id} seq=${it.sequence_number}" }}")

            ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
            Log.d(TAG, "doAssign: deleteBySpoolId($spoolId) done")

            val nextSequence = if (newPlId != null)
                ServiceLocator.smsSpoolDao.getByPackingList(newPlId).size
            else 0
            Log.d(TAG, "doAssign: nextSequence=$nextSequence (from sms_spool.getByPackingList($newPlId))")

            if (newPlId != null) {
                val tempId = spoolId xor newPlId
                val entity = SmsPackingListSpoolEntity(
                    packing_list_spool_id = tempId,
                    packing_list_id = newPlId,
                    spool_id = spoolId,
                    sequence_number = nextSequence,
                    added_at = java.time.LocalDateTime.now().toString()
                )
                Log.d(TAG, "doAssign: inserting junction entity → $entity")
                ServiceLocator.smsPackingListSpoolDao.insert(entity)

                val junctionAfter = ServiceLocator.smsPackingListSpoolDao.getByPackingList(newPlId)
                Log.d(TAG, "doAssign: junction table AFTER insert (pl=$newPlId): ${junctionAfter.map { "id=${it.packing_list_spool_id} spool=${it.spool_id} seq=${it.sequence_number}" }}")
            }

            txtCurrentPl.text = plName ?: getString(R.string.spool_item_pl_none)
            btnRemove.visibility = if (newPlId != null) View.VISIBLE else View.GONE

            // Remote API
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                Log.d(TAG, "doAssign: API call → projectCode=$projectCode newPlId=$newPlId oldPlId=$oldPlId sequenceNumber=$nextSequence")
                if (projectCode != null) {
                    val service = ServiceLocator.apiClient.getService()
                    val resp = when {
                        newPlId != null -> service.addSpoolToPackingList(projectCode, newPlId, AssignSpoolRequest(spoolId, "API", nextSequence))
                        oldPlId != null -> service.removeSpoolFromPackingList(projectCode, oldPlId, spoolId)
                        else -> null
                    }
                    val respBody = resp?.body()?.string()
                    Log.d(TAG, "doAssign: API response code=${resp?.code()} isSuccessful=${resp?.isSuccessful} body=${respBody?.take(300)}")
                    if (resp != null && !resp.isSuccessful) {
                        Log.e(TAG, "doAssign: API error body=${resp.errorBody()?.string()?.take(300)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "doAssign: API exception", e)
            }

            Log.d(TAG, "doAssign END")
            onSpoolUpdated?.invoke()
        }
    }

    private fun addRow(parent: LinearLayout, labelRes: Int, value: String) {
        val ctx = requireContext()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { bottomMargin = dp4 }
        }
        row.addView(TextView(ctx).apply {
            text = getString(labelRes)
            textSize = 12f
            setTextColor(ctx.getColor(R.color.on_surface_variant))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.38f)
        })
        row.addView(TextView(ctx).apply {
            text = value
            textSize = 13f
            setTextColor(ctx.getColor(R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.62f)
        })
        parent.addView(row)
    }

    private suspend fun fetchSpoolDetail(s: SmsSpoolEntity) {
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
            ?: run { Log.e(TAG, "fetchSpoolDetail: no projectCode for id=$projectId"); return }
        val service = try { ServiceLocator.apiClient.getService() } catch (e: Exception) {
            Log.e(TAG, "fetchSpoolDetail: getService failed", e); return
        }
        val pathId = s.spool_id.toString()
        Log.d(TAG, "fetchSpoolDetail: projectCode=$projectCode pathId=$pathId displayCode=${s.displayCode}")

        try {
            val resp = service.getSpoolProperty(projectCode, pathId)
            val raw = if (resp.isSuccessful) resp.body()?.string().orEmpty() else ""
            Log.d(TAG, "property HTTP ${resp.code()} raw(300): ${raw.take(300)}")
            if (resp.isSuccessful) {
                val prop = parseSpoolProperty(raw, s.spool_id)
                Log.d(TAG, "property parsed: $prop")
                prop?.let { ServiceLocator.smsSpoolPropertyDao.insertAll(listOf(it)) }
            } else {
                Log.w(TAG, "property error: ${resp.errorBody()?.string()?.take(200)}")
            }
        } catch (e: Exception) { Log.e(TAG, "property fetch exception", e) }

        try {
            val resp = service.getSpoolStatusFlags(projectCode, pathId)
            val raw = if (resp.isSuccessful) resp.body()?.string().orEmpty() else ""
            Log.d(TAG, "statusFlags HTTP ${resp.code()} raw(300): ${raw.take(300)}")
            if (resp.isSuccessful) {
                val flags = parseSpoolStatusFlags(raw, s.spool_id)
                Log.d(TAG, "statusFlags parsed: $flags")
                flags?.let { ServiceLocator.smsSpoolStatusFlagsDao.insertAll(listOf(it)) }
            } else {
                Log.w(TAG, "statusFlags error: ${resp.errorBody()?.string()?.take(200)}")
            }
        } catch (e: Exception) { Log.e(TAG, "statusFlags fetch exception", e) }

        try {
            val resp = service.getSpoolEvents(projectCode, pathId)
            val raw = if (resp.isSuccessful) resp.body()?.string().orEmpty() else ""
            Log.d(TAG, "events HTTP ${resp.code()} raw(300): ${raw.take(300)}")
            if (resp.isSuccessful) {
                val events = parseSpoolEvents(raw, s.spool_id)
                Log.d(TAG, "events parsed: ${events.size}")
                if (events.isNotEmpty()) {
                    ServiceLocator.smsSpoolEventDao.deleteBySpool(s.spool_id)
                    ServiceLocator.smsSpoolEventDao.insertAll(events)
                }
            } else {
                Log.w(TAG, "events error: ${resp.errorBody()?.string()?.take(200)}")
            }
        } catch (e: Exception) { Log.e(TAG, "events fetch exception", e) }
    }

    private fun parseSpoolProperty(raw: String, spoolId: Long): SmsSpoolPropertyEntity? {
        return try {
            val el = JsonParser.parseString(raw)
            val o = when {
                el.isJsonObject -> el.asJsonObject.let { obj ->
                    if (obj.has("data") && !obj.get("data").isJsonNull && obj.get("data").isJsonObject)
                        obj.getAsJsonObject("data") else obj
                }
                else -> return null
            }
            SmsSpoolPropertyEntity(
                spool_id = spoolId,
                diameter_inches = o.jDbl("diameterInches", "diameter_inches"),
                diameter = o.jDbl("diameter"),
                bore_size_id = o.jInt("boreSizeId", "bore_size_id"),
                weight_kg = o.jDbl("weightKg", "weight_kg"),
                updated_at = o.jStr("updatedAt", "updated_at").orEmpty()
            )
        } catch (_: Exception) { null }
    }

    private fun parseSpoolStatusFlags(raw: String, spoolId: Long): SmsSpoolStatusFlagsEntity? {
        return try {
            val el = JsonParser.parseString(raw)
            val o = when {
                el.isJsonObject -> el.asJsonObject.let { obj ->
                    if (obj.has("data") && !obj.get("data").isJsonNull && obj.get("data").isJsonObject)
                        obj.getAsJsonObject("data") else obj
                }
                else -> return null
            }
            SmsSpoolStatusFlagsEntity(
                spool_id = spoolId,
                status_id = o.jInt("statusId", "status_id"),
                incomplete_status_id = o.jInt("incompleteStatusId", "incomplete_status_id"),
                position_id = o.jInt("positionId", "position_id"),
                hold = o.jBool("hold") ?: false,
                damaged = o.jBool("damaged") ?: false,
                returned_to_factory = o.jBool("returnedToFactory", "returned_to_factory") ?: false,
                position_status_discrepancy = o.jBool("positionStatusDiscrepancy", "position_status_discrepancy") ?: false,
                review_discrepancy = o.jBool("reviewDiscrepancy", "review_discrepancy") ?: false,
                last_event_date = o.jStr("lastEventDate", "last_event_date"),
                pca_status_date = o.jStr("pcaStatusDate", "pca_status_date"),
                pca_entry_date = o.jStr("pcaEntryDate", "pca_entry_date"),
                updated_at = o.jStr("updatedAt", "updated_at").orEmpty()
            )
        } catch (_: Exception) { null }
    }

    private fun parseSpoolEvents(raw: String, spoolId: Long): List<SmsSpoolEventEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jLong("eventId", "event_id", "id") ?: return@mapNotNull null
                SmsSpoolEventEntity(
                    event_id = id,
                    event_date = o.jStr("eventDate", "event_date").orEmpty(),
                    spool_id = spoolId,
                    event_type = o.jStr("eventType", "event_type").orEmpty(),
                    old_value = o.jStr("oldValue", "old_value"),
                    new_value = o.jStr("newValue", "new_value"),
                    source = o.jStr("source"),
                    created_at = o.jStr("createdAt", "created_at").orEmpty(),
                    created_by = o.jStr("createdBy", "created_by").orEmpty()
                )
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        onSpoolUpdated = null
    }
}
