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

        viewLifecycleOwner.lifecycleScope.launch {
            if (!ServiceLocator.authRepo.isAuthenticated()) {
                status.text = getString(R.string.sync_auth_required)
                status.setTextColor(resources.getColor(R.color.warning, null))
                return@launch
            }

            btn.isEnabled = false
            progress.visibility = View.VISIBLE
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

            if (result.success) {
                status.text = getString(R.string.sync_success, result.logsUploaded, result.workersAdded, result.workersUpdated)
                status.setTextColor(resources.getColor(R.color.granted, null))
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
                apiReachable = true
            } else {
                status.text = getString(R.string.sync_error, result.error ?: "Error desconocido")
                status.setTextColor(resources.getColor(R.color.error, null))
                checkConnectivity()
            }

            loadKpis()
            loadLastSync()
        }
    }
}
