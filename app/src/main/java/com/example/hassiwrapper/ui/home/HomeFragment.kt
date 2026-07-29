package com.example.hassiwrapper.ui.home

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.normalizeDeviceLocation
import com.example.hassiwrapper.parsePackingListEntities
import com.example.hassiwrapper.parseSpoolEntities
import com.example.hassiwrapper.parseVehicleEntities
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val isGuest get() = ProfileManager.currentUserRole() == ProfileManager.UserRole.GUEST

    // onViewCreated + the immediately-following onResume both call loadGuestHeader() on
    // initial fragment creation — without this guard, two concurrent coroutines each
    // clear-then-repopulate guestSubPositionBreakdown, and their addView calls interleave
    // into duplicate rows (observed on device 2026-07-20).
    private var guestHeaderJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layoutRes = if (isGuest) R.layout.fragment_home_guest else R.layout.fragment_home
        return inflater.inflate(layoutRes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isGuest) {
            setupGuestView(view)
            loadGuestHeader()
            return
        }

        val isDevOrAdmin = ProfileManager.currentUserRole().let {
            it == ProfileManager.UserRole.ADMIN || it == ProfileManager.UserRole.DEV
        }
        view.findViewById<View>(R.id.techOrbView)?.visibility =
            if (isDevOrAdmin) View.VISIBLE else View.GONE

        val syncBtn = view.findViewById<View>(R.id.btnGoSync)
        syncBtn.translationY = 60f
        syncBtn.alpha = 0f
        syncBtn.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(350)
            .setStartDelay(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
        syncBtn.setOnClickListener {
            findNavController().navigate(R.id.syncFragment)
        }
        view.findViewById<View>(R.id.cardQuickMap).setOnClickListener {
            findNavController().navigate(R.id.spoolMapFragment)
        }
        view.findViewById<View>(R.id.cardSpools).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_inventarioFragment, bundleOf("initialTab" to 0))
        }
        view.findViewById<View>(R.id.cardVehicles).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_inventarioFragment, bundleOf("initialTab" to 2))
        }
        view.findViewById<View>(R.id.cardCriticalIncidents).setOnClickListener {
            findNavController().navigate(R.id.action_global_incidentsFragment)
        }
        view.findViewById<View>(R.id.cardPackingLists).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_inventarioFragment, bundleOf("initialTab" to 1))
        }
        // Creación manual de PL deshabilitada: los PLs ahora se crean solo desde los envíos.
        view.findViewById<View>(R.id.cardQuickNewPl).visibility = View.GONE
        view.findViewById<View>(R.id.cardQuickSend).setOnClickListener {
            findNavController().navigate(R.id.action_global_sendPackingListFragment)
        }
        view.findViewById<View>(R.id.cardQuickTransfers).setOnClickListener {
            findNavController().navigate(R.id.action_global_receivePackingListFragment)
        }
        view.findViewById<View>(R.id.cardQuickNewIncident).setOnClickListener {
            findNavController().navigate(R.id.newIncidentFragment)
        }
        view.findViewById<View>(R.id.cardQuickNewSpool).setOnClickListener {
            findNavController().navigate(R.id.newSpoolFragment)
        }
        view.findViewById<View>(R.id.cardQuickNewVehicle).setOnClickListener {
            findNavController().navigate(R.id.newVehicleFragment)
        }
        // DEBUG BUTTON — remove btnChangeProject from layout + this block before production
        view.findViewById<View>(R.id.btnChangeProject).setOnClickListener {
            showProjectPickerDialog()
        }

        loadStats()
    }

    override fun onResume() {
        super.onResume()
        if (isGuest) loadGuestHeader() else loadStats()
    }

    private fun setupGuestView(view: View) {
        view.findViewById<View>(R.id.cardGuestSend).setOnClickListener {
            findNavController().navigate(R.id.action_global_sendPackingListFragment)
        }
        view.findViewById<View>(R.id.cardGuestReceive).setOnClickListener {
            findNavController().navigate(R.id.action_global_receivePackingListFragment)
        }
        view.findViewById<View>(R.id.cardGuestNewIncident).setOnClickListener {
            findNavController().navigate(R.id.newIncidentFragment)
        }
        view.findViewById<View>(R.id.btnGuestSync).setOnClickListener {
            findNavController().navigate(R.id.syncFragment)
        }
        view.findViewById<View>(R.id.btnGuestMap).setOnClickListener {
            findNavController().navigate(R.id.spoolMapFragment)
        }
        view.findViewById<View>(R.id.btnGuestInventario).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_inventarioFragment, bundleOf("initialTab" to 0))
        }
        view.findViewById<View>(R.id.btnGuestSettings).setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }
        view.findViewById<View>(R.id.btnGuestQr).setOnClickListener {
            findNavController().navigate(R.id.qrScannerFragment)
        }
    }

    private fun loadGuestHeader() {
        guestHeaderJob?.cancel()
        guestHeaderJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val terminalName     = ServiceLocator.configRepo.get("device_code")     ?: "—"
                val terminalLocation = ServiceLocator.configRepo.get("device_location") ?: ""
                view?.let { v ->
                    v.findViewById<TextView>(R.id.txtGuestUserName).text = terminalName
                    val txtLoc = v.findViewById<TextView>(R.id.txtGuestLocation)
                    if (terminalLocation.isNotBlank()) {
                        txtLoc.text = terminalLocationLabel(terminalLocation)
                        txtLoc.visibility = View.VISIBLE
                    } else {
                        txtLoc.visibility = View.GONE
                    }
                }
                loadGuestZoneStats(terminalLocation)
            } catch (e: Exception) {
                Log.e("HomeDebug", "loadGuestHeader failed", e)
            }
        }
    }

    /** Guest home zone KPIs: confirmed (scanned by a terminal AND synced) vs pending (scanned
     *  locally, not yet uploaded) spool counts for this terminal's configured zone, plus a per-sub-position
     *  (GCP) breakdown when the project defines any for that zone. Counts move themselves:
     *  a spool packed into a PL headed to another zone resolves there instead (see
     *  SPOOL_RESOLVED_POSITION), so no separate "deduct on transfer" step is needed.
     *
     *  A terminal further pinned to one sub-position (device_sub_position_id — e.g. a JAFURAH
     *  "Laydown GCP 5" terminal, where GCP5/6/9 are siblings under the same LAYDOWN zone) narrows
     *  the top KPI to that sub-position alone and skips the breakdown list, which would otherwise
     *  reveal the sibling GCP zones' counts. Only applies when the pin actually has siblings —
     *  a lone/degenerate sub-position (e.g. an auto-seeded "WORKSHOP/WORKSHOP") has nothing to
     *  hide, and narrowing there would drop spools stamped before the terminal was pinned. */
    private suspend fun loadGuestZoneStats(rawLocation: String) {
        val view = view ?: return
        val row = view.findViewById<View>(R.id.guestZoneStatsRow)
        val breakdown = view.findViewById<android.widget.LinearLayout>(R.id.guestSubPositionBreakdown)
        val location = normalizeDeviceLocation(rawLocation)
        if (location == null) {
            row.visibility = View.GONE
            breakdown.visibility = View.GONE
            return
        }

        val projectId = getSelectedProjectId()
        val positionId = ServiceLocator.smsPositionDao.getByCode(location)?.position_id
        val subPositions = positionId?.let { ServiceLocator.smsSubPositionDao.getByPosition(projectId, it) }.orEmpty()
        val pinnedSubPositionId = ServiceLocator.configRepo.get("device_sub_position_id")?.toLongOrNull()

        // Only narrow to the pinned sub-position when it actually has siblings under the same
        // zone (e.g. JAFURAH GCP5/6/9) — that's the only case where showing the full-zone count
        // would leak sibling GCPs' totals. A lone/degenerate sub-position (e.g. a plain WORKSHOP
        // zone auto-seeded with a single "WORKSHOP/WORKSHOP" sub-position) has nothing to hide.
        // Narrowing there would also under-count: PositionHelper only started stamping the
        // terminal's pin onto scanned spools recently, so rows scanned before that (or by a
        // terminal with no pin) still carry a null sub_position_id and would vanish from the
        // KPI. Fall through to the full-zone counts instead.
        if (pinnedSubPositionId != null && subPositions.size > 1) {
            val pinned = ServiceLocator.smsSpoolDao.countByProjectZoneAndSubPosition(projectId, location)
                .firstOrNull { it.subPositionId == pinnedSubPositionId }
            view.findViewById<TextView>(R.id.txtGuestZoneConfirmedCount).text = (pinned?.confirmed ?: 0).toString()
            view.findViewById<TextView>(R.id.txtGuestZonePendingCount).text = (pinned?.pending ?: 0).toString()
            row.visibility = View.VISIBLE
            breakdown.visibility = View.GONE
            return
        }

        val confirmed = ServiceLocator.smsSpoolDao.countConfirmedByProjectAndZone(projectId, location)
        val pending = ServiceLocator.smsSpoolDao.countPendingByProjectAndZone(projectId, location)
        view.findViewById<TextView>(R.id.txtGuestZoneConfirmedCount).text = confirmed.toString()
        view.findViewById<TextView>(R.id.txtGuestZonePendingCount).text = pending.toString()
        row.visibility = View.VISIBLE

        breakdown.removeAllViews()
        if (subPositions.size <= 1) {
            breakdown.visibility = View.GONE
            return
        }
        val labelById = subPositions.associateBy({ it.sub_position_id }, { it.full_path.ifBlank { it.name } })
        val counts = ServiceLocator.smsSpoolDao.countByProjectZoneAndSubPosition(projectId, location)
            .filter { it.subPositionId != null && (it.confirmed > 0 || it.pending > 0) }
        if (counts.isEmpty()) {
            breakdown.visibility = View.GONE
            return
        }
        counts.forEach { c ->
            val subLabel = labelById[c.subPositionId] ?: return@forEach
            val label = "$location/$subLabel"
            val tv = TextView(requireContext())
            tv.text = getString(R.string.home_guest_subpos_row, label, c.confirmed, c.pending)
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_surface))
            tv.textSize = 11f
            tv.gravity = android.view.Gravity.CENTER
            tv.setPadding(2, 2, 2, 2)
            breakdown.addView(tv)
        }
        breakdown.visibility = View.VISIBLE
    }

    /** LAYDOWN/SITE plus pinned GCP (device_sub_position_id), e.g. "LAYDOWN / GCP 5" — same
     *  format as SettingsFragment's device location row, shown here too since a bare zone name
     *  doesn't tell a JAFURAH-style GCP5/6/9 terminal which sub-position it's pinned to. */
    private suspend fun terminalLocationLabel(locationCode: String): String {
        val pinnedSubId = ServiceLocator.configRepo.get("device_sub_position_id")?.toLongOrNull()
        val pinnedLabel = pinnedSubId?.let { ServiceLocator.smsSubPositionDao.getById(it) }
            ?.let { sp -> sp.full_path.ifBlank { sp.name } }
            // full_path already starts with the zone name (e.g. "LAYDOWN/GCP5") — drop any
            // segment matching locationCode so it isn't prefixed twice ("LAYDOWN / LAYDOWN/GCP5").
            ?.split("/")?.map { it.trim() }?.filterNot { it.equals(locationCode, ignoreCase = true) }
            ?.joinToString("/")?.ifBlank { null }
        return if (pinnedLabel != null) "$locationCode / $pinnedLabel" else locationCode
    }

    private suspend fun getSelectedProjectId(): Int =
        ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

    private fun showProjectPickerDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projects = ServiceLocator.projectDao.getAll()
            if (projects.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No hay proyectos en la BD. Haz sync primero.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = projects.map { "[${it.project_code}] ${it.project_name}" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.home_dialog_select_project))
                .setItems(labels) { _, idx ->
                    val selected = projects[idx]
                    viewLifecycleOwner.lifecycleScope.launch {
                        ServiceLocator.configRepo.setInt("selected_project_id", selected.project_id)
                        syncProjectData(selected.project_id, selected.project_code)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun syncProjectData(projectId: Int, projectCode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            view?.findViewById<TextView>(R.id.txtProjectLabel)?.text =
                getString(R.string.home_syncing_project)
            try {
                val service = ServiceLocator.apiClient.getService()

                // Unfiltered: this repopulates the shared local sms_spool mirror for the newly
                // selected project, which scan-recognition/search/packing-list pickers depend on.
                // KPI tiles filter to scanned=true locally instead (see loadStats/countScannedByProject).
                val spoolResp = service.getSpools(projectCode)
                if (spoolResp.isSuccessful) {
                    val entities = parseSpoolEntities(spoolResp.body()?.string().orEmpty(), projectId)
                    val activeSpools = entities.filter { it.is_active }
                    Log.d("HomeDebug", "syncProject: ${activeSpools.size} active spools for $projectCode (${entities.size - activeSpools.size} inactive skipped)")
                    ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                    if (activeSpools.isNotEmpty()) ServiceLocator.smsSpoolDao.insertAll(activeSpools)
                } else {
                    Log.w("HomeDebug", "getSpools HTTP ${spoolResp.code()}")
                }
                ServiceLocator.smsSpoolDao.deleteInactive()

                val plResp = service.getPackingLists(projectCode)
                if (plResp.isSuccessful) {
                    val entities = parsePackingListEntities(plResp.body()?.string().orEmpty(), projectId)
                    val activePLs = entities.filter { it.is_active }
                    Log.d("HomeDebug", "syncProject: ${activePLs.size} active packing lists for $projectCode (${entities.size - activePLs.size} inactive skipped)")
                    ServiceLocator.smsPackingListDao.deleteSyncedByProject(projectId)
                    if (activePLs.isNotEmpty()) ServiceLocator.smsPackingListDao.insertAll(activePLs)
                } else {
                    Log.w("HomeDebug", "getPackingLists HTTP ${plResp.code()}")
                }
                ServiceLocator.smsPackingListDao.deleteInactive()

                val vehicleResp = service.getVehicles(projectCode)
                if (vehicleResp.isSuccessful) {
                    val entities = parseVehicleEntities(vehicleResp.body()?.string().orEmpty(), projectId)
                    Log.d("HomeDebug", "syncProject: ${entities.size} vehicles for $projectCode")
                    if (entities.isNotEmpty()) {
                        ServiceLocator.smsVehicleDao.deleteByProject(projectId)
                        ServiceLocator.smsVehicleDao.insertAll(entities)
                    }
                } else {
                    Log.w("HomeDebug", "getVehicles HTTP ${vehicleResp.code()}")
                }

                Toast.makeText(
                    requireContext(),
                    getString(R.string.home_project_synced, projectCode),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e("HomeDebug", "syncProjectData failed", e)
                Toast.makeText(requireContext(), "Error sync: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                loadStats()
            }
        }
    }

    private fun animateCountUp(tv: TextView, target: Int) {
        if (target == 0) { tv.text = "0"; return }
        ValueAnimator.ofInt(0, target).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = (animatedValue as Int).toString() }
            start()
        }
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lastSync = ServiceLocator.configRepo.get("last_sync")

                val projectId = getSelectedProjectId()
                val isPrivileged = ProfileManager.currentUserRole() != ProfileManager.UserRole.GUEST
                val filterZone = if (isPrivileged) ServiceLocator.configRepo.get("device_location")?.takeIf { it.isNotBlank() } else null
                val spoolCount = if (filterZone != null)
                    ServiceLocator.smsSpoolDao.countScannedByProjectAndZone(projectId, filterZone)
                else
                    ServiceLocator.smsSpoolDao.countScannedByProject(projectId)
                val packingListCount = ServiceLocator.smsPackingListDao.countByProject(projectId)
                val vehicleCount = ServiceLocator.smsVehicleDao.countByProject(projectId)
                val criticalIncidentCount = ServiceLocator.smsIncidentService.getCriticalCount(projectId)

                val project = ServiceLocator.projectDao.getById(projectId)
                val terminalName     = ServiceLocator.configRepo.get("device_code")     ?: "—"
                val terminalLocation = ServiceLocator.configRepo.get("device_location") ?: ""

                Log.d("HomeDebug", "=== KPIs ===")
                Log.d("HomeDebug", "project id=$projectId → code=${project?.project_code} name=${project?.project_name}")
                Log.d("HomeDebug", "spools=$spoolCount packingLists=$packingListCount vehicles=$vehicleCount")
                Log.d("HomeDebug", "lastSync=$lastSync")

                view?.let { v ->
                    v.findViewById<TextView>(R.id.txtTerminalName).text = terminalName
                    val txtLoc = v.findViewById<TextView>(R.id.txtTerminalLocation)
                    if (terminalLocation.isNotBlank()) {
                        txtLoc.text = terminalLocationLabel(terminalLocation)
                        txtLoc.visibility = View.VISIBLE
                    } else {
                        txtLoc.visibility = View.GONE
                    }
                    animateCountUp(v.findViewById(R.id.txtSpoolCount), spoolCount)
                    animateCountUp(v.findViewById(R.id.txtPackingListCount), packingListCount)
                    animateCountUp(v.findViewById(R.id.txtVehicleCount), vehicleCount)
                    animateCountUp(v.findViewById(R.id.txtCriticalIncidentCount), criticalIncidentCount)
                    v.findViewById<TextView>(R.id.txtLastSync).text = if (lastSync != null) {
                        getString(R.string.home_last_sync_format, lastSync.take(19).replace('T', ' '))
                    } else getString(R.string.home_last_sync_none)
                    v.findViewById<TextView>(R.id.txtProjectLabel).text =
                        getString(R.string.home_project_label, project?.project_code ?: projectId.toString())
                }
            } catch (e: Exception) {
                Log.e("HomeDebug", "Exception in loadStats()", e)
            }
        }
    }

}
