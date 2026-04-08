package com.example.hassiwrapper.ui.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.ProfileManager
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

            val authenticated = ServiceLocator.authRepo.isAuthenticated()
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
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch
            val counts = ServiceLocator.syncService.getPendingCounts()
            val workerCount = ServiceLocator.personDao.count()
            val vehicleCount = ServiceLocator.vehicleDao.count()

            // Records KPI
            setupPendingKpi(
                v.findViewById(R.id.txtKpiRecords),
                v.findViewById(R.id.txtKpiRecordsLabel),
                counts.logs
            )

            // Photos KPI
            setupPendingKpi(
                v.findViewById(R.id.txtKpiPhotos),
                v.findViewById(R.id.txtKpiPhotosLabel),
                counts.photos
            )

            // Observations KPI
            setupPendingKpi(
                v.findViewById(R.id.txtKpiObservations),
                v.findViewById(R.id.txtKpiObservationsLabel),
                counts.observations
            )

            // Workers KPI
            val txtWorkers = v.findViewById<TextView>(R.id.txtKpiWorkers)
            val txtWorkersLabel = v.findViewById<TextView>(R.id.txtKpiWorkersLabel)
            txtWorkers.text = workerCount.toString()
            txtWorkersLabel.text = if (apiReachable) {
                getString(R.string.sync_kpi_workers_synced)
            } else {
                ""
            }

            // Vehicles KPI
            val txtVehicles = v.findViewById<TextView>(R.id.txtKpiVehicles)
            val txtVehiclesLabel = v.findViewById<TextView>(R.id.txtKpiVehiclesLabel)
            txtVehicles.text = vehicleCount.toString()
            txtVehiclesLabel.text = if (apiReachable) {
                getString(R.string.sync_kpi_vehicles_synced)
            } else {
                ""
            }
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
        val progress = v.findViewById<ProgressBar>(R.id.progressSync)
        val status = v.findViewById<TextView>(R.id.txtSyncStatus)
        val dotApi = v.findViewById<View>(R.id.dotApi)
        val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)
        val cardResult = v.findViewById<View>(R.id.cardSyncResult)

        viewLifecycleOwner.lifecycleScope.launch {
            if (!ServiceLocator.authRepo.isAuthenticated()) {
                status.text = getString(R.string.sync_auth_required)
                status.setTextColor(resources.getColor(R.color.warning, null))
                return@launch
            }

            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            cardResult.visibility = View.GONE
            status.text = getString(R.string.sync_syncing)
            status.setTextColor(resources.getColor(R.color.on_surface_variant, null))
            val result = ServiceLocator.syncService.fullSync { retry ->
                status.text = getString(R.string.sync_retrying, retry.attempt, retry.waitSeconds)
                status.setTextColor(resources.getColor(R.color.warning, null))
                dotApi.setBackgroundResource(R.drawable.dot_grey)
                txtApi.text = getString(R.string.sync_status_checking)
            }

            btn.isEnabled = true
            progress.visibility = View.GONE
            status.text = ""

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
        val txtLogs = v.findViewById<TextView>(R.id.txtResultLogs)
        val txtWorkers = v.findViewById<TextView>(R.id.txtResultWorkers)
        val txtVehicles = v.findViewById<TextView>(R.id.txtResultVehicles)
        val txtObservations = v.findViewById<TextView>(R.id.txtResultObservations)
        val txtPhotos = v.findViewById<TextView>(R.id.txtResultPhotos)
        val txtError = v.findViewById<TextView>(R.id.txtResultError)

        card.visibility = View.VISIBLE

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

        // Logs
        if (result.logsUploaded > 0) {
            txtLogs.text = getString(R.string.sync_result_logs, result.logsUploaded)
            txtLogs.visibility = View.VISIBLE
        } else {
            txtLogs.visibility = View.GONE
        }

        // Workers
        if (result.workersAdded > 0 || result.workersUpdated > 0) {
            txtWorkers.text = getString(R.string.sync_result_workers, result.workersAdded, result.workersUpdated)
            txtWorkers.visibility = View.VISIBLE
        } else {
            txtWorkers.visibility = View.GONE
        }

        // Vehicles
        if (result.vehiclesAdded > 0 || result.vehiclesUpdated > 0) {
            txtVehicles.text = getString(R.string.sync_result_vehicles, result.vehiclesAdded, result.vehiclesUpdated)
            txtVehicles.visibility = View.VISIBLE
        } else {
            txtVehicles.visibility = View.GONE
        }

        // Observations
        if (result.observationsUploaded > 0) {
            txtObservations.text = getString(R.string.sync_result_observations, result.observationsUploaded)
            txtObservations.visibility = View.VISIBLE
        } else {
            txtObservations.visibility = View.GONE
        }

        // Photos
        if (result.photosUploaded > 0 || result.photosFailed > 0) {
            val sb = StringBuilder()
            if (result.photosUploaded > 0) {
                sb.append(getString(R.string.sync_result_photos_ok, result.photosUploaded))
            }
            if (result.photosFailed > 0) {
                if (sb.isNotEmpty()) sb.append("  |  ")
                sb.append(getString(R.string.sync_result_photos_fail, result.photosFailed))
                val profile = ProfileManager.currentProfile()
                if (profile == ProfileManager.Profile.DEV || profile == ProfileManager.Profile.ADMIN || profile == ProfileManager.Profile.PRE) {
                    sb.append("\n").append(result.photoErrors.joinToString("\n"))
                }
            }
            txtPhotos.text = sb.toString()
            txtPhotos.setTextColor(ContextCompat.getColor(requireContext(),
                if (result.photosFailed > 0) R.color.warning else R.color.on_surface_variant))
            txtPhotos.visibility = View.VISIBLE
        } else {
            txtPhotos.visibility = View.GONE
        }

        // Error
        if (!result.success && result.error != null) {
            txtError.text = result.error
            txtError.visibility = View.VISIBLE
        } else {
            txtError.visibility = View.GONE
        }
    }
}
