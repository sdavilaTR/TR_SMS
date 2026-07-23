package com.example.hassiwrapper.services

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * `spool.revision` reflects the backend's current engineering revision once a sync has
 * landed it (JAFURAH PCA import) — it is NOT necessarily what's physically printed on the
 * spool tag. Compare it against the revision just parsed off the scanned tag so a spool
 * still carrying an old physical revision isn't silently treated as up to date. On mismatch,
 * offer to raise an incidencia so it reaches supervisors (see [SmsIncidentService.createRevisionMismatchIncident]).
 *
 * Shared by MainActivity's global hardware-scan handler and QrScannerFragment's own scan
 * flow — both need their own [Context]/[CoroutineScope] for the dialog, so only the logic
 * (not the call site) is factored out here.
 */
object RevisionMismatchHelper {

    fun warnIfMismatch(
        context: Context,
        scope: CoroutineScope,
        spoolCode: String,
        spoolSuffix: String?,
        scannedRevision: String?,
        storedRevision: String?,
        onMismatch: ((warning: String) -> Unit)? = null
    ) {
        val scanned = scannedRevision?.trim()?.takeIf { it.isNotEmpty() }
        val stored = storedRevision?.trim()?.takeIf { it.isNotEmpty() }
        if (scanned == null || stored == null || scanned.equals(stored, ignoreCase = true)) return
        val warning = context.getString(R.string.qr_scanner_revision_mismatch, scanned, stored)
        onMismatch?.invoke(warning)

        AlertDialog.Builder(context)
            .setTitle(R.string.revision_mismatch_dialog_title)
            .setMessage(warning)
            .setPositiveButton(R.string.revision_mismatch_dialog_create) { _, _ ->
                scope.launch {
                    val incident = ServiceLocator.smsIncidentService.createRevisionMismatchIncident(
                        spoolCode, spoolSuffix, scanned, stored
                    )
                    if (incident != null) {
                        ServiceLocator.auditLogService.log(
                            AuditLogService.INCIDENCIA_CREADA,
                            AuditLogService.ENTITY_INCIDENCIA,
                            incident.id, incident.spool_code, projectId = incident.project_id
                        )
                        Toast.makeText(context, R.string.revision_mismatch_incident_created, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.revision_mismatch_incident_exists, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.revision_mismatch_dialog_dismiss, null)
            .show()
    }
}
