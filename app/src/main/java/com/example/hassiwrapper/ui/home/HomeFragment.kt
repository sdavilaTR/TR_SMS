package com.example.hassiwrapper.ui.home

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.SmsPackingListDto
import com.example.hassiwrapper.network.dto.SmsVehicleDto
import com.example.hassiwrapper.network.dto.SpoolDto
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val isGuest get() = ProfileManager.currentUserRole() == ProfileManager.UserRole.GUEST

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

view.findViewById<View>(R.id.btnGoSync).setOnClickListener {
            findNavController().navigate(R.id.syncFragment)
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
        view.findViewById<View>(R.id.cardQuickNewPl).setOnClickListener {
            findNavController().navigate(R.id.newPackingListFragment)
        }
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
        view.findViewById<View>(R.id.btnGuestSettings).setOnClickListener {
            findNavController().navigate(R.id.settingsFragment)
        }
        view.findViewById<View>(R.id.btnGuestQr).setOnClickListener {
            findNavController().navigate(R.id.qrScannerFragment)
        }
    }

    private fun loadGuestHeader() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val terminalName     = ServiceLocator.configRepo.get("device_code")     ?: "—"
                val terminalLocation = ServiceLocator.configRepo.get("device_location") ?: ""
                view?.let { v ->
                    v.findViewById<TextView>(R.id.txtGuestUserName).text = terminalName
                    val txtLoc = v.findViewById<TextView>(R.id.txtGuestLocation)
                    if (terminalLocation.isNotBlank()) {
                        txtLoc.text = terminalLocation
                        txtLoc.visibility = View.VISIBLE
                    } else {
                        txtLoc.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeDebug", "loadGuestHeader failed", e)
            }
        }
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

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lastSync = ServiceLocator.configRepo.get("last_sync")

                val projectId = getSelectedProjectId()
                val isPrivileged = ProfileManager.currentUserRole() != ProfileManager.UserRole.GUEST
                val filterZone = if (isPrivileged) ServiceLocator.configRepo.get("device_location")?.takeIf { it.isNotBlank() } else null
                val spoolCount = if (filterZone != null)
                    ServiceLocator.smsSpoolDao.countActiveByProjectAndZone(projectId, filterZone)
                else
                    ServiceLocator.smsSpoolDao.countActiveByProject(projectId)
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
                        txtLoc.text = terminalLocation
                        txtLoc.visibility = View.VISIBLE
                    } else {
                        txtLoc.visibility = View.GONE
                    }
                    v.findViewById<TextView>(R.id.txtVehicleCount).text = vehicleCount.toString()
                    v.findViewById<TextView>(R.id.txtCriticalIncidentCount).text = criticalIncidentCount.toString()
                    v.findViewById<TextView>(R.id.txtSpoolCount).text = spoolCount.toString()
                    v.findViewById<TextView>(R.id.txtPackingListCount).text = packingListCount.toString()
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

    private fun parseSpoolEntities(raw: String, defaultProjectId: Int): List<SmsSpoolEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "spools").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapIndexedNotNull { idx, element ->
                if (!element.isJsonObject) return@mapIndexedNotNull null
                try {
                    val dto = gson.fromJson(element, SpoolDto::class.java)
                    val entity = dto.toEntity()
                    if (entity.spool_id == 0L) return@mapIndexedNotNull null
                    val finalId = if (dto.spoolId?.toDoubleOrNull() == null && !dto.spoolId.isNullOrEmpty()) {
                        val key = "${dto.spoolId}-${dto.spoolSuffix.orEmpty()}-$idx"
                        val crc = java.util.zip.CRC32(); crc.update(key.toByteArray())
                        crc.value.toLong().takeIf { it != 0L } ?: (idx + 1L)
                    } else entity.spool_id
                    entity.copy(spool_id = finalId, project_id = defaultProjectId)
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parsePackingListEntities(raw: String, defaultProjectId: Int): List<SmsPackingListEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "packingLists", "packing_lists").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsPackingListDto::class.java)
                    val entity = dto.toEntity(defaultProjectId)  // toEntity already forces projectId
                    if (entity.packing_list_id == 0L) null else entity
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parseVehicleEntities(raw: String, defaultProjectId: Int): List<SmsVehicleEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "vehicles").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsVehicleDto::class.java)
                    val entity = dto.toEntity(defaultProjectId)
                    if (entity.vehicle_id == 0L) null else entity
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }
}
