package com.example.hassiwrapper.ui.createspool

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEventEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolLocationEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolPropertyEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolStatusFlagsEntity
import com.example.hassiwrapper.services.GpsHelper
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.example.hassiwrapper.jBool
import com.example.hassiwrapper.jDbl
import com.example.hassiwrapper.jInt
import com.example.hassiwrapper.jLong
import com.example.hassiwrapper.jStr
import com.example.hassiwrapper.smsJsonArray
import android.util.Log
import com.example.hassiwrapper.data.db.entities.SmsPackingListSpoolEntity
import com.example.hassiwrapper.network.dto.AssignSpoolRequest
import com.example.hassiwrapper.services.OutboxService
import com.google.gson.JsonParser
import com.example.hassiwrapper.ProfileManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SpoolDetailBottomSheet : BottomSheetDialogFragment() {

    var onSpoolUpdated: (() -> Unit)? = null

    private var mapView: MapView? = null

    private var pendingGpsAction: (() -> Unit)? = null
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingGpsAction
        pendingGpsAction = null
        if (grants.values.any { it }) {
            action?.invoke()
        } else if (isAdded) {
            Toast.makeText(requireContext(), R.string.spool_detail_gps_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "SpoolDetail"
        private const val ARG_SPOOL_ID = "spool_id"

        /** Spools deleted locally this session — syncSmsData filters these out to prevent re-insertion.
         *  ConcurrentHashMap.newKeySet: written from Main (deleteSpool) and read from Dispatchers.Default
         *  (syncSmsData parallel launch), so must be thread-safe. */
        val locallyDeletedSpoolIds: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun newInstance(spoolId: Long): SpoolDetailBottomSheet =
            SpoolDetailBottomSheet().apply {
                arguments = Bundle().apply { putLong(ARG_SPOOL_ID, spoolId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        val view = inflater.inflate(R.layout.fragment_spool_detail_bottom_sheet, container, false)
        mapView = view.findViewById<MapView>(R.id.mapViewSpool).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            // Thumbnail-sized preview inside a scrolling sheet — block pan/zoom gestures so
            // touches fall through to the sheet's own scroll instead of dragging the map.
            setOnTouchListener { _, _ -> true }
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spoolId = arguments?.getLong(ARG_SPOOL_ID) ?: return
        view.findViewById<MaterialButton>(R.id.btnDeleteSpool).setOnClickListener {
            confirmDeleteSpool(spoolId)
        }
        val btnHardDelete = view.findViewById<MaterialButton>(R.id.btnHardDeleteSpool)
        if (ProfileManager.currentUserRole() == ProfileManager.UserRole.DEV) {
            btnHardDelete.visibility = View.VISIBLE
            btnHardDelete.setOnClickListener { confirmHardDeleteSpool(spoolId) }
        }
        view.findViewById<MaterialButton>(R.id.btnUpdateGps).setOnClickListener {
            val action: () -> Unit = { viewLifecycleOwner.lifecycleScope.launch { captureAndSaveGps(view, spoolId) } }
            val hasFine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                action()
            } else {
                pendingGpsAction = action
                requestLocationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
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
                val spool = ServiceLocator.smsSpoolDao.getById(spoolId)
                val plId = spool?.packing_list_id
                val isSynced = spool?.synced == true
                val projectId = spool?.project_id ?: (ServiceLocator.configRepo.getInt("selected_project_id") ?: 6)

                // Delete locally now; if the spool exists on the server, queue the delete so
                // it syncs (and survives an app restart) when the connection returns. No more
                // blocking the user offline — the outbox drains in order on the next sync.
                locallyDeletedSpoolIds.add(spoolId)
                if (isSynced) {
                    if (plId != null) {
                        ServiceLocator.outboxService.enqueue(
                            OutboxService.Entity.PL_ASSIGN, OutboxService.Op.UNASSIGN, spoolId, projectId, refEntityId = plId
                        )
                    }
                    ServiceLocator.outboxService.enqueue(
                        OutboxService.Entity.SPOOL, OutboxService.Op.DELETE, spoolId, projectId
                    )
                }
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                ServiceLocator.smsSpoolLocationDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolStatusFlagsDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolPropertyDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolEventDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolDao.deleteById(spoolId)
                if (plId != null) refreshPlSpoolCount(plId)
                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.SPOOL_ELIMINADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_SPOOL,
                    spoolId, spool?.displayCode ?: getString(R.string.spool_detail_label_fallback, spoolId), projectId = projectId
                )
                // UI last — dismiss() cancels viewLifecycleOwner so it must be final
                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_deleted), android.widget.Toast.LENGTH_SHORT).show()
                onSpoolUpdated?.invoke()
                dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "deleteSpool error", e)
                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_error_delete, e.message), android.widget.Toast.LENGTH_LONG).show()
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
                val spool = ServiceLocator.smsSpoolDao.getById(spoolId)
                val plId = spool?.packing_list_id
                val isSynced = spool?.synced == true
                val projectId = spool?.project_id ?: (ServiceLocator.configRepo.getInt("selected_project_id") ?: 6)

                // Delete locally now; queue the hard-delete for synced spools so it survives
                // offline + app restart and drains in order on the next sync.
                locallyDeletedSpoolIds.add(spoolId)
                if (isSynced) {
                    if (plId != null) {
                        ServiceLocator.outboxService.enqueue(
                            OutboxService.Entity.PL_ASSIGN, OutboxService.Op.UNASSIGN, spoolId, projectId, refEntityId = plId
                        )
                    }
                    ServiceLocator.outboxService.enqueue(
                        OutboxService.Entity.SPOOL, OutboxService.Op.HARD_DELETE, spoolId, projectId
                    )
                }
                ServiceLocator.smsPackingListSpoolDao.deleteBySpoolId(spoolId)
                ServiceLocator.smsSpoolLocationDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolStatusFlagsDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolPropertyDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolEventDao.deleteBySpool(spoolId)
                ServiceLocator.smsSpoolDao.deleteById(spoolId)
                if (plId != null) refreshPlSpoolCount(plId)
                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.SPOOL_ELIMINADO_HARD,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_SPOOL,
                    spoolId, spool?.displayCode ?: getString(R.string.spool_detail_label_fallback, spoolId), projectId = projectId
                )
                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_hard_deleted), android.widget.Toast.LENGTH_SHORT).show()
                onSpoolUpdated?.invoke()
                dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "hardDeleteSpool error", e)
                if (isAdded) android.widget.Toast.makeText(requireContext(), getString(R.string.spool_detail_error_generic, e.message), android.widget.Toast.LENGTH_LONG).show()
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
            bindGpsLocations(view, spoolId)
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
        s.sit_number?.let { addRow(layout, R.string.spool_detail_field_sit_number, it) }
        s.revision?.let { addRow(layout, R.string.spool_detail_field_revision, it) }
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
            flags.sub_position_id?.let { subId ->
                val sub = ServiceLocator.smsSubPositionDao.getById(subId)
                addRow(layout, R.string.spool_detail_field_sub_position,
                    sub?.run { full_path.ifBlank { name.ifBlank { code } } } ?: subId.toString())
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
        val count = ServiceLocator.smsSpoolDao.countByPackingList(plId)
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
                val tempId = ServiceLocator.smsPackingListSpoolDao.getMaxId()?.let { it + 1 } ?: spoolId
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

            // Queue the assignment change so it syncs offline + survives an app restart.
            // Drain resolves negative temp PL ids once their CREATE op lands.
            // When switching PLs, enqueue UNASSIGN from old PL first so the server removes the
            // spool from its previous PL before adding it to the new one (prevents duplicates).
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            if (newPlId != null) {
                if (oldPlId != null && oldPlId != newPlId) {
                    ServiceLocator.outboxService.enqueue(
                        OutboxService.Entity.PL_ASSIGN, OutboxService.Op.UNASSIGN, spoolId, projectId,
                        refEntityId = oldPlId
                    )
                }
                ServiceLocator.outboxService.enqueue(
                    OutboxService.Entity.PL_ASSIGN, OutboxService.Op.ASSIGN, spoolId, projectId,
                    refEntityId = newPlId,
                    payload = AssignSpoolRequest(spoolId, "API", nextSequence)
                )
            } else if (oldPlId != null) {
                ServiceLocator.outboxService.enqueue(
                    OutboxService.Entity.PL_ASSIGN, OutboxService.Op.UNASSIGN, spoolId, projectId,
                    refEntityId = oldPlId
                )
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
                var flags = parseSpoolStatusFlags(raw, s.spool_id)
                // If the local spool has a pending offline relocation (synced=false), the server
                // still returns the old position/sub-position.  Override with local values so the
                // detail sheet shows the correct state until the retry upload flips synced=true.
                if (!s.synced && flags != null) {
                    flags = flags.copy(
                        position_id     = s.position_id ?: flags.position_id,
                        sub_position_id = s.sub_position_id ?: flags.sub_position_id
                    )
                    Log.d(TAG, "statusFlags: local override pos=${flags.position_id} sub=${flags.sub_position_id} (spool synced=false)")
                }
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
                sub_position_id = o.jLong("subPositionId", "sub_position_id"),
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

    private suspend fun bindGpsLocations(view: View, spoolId: Long) {
        val locations = ServiceLocator.smsSpoolLocationDao.getBySpool(spoolId)
        val txtNoData  = view.findViewById<TextView>(R.id.txtGpsNoData)
        val txtCurrent = view.findViewById<TextView>(R.id.txtGpsCurrent)
        val txtPrev    = view.findViewById<TextView>(R.id.txtGpsPrevious)
        val mv         = mapView ?: return
        val btnOpenMaps = view.findViewById<MaterialButton>(R.id.btnOpenMaps)

        if (locations.isEmpty()) {
            txtNoData.visibility  = View.VISIBLE
            txtCurrent.visibility = View.GONE
            txtPrev.visibility    = View.GONE
            mv.visibility         = View.GONE
            btnOpenMaps.visibility = View.GONE
            return
        }

        txtNoData.visibility  = View.GONE
        val current = locations[0]
        val previous = locations.getOrNull(1)

        txtCurrent.text = if (current.gps_accuracy_m != null) {
            getString(
                R.string.spool_detail_gps_current,
                current.latitude, current.longitude,
                current.gps_accuracy_m, current.captured_at.take(10)
            )
        } else {
            getString(
                R.string.spool_detail_gps_current_no_acc,
                current.latitude, current.longitude, current.captured_at.take(10)
            )
        }
        txtCurrent.visibility = View.VISIBLE

        if (previous != null) {
            txtPrev.text = if (previous.gps_accuracy_m != null) {
                getString(
                    R.string.spool_detail_gps_previous,
                    previous.latitude, previous.longitude,
                    previous.gps_accuracy_m, previous.captured_at.take(10)
                )
            } else {
                getString(
                    R.string.spool_detail_gps_previous_no_acc,
                    previous.latitude, previous.longitude, previous.captured_at.take(10)
                )
            }
            txtPrev.visibility = View.VISIBLE
        }

        btnOpenMaps.visibility = View.VISIBLE
        btnOpenMaps.setOnClickListener {
            val uri = Uri.parse("geo:${current.latitude},${current.longitude}?q=${current.latitude},${current.longitude}")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        // Show map with pins
        mv.visibility = View.VISIBLE
        mv.overlays.clear()

        val currentPoint = GeoPoint(current.latitude, current.longitude)
        mv.overlays.add(Marker(mv).apply {
            position = currentPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Actual"
        })
        if (previous != null) {
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(previous.latitude, previous.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Anterior"
            })
        }
        mv.controller.setZoom(19.0)
        mv.controller.setCenter(currentPoint)
        mv.invalidate()
    }

    private suspend fun captureAndSaveGps(view: View, spoolId: Long) {
        val btn = view.findViewById<MaterialButton>(R.id.btnUpdateGps)
        btn.isEnabled = false
        btn.text = getString(R.string.spool_detail_gps_capturing)
        try {
            val gps = GpsHelper.getCurrentLocation(requireContext())
            if (gps == null) {
                Toast.makeText(requireContext(), R.string.spool_detail_gps_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            val (lat, lon, acc) = gps
            val loc = SmsSpoolLocationEntity(
                spool_id       = spoolId,
                latitude       = lat,
                longitude      = lon,
                gps_accuracy_m = acc,
                captured_at    = GpsHelper.capturedAtNow(),
                captured_by    = ServiceLocator.configRepo.get("device_name")
            )
            ServiceLocator.smsSpoolLocationDao.insert(loc)
            ServiceLocator.smsSpoolLocationDao.pruneOldest(spoolId)
            Toast.makeText(requireContext(), R.string.spool_detail_gps_saved, Toast.LENGTH_SHORT).show()

            // Refresh map display
            mapView?.overlays?.clear()
            bindGpsLocations(view, spoolId)
        } catch (e: Exception) {
            Log.e(TAG, "captureAndSaveGps error", e)
            if (isAdded) Toast.makeText(requireContext(), getString(R.string.spool_detail_error_generic, e.message), Toast.LENGTH_LONG).show()
        } finally {
            btn.isEnabled = true
            btn.text = getString(R.string.spool_detail_btn_update_gps)
        }
    }

    override fun onDestroyView() {
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
        onSpoolUpdated = null
    }
}
