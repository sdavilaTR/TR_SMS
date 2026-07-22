package com.example.hassiwrapper.ui.incidents

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsIncidentEntity
import com.example.hassiwrapper.services.SmsIncidentService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class IncidentDetailFragment : Fragment() {

    private val severityLabels = mapOf(
        "LOW" to R.string.incident_severity_low,
        "MEDIUM" to R.string.incident_severity_medium,
        "HIGH" to R.string.incident_severity_high,
        "CRITICAL" to R.string.incident_severity_critical
    )

    private val locationLabels = mapOf(
        "LAYDOWN" to R.string.incident_location_laydown,
        "SITE" to R.string.incident_location_site,
        "WORKSHOP" to R.string.incident_location_workshop
    )

    private val statusLabels = mapOf(
        "OPEN" to R.string.incident_status_open,
        "CLOSED" to R.string.incident_status_closed,
        SmsIncidentService.STATUS_REPRINT_APPROVED to R.string.incident_status_reprint_approved
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_incident_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = {
            findNavController().popBackStack()
        }

        val incidentId = arguments?.getLong("incidentId") ?: 0L
        val imgPhoto = view.findViewById<ImageView>(R.id.imgIncidentDetailPhoto)
        val txtSpool = view.findViewById<TextView>(R.id.txtIncidentDetailSpool)
        val txtSeverity = view.findViewById<TextView>(R.id.txtIncidentDetailSeverity)
        val txtDescription = view.findViewById<TextView>(R.id.txtIncidentDetailDescription)
        val rows = view.findViewById<LinearLayout>(R.id.layoutIncidentDetailFields)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnCloseIncident)
        val btnApproveReprint = view.findViewById<MaterialButton>(R.id.btnApproveReprint)

        fun reload() {
            viewLifecycleOwner.lifecycleScope.launch {
                val incident = ServiceLocator.smsIncidentDao.getById(incidentId) ?: return@launch
                val subPositionPath = incident.sub_position_id?.let { ServiceLocator.smsSubPositionDao.getById(it)?.full_path }
                bind(incident, subPositionPath, imgPhoto, txtSpool, txtSeverity, txtDescription, rows, btnClose, btnApproveReprint)
            }
        }

        btnApproveReprint.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.incident_approve_reprint_confirm_title)
                .setMessage(R.string.incident_approve_reprint_confirm_msg)
                .setPositiveButton(R.string.incident_approve_reprint_confirm_yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val incident = ServiceLocator.smsIncidentDao.getById(incidentId)
                        ServiceLocator.smsIncidentService.approveReprint(incidentId)
                        if (incident != null) {
                            ServiceLocator.auditLogService.log(
                                com.example.hassiwrapper.services.AuditLogService.INCIDENCIA_REIMPRESION_APROBADA,
                                com.example.hassiwrapper.services.AuditLogService.ENTITY_INCIDENCIA,
                                incident.id, incident.spool_code, projectId = incident.project_id
                            )
                        }
                        Toast.makeText(requireContext(), R.string.incident_reprint_approved_ok, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
                .setNegativeButton(R.string.incident_approve_reprint_confirm_no, null)
                .show()
        }

        btnClose.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.incident_close_confirm_title)
                .setMessage(R.string.incident_close_confirm_msg)
                .setPositiveButton(R.string.incident_close_confirm_yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val incident = ServiceLocator.smsIncidentDao.getById(incidentId)
                        ServiceLocator.smsIncidentService.closeIncident(incidentId)
                        if (incident != null) {
                            ServiceLocator.auditLogService.log(
                                com.example.hassiwrapper.services.AuditLogService.INCIDENCIA_CERRADA,
                                com.example.hassiwrapper.services.AuditLogService.ENTITY_INCIDENCIA,
                                incident.id, incident.spool_code, projectId = incident.project_id
                            )
                        }
                        Toast.makeText(requireContext(), R.string.incident_closed_ok, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
                .setNegativeButton(R.string.incident_close_confirm_no, null)
                .show()
        }

        reload()
    }

    private fun bind(
        item: SmsIncidentEntity,
        subPositionPath: String?,
        imgPhoto: ImageView,
        txtSpool: TextView,
        txtSeverity: TextView,
        txtDescription: TextView,
        rows: LinearLayout,
        btnClose: MaterialButton,
        btnApproveReprint: MaterialButton
    ) {
        item.photo_path?.let { path ->
            val file = File(path)
            if (file.exists()) {
                imgPhoto.setImageURI(Uri.fromFile(file))
                imgPhoto.visibility = View.VISIBLE
            }
        }

        txtSpool.text = if (!item.spool_suffix.isNullOrBlank()) {
            "${item.spool_code}-${item.spool_suffix}"
        } else item.spool_code

        txtSeverity.text = severityLabels[item.severity]?.let(::getString) ?: item.severity
        txtSeverity.background.setTint(SmsIncidentService.getSeverityColor(item.severity))

        txtDescription.text = item.description.ifBlank { getString(R.string.incident_card_no_description) }

        rows.removeAllViews()
        addRow(rows, R.string.incident_label_location, locationLabels[item.location_type]?.let(::getString) ?: item.location_type)
        item.location_detail?.takeIf { it.isNotBlank() }?.let {
            addRow(rows, R.string.incident_label_location_detail, it)
        }
        item.vehicle_plate?.takeIf { it.isNotBlank() }?.let {
            addRow(rows, R.string.incident_label_vehicle_plate, it)
        }
        addRow(rows, R.string.incident_label_position, item.position_code ?: getString(R.string.incident_value_unknown))
        subPositionPath?.takeIf { it.isNotBlank() }?.let {
            addRow(rows, R.string.incident_label_subposition, it)
        }
        if (item.incident_type == SmsIncidentService.TYPE_REVISION_MISMATCH) {
            item.scanned_revision?.let { addRow(rows, R.string.incident_label_scanned_revision, it) }
            item.stored_revision?.let { addRow(rows, R.string.incident_label_stored_revision, it) }
        }
        addRow(rows, R.string.incident_label_author, item.author_name?.takeIf { it.isNotBlank() } ?: getString(R.string.incident_value_unknown))
        addRow(rows, R.string.settings_device_code_label, item.device_code?.takeIf { it.isNotBlank() } ?: getString(R.string.incident_value_unknown))
        addRow(rows, R.string.incident_label_date, item.event_date.take(16).replace('T', ' '))
        addRow(rows, R.string.incident_label_status, statusLabels[item.status]?.let(::getString) ?: item.status)

        if (item.status == "CLOSED") {
            addRow(rows, R.string.incident_label_closed_by, item.closed_by?.takeIf { it.isNotBlank() } ?: getString(R.string.incident_value_unknown))
            item.closed_at?.let {
                addRow(rows, R.string.incident_label_closed_at, it.take(16).replace('T', ' '))
            }
            btnClose.visibility = View.GONE
        } else {
            btnClose.visibility = View.VISIBLE
        }

        val isSupervisor = ProfileManager.currentUserRole() != ProfileManager.UserRole.GUEST
        btnApproveReprint.visibility = if (
            isSupervisor &&
            item.incident_type == SmsIncidentService.TYPE_REVISION_MISMATCH &&
            item.status == SmsIncidentService.STATUS_OPEN
        ) View.VISIBLE else View.GONE
    }

    private fun addRow(parent: LinearLayout, labelRes: Int, value: String) {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_incident_detail_row, parent, false)
        row.findViewById<TextView>(R.id.txtRowLabel).text = getString(labelRes)
        row.findViewById<TextView>(R.id.txtRowValue).text = value
        parent.addView(row)
    }
}
