package com.example.hassiwrapper.ui.vehicles

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
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
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.UpdatePackingListRequest
import com.example.hassiwrapper.network.dto.parsePackingListConflictMessage
import com.example.hassiwrapper.services.OutboxService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class VehicleDetailFragment : Fragment() {

    companion object {
        private const val TAG = "VehicleDetail"
        val locallyDeletedVehicleIds = mutableSetOf<Long>()
    }

    private var vehicleId = 0L

    private lateinit var txtPlate: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtInfoPlate: TextView
    private lateinit var txtInfoName: TextView
    private lateinit var txtInfoCompany: TextView
    private lateinit var txtInfoType: TextView
    private lateinit var txtInfoCapacity: TextView
    private lateinit var layoutPackingLists: LinearLayout
    private lateinit var btnAddPackingList: MaterialButton
    private lateinit var btnEditVehicle: MaterialButton
    private lateinit var btnHardDelete: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicleId = arguments?.getLong("vehicleId") ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_vehicle_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = {
            findNavController().popBackStack()
        }
        txtPlate           = view.findViewById(R.id.txtDetailVehiclePlate)
        txtSubtitle        = view.findViewById(R.id.txtDetailVehicleSubtitle)
        txtInfoPlate       = view.findViewById(R.id.txtInfoPlate)
        txtInfoName        = view.findViewById(R.id.txtInfoName)
        txtInfoCompany     = view.findViewById(R.id.txtInfoCompany)
        txtInfoType        = view.findViewById(R.id.txtInfoType)
        txtInfoCapacity    = view.findViewById(R.id.txtInfoCapacity)
        layoutPackingLists = view.findViewById(R.id.layoutPackingLists)
        btnAddPackingList  = view.findViewById(R.id.btnAddPackingList)
        btnEditVehicle     = view.findViewById(R.id.btnEditVehicle)
        btnHardDelete      = view.findViewById(R.id.btnHardDeleteVehicle)
        progress           = view.findViewById(R.id.progressDetail)

        btnEditVehicle.setOnClickListener {
            val bundle = Bundle().apply { putLong("vehicleId", vehicleId) }
            findNavController().navigate(R.id.action_vehicleDetailFragment_to_editVehicleFragment, bundle)
        }
        btnHardDelete.setOnClickListener { confirmHardDelete() }
        btnAddPackingList.setOnClickListener { showAddPackingListDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val v = ServiceLocator.smsVehicleDao.getById(vehicleId)
            if (v == null) {
                findNavController().navigateUp()
                return@launch
            }
            bindVehicle(v)
            progress.visibility = View.GONE
            loadPackingLists(v)
        }
    }

    private fun bindVehicle(v: SmsVehicleEntity) {
        txtPlate.text    = v.license_plate.ifBlank { getString(R.string.vehicles_label_fallback, v.vehicle_id) }
        val subtitle = listOfNotNull(v.vehicle_name, v.vehicle_type, v.company).joinToString(" · ")
        txtSubtitle.text = subtitle.ifBlank { "—" }
        txtInfoPlate.text    = v.license_plate.ifBlank { "—" }
        txtInfoName.text     = v.vehicle_name?.ifBlank { "—" } ?: "—"
        txtInfoCompany.text  = v.company?.ifBlank { "—" } ?: "—"
        txtInfoType.text     = v.vehicle_type?.ifBlank { "—" } ?: "—"
        txtInfoCapacity.text = v.capacity_weight_kg?.let { "$it kg" } ?: "—"
    }

    private fun loadPackingLists(v: SmsVehicleEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            layoutPackingLists.removeAllViews()
            layoutPackingLists.addView(makeStatusText(getString(R.string.vehicle_detail_loading_pls)))
            try {
                val pls = ServiceLocator.smsPackingListDao.getByVehiclePlate(v.license_plate)
                layoutPackingLists.removeAllViews()
                if (pls.isEmpty()) {
                    layoutPackingLists.addView(makeStatusText(getString(R.string.vehicle_detail_no_pls)))
                } else {
                    pls.forEach { addPlRow(it) }
                }
            } catch (e: Exception) {
                layoutPackingLists.removeAllViews()
                layoutPackingLists.addView(makeStatusText(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun makeStatusText(msg: String): TextView = TextView(requireContext()).apply {
        text = msg
        textSize = 13f
        setTextColor(requireContext().getColor(R.color.on_surface_variant))
    }

    private fun addPlRow(pl: SmsPackingListEntity) {
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
            text = "• ${pl.packing_list_name.ifBlank { "PL ${pl.packing_list_id}" }}" +
                (pl.packing_date.take(10).takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.vehicle_detail_btn_remove_pl)
            isAllCaps = false
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.error))
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { confirmRemovePackingList(pl) }
        }
        row.addView(txt)
        row.addView(btn)
        layoutPackingLists.addView(row)
    }

    private fun showAddPackingListDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val unassigned = ServiceLocator.smsPackingListDao.getByProject(projectId)
                .filter { it.vehicle_id == null && it.vehicle_plate.isNullOrBlank() }
            if (unassigned.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.vehicle_detail_no_unassigned_pls), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = unassigned.map { it.packing_list_name.ifBlank { "PL ${it.packing_list_id}" } }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.vehicle_detail_add_pl_title))
                .setItems(labels) { _, which -> doAddPackingList(unassigned[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun doAddPackingList(pl: SmsPackingListEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val v = ServiceLocator.smsVehicleDao.getById(vehicleId) ?: return@launch
                val updated = pl.copy(vehicle_id = vehicleId, vehicle_plate = v.license_plate, synced = false)
                ServiceLocator.smsPackingListDao.insertAll(listOf(updated))

                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val position = pl.position_id?.let { ServiceLocator.smsPositionDao.getById(it) }
                        val resp = ServiceLocator.apiClient.getService().updatePackingList(
                            projectCode,
                            UpdatePackingListRequest(
                                packingListId   = pl.packing_list_id,
                                packingListName = pl.packing_list_name,
                                vehicle         = v.license_plate,
                                position        = position?.run { name.ifBlank { code } },
                                positionId      = pl.position_id,
                                packingDate     = pl.packing_date,
                                notes           = pl.notes,
                                createdBy       = pl.created_by,
                                updatedBy       = "APP",
                                projectCode     = projectCode
                                // rowVersion intentionally omitted — see EditPackingListFragment.saveEdits.
                            )
                        )
                        if (!resp.isSuccessful) {
                            if (resp.code() == 409) {
                                // Another device already put this vehicle on a different active PL —
                                // undo the optimistic local assignment instead of leaving it diverged
                                // from the server.
                                ServiceLocator.smsPackingListDao.insertAll(listOf(pl))
                                val msg = parsePackingListConflictMessage(409, resp.errorBody()?.string())
                                Toast.makeText(requireContext(), getString(R.string.pl_vehicle_conflict, msg), Toast.LENGTH_LONG).show()
                                loadData()
                                return@launch
                            }
                            Log.w(TAG, "updatePackingList API failed: HTTP ${resp.code()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updatePackingList API failed", e)
                }

                Toast.makeText(requireContext(), getString(R.string.vehicle_detail_pl_added), Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRemovePackingList(pl: SmsPackingListEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.vehicle_detail_remove_pl_title))
            .setMessage(getString(R.string.vehicle_detail_remove_pl_msg, pl.packing_list_name))
            .setPositiveButton(android.R.string.ok) { _, _ -> doRemovePackingList(pl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doRemovePackingList(pl: SmsPackingListEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = pl.copy(vehicle_id = null, vehicle_plate = null, synced = false)
                ServiceLocator.smsPackingListDao.insertAll(listOf(updated))

                try {
                    val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val position = pl.position_id?.let { ServiceLocator.smsPositionDao.getById(it) }
                        ServiceLocator.apiClient.getService().updatePackingList(
                            projectCode,
                            UpdatePackingListRequest(
                                packingListId   = pl.packing_list_id,
                                packingListName = pl.packing_list_name,
                                vehicle         = null,
                                position        = position?.run { name.ifBlank { code } },
                                positionId      = pl.position_id,
                                packingDate     = pl.packing_date,
                                notes           = pl.notes,
                                createdBy       = pl.created_by,
                                updatedBy       = "APP",
                                projectCode     = projectCode
                                // rowVersion intentionally omitted — see EditPackingListFragment.saveEdits.
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updatePackingList (remove) API failed", e)
                }

                Toast.makeText(requireContext(), getString(R.string.vehicle_detail_pl_removed), Toast.LENGTH_SHORT).show()
                loadData()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmHardDelete() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.vehicle_detail_confirm_hard_delete_title))
            .setMessage(getString(R.string.vehicle_detail_confirm_hard_delete_msg))
            .setPositiveButton(android.R.string.ok) { _, _ -> doHardDelete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doHardDelete() {
        btnHardDelete.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val vehicle = ServiceLocator.smsVehicleDao.getById(vehicleId)
                val isSynced = vehicle?.synced == true
                val projectId = vehicle?.project_id ?: (ServiceLocator.configRepo.getInt("selected_project_id") ?: 6)

                locallyDeletedVehicleIds.add(vehicleId)

                val pls = ServiceLocator.smsPackingListDao.getByVehicle(vehicleId)
                pls.forEach { pl ->
                    ServiceLocator.smsPackingListDao.insertAll(listOf(pl.copy(vehicle_id = null, vehicle_plate = null, synced = false)))
                }
                ServiceLocator.smsVehicleDao.deleteById(vehicleId)

                // Queue the server hard-delete for synced vehicles so it survives offline + restart.
                if (isSynced) {
                    ServiceLocator.outboxService.enqueue(
                        OutboxService.Entity.VEHICLE, OutboxService.Op.HARD_DELETE, vehicleId, projectId
                    )
                }

                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.VEHICULO_ELIMINADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_VEHICULO,
                    vehicleId, vehicle?.license_plate ?: "", projectId = projectId
                )

                Toast.makeText(requireContext(), getString(R.string.vehicle_detail_hard_deleted), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnHardDelete.isEnabled = true
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
