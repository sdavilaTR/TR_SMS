package com.example.hassiwrapper.ui.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SyncFragment : Fragment() {

    private var apiReachable = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnFullSync).setOnClickListener {
            performSync()
        }

        loadKpis()
        loadLastSync()
        checkConnectivity()
        checkAuthStatus()
    }

    override fun onResume() {
        super.onResume()
        checkConnectivity()
        checkAuthStatus()
        loadKpis()
    }

    private fun checkConnectivity() {
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch

            val dotNetwork = v.findViewById<View>(R.id.dotNetwork)
            val txtNetwork = v.findViewById<TextView>(R.id.txtNetworkStatus)
            val dotApi = v.findViewById<View>(R.id.dotApi)
            val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)

            dotNetwork.setBackgroundResource(R.drawable.dot_grey)
            txtNetwork.text = getString(R.string.sync_status_checking)
            dotApi.setBackgroundResource(R.drawable.dot_grey)
            txtApi.text = getString(R.string.sync_status_checking)

            val networkOnline = isNetworkAvailable()
            if (networkOnline) {
                dotNetwork.setBackgroundResource(R.drawable.dot_green)
                txtNetwork.text = getString(R.string.sync_network_online)
            } else {
                dotNetwork.setBackgroundResource(R.drawable.dot_red)
                txtNetwork.text = getString(R.string.sync_network_offline)
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
                apiReachable = false
                loadKpis()
                return@launch
            }

            val status = ServiceLocator.apiClient.checkConnectivity()
            apiReachable = status.apiReachable
            if (status.apiReachable) {
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
            } else {
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
            }
            loadKpis()
        }
    }

    private fun checkAuthStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch
            val dotAuth = v.findViewById<View>(R.id.dotAuth)
            val txtAuth = v.findViewById<TextView>(R.id.txtAuthStatus)
            val btnSync = v.findViewById<MaterialButton>(R.id.btnFullSync)

            var authenticated = ServiceLocator.authRepo.isAuthenticated()

            // If not authenticated, attempt auto-re-login with stored device code
            if (!authenticated) {
                dotAuth.setBackgroundResource(R.drawable.dot_grey)
                txtAuth.text = getString(R.string.sync_auto_relogin)
                val relogged = ServiceLocator.authRepo.reLoginWithStoredCode(
                    ServiceLocator.apiClient.getService()
                )
                authenticated = relogged
            }

            if (authenticated) {
                dotAuth.setBackgroundResource(R.drawable.dot_green)
                txtAuth.text = getString(R.string.sync_auth_ok)
                btnSync.isEnabled = true
            } else {
                dotAuth.setBackgroundResource(R.drawable.dot_orange)
                txtAuth.text = getString(R.string.sync_auth_none)
                btnSync.isEnabled = false
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadKpis() {
        if (view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

            // Matches HomeFragment's "spool count" KPI (scanned-only) so the two screens
            // don't show two different numbers for what looks like the same metric.
            val spoolCount = ServiceLocator.smsSpoolDao.countScannedByProject(projectId)
            val packingListCount = ServiceLocator.smsPackingListDao.countByProject(projectId)
            val vehicleCount = ServiceLocator.smsVehicleDao.countByProject(projectId)
            val inTransitCount = ServiceLocator.smsSpoolDao.countInTransitByProject(projectId)
            val pendingTotal = ServiceLocator.smsSpoolDao.getUnsynced().size +
                    ServiceLocator.smsPackingListDao.getUnsynced().size +
                    ServiceLocator.smsVehicleLoadingDao.getUnsynced().size

            val synced = if (apiReachable) getString(R.string.sync_kpi_workers_synced) else ""

            // Spools KPI
            val txtSpools = v.findViewById<TextView>(R.id.txtKpiRecords)
            txtSpools.text = spoolCount.toString()
            txtSpools.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            v.findViewById<TextView>(R.id.txtKpiRecordsLabel).text = synced

            // Packing Lists KPI
            val txtPL = v.findViewById<TextView>(R.id.txtKpiPhotos)
            txtPL.text = packingListCount.toString()
            txtPL.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            v.findViewById<TextView>(R.id.txtKpiPhotosLabel).text = synced

            // SMS Vehicles KPI
            val txtVeh = v.findViewById<TextView>(R.id.txtKpiObservations)
            txtVeh.text = vehicleCount.toString()
            txtVeh.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
            v.findViewById<TextView>(R.id.txtKpiObservationsLabel).text = synced

            // En Tránsito KPI
            val txtTransit = v.findViewById<TextView>(R.id.txtKpiWorkers)
            txtTransit.text = inTransitCount.toString()
            txtTransit.setTextColor(ContextCompat.getColor(requireContext(),
                if (inTransitCount > 0) R.color.warning else R.color.on_surface_variant))
            v.findViewById<TextView>(R.id.txtKpiWorkersLabel).text =
                if (inTransitCount > 0) getString(R.string.sync_kpi_in_transit_active) else ""

            // Pendientes Sync KPI
            setupPendingKpi(
                v.findViewById(R.id.txtKpiVehicles),
                v.findViewById(R.id.txtKpiVehiclesLabel),
                pendingTotal
            )
        }
    }

    private fun setupPendingKpi(txtValue: TextView, txtLabel: TextView, pending: Int) {
        if (pending == 0) {
            txtValue.text = "✓"
            txtValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
            txtLabel.text = getString(R.string.sync_kpi_synced)
        } else {
            txtValue.text = pending.toString()
            txtValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning))
            txtLabel.text = getString(R.string.sync_kpi_pending)
        }
    }

    private fun loadLastSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            val lastSync = ServiceLocator.configRepo.get("last_sync")
            view?.findViewById<TextView>(R.id.txtLastSync)?.text = if (lastSync != null) {
                getString(R.string.sync_last_format, lastSync.take(19).replace('T', ' '))
            } else getString(R.string.sync_last_none)
        }
    }

    private fun performSync() {
        val v = view ?: return
        val btn = v.findViewById<MaterialButton>(R.id.btnFullSync)
        val progress = v.findViewById<View>(R.id.progressSync)
        val dotApi = v.findViewById<View>(R.id.dotApi)
        val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)
        val cardResult = v.findViewById<View>(R.id.cardSyncResult)
        val scrollLog = v.findViewById<ScrollView>(R.id.scrollSyncLog)
        val txtLog = v.findViewById<TextView>(R.id.txtSyncLog)

        viewLifecycleOwner.lifecycleScope.launch {
            if (!ServiceLocator.authRepo.isAuthenticated()) {
                scrollLog.visibility = View.VISIBLE
                txtLog.text = getString(R.string.sync_auth_required)
                return@launch
            }

            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            cardResult.visibility = View.GONE
            scrollLog.visibility = View.VISIBLE
            txtLog.text = getString(R.string.sync_log_start)

            val appendLog: (String) -> Unit = { msg ->
                val current = txtLog.text.toString()
                txtLog.text = if (current.isEmpty()) msg else "$current\n$msg"
                scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            }

            val result = ServiceLocator.syncService.fullSync(
                onRetry = { retry ->
                    appendLog(getString(R.string.sync_retrying, retry.attempt, retry.waitSeconds))
                    dotApi.setBackgroundResource(R.drawable.dot_grey)
                    txtApi.text = getString(R.string.sync_status_checking)
                },
                onProgress = appendLog
            )

            if (result.success) {
                appendLog(getString(R.string.sync_step_sms))
                try {
                    (requireActivity() as? com.example.hassiwrapper.MainActivity)?.syncSmsData(appendLog)
                } catch (e: Exception) {
                    Log.e("SyncSMS", "SMS sync failed", e)
                    appendLog(getString(R.string.sync_error, e.message))
                }
            }

            btn.isEnabled = true
            progress.visibility = View.GONE

            showSyncResultCard(v, result)

            if (result.success) {
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
                apiReachable = true
            } else {
                checkConnectivity()
            }

            loadKpis()
            loadLastSync()
        }
    }

    private fun showSyncResultCard(v: View, result: com.example.hassiwrapper.services.SyncService.SyncResult) {
        val card = v.findViewById<View>(R.id.cardSyncResult)
        val txtIcon = v.findViewById<TextView>(R.id.txtResultIcon)
        val txtTitle = v.findViewById<TextView>(R.id.txtResultTitle)
        val txtError = v.findViewById<TextView>(R.id.txtResultError)

        card.visibility = View.VISIBLE
        // Per-category counts (logs/workers/AC-vehicles/observations/photos) belonged to the
        // old Access-Control SyncResult; syncSmsUploads only reports success/error.
        v.findViewById<TextView>(R.id.txtResultLogs).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultWorkers).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultVehicles).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultObservations).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultPhotos).visibility = View.GONE

        if (result.success) {
            txtIcon.text = "✓"
            txtIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
            txtTitle.text = getString(R.string.sync_result_title_ok)
            txtTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
        } else {
            txtIcon.text = "✕"
            txtIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
            txtTitle.text = getString(R.string.sync_result_title_error)
            txtTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
        }

        if (!result.success && result.error != null) {
            txtError.text = result.error
            txtError.visibility = View.VISIBLE
        } else {
            txtError.visibility = View.GONE
        }
    }

}
