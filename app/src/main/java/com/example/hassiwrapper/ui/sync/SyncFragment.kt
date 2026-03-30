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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SyncFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnFullSync).setOnClickListener {
            performSync()
        }

        loadPendingCounts()
        loadLastSync()
        checkConnectivity()
    }

    override fun onResume() {
        super.onResume()
        // Refresh connectivity status every time the screen becomes visible
        checkConnectivity()
        loadPendingCounts()
    }

    private fun checkConnectivity() {
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch

            val dotNetwork = v.findViewById<View>(R.id.dotNetwork)
            val txtNetwork = v.findViewById<TextView>(R.id.txtNetworkStatus)
            val dotApi = v.findViewById<View>(R.id.dotApi)
            val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)

            // Show "checking" state while we probe
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
                // No point pinging the API if there's no network
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
                return@launch
            }

            val status = ServiceLocator.apiClient.checkConnectivity()
            if (status.apiReachable) {
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
            } else {
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadPendingCounts() {
        viewLifecycleOwner.lifecycleScope.launch {
            val counts = ServiceLocator.syncService.getPendingCounts()
            view?.let { v ->
                v.findViewById<TextView>(R.id.txtPendingLogs).text = getString(R.string.sync_pending_logs, counts.logs)
                v.findViewById<TextView>(R.id.txtPendingIncidents).text = getString(R.string.sync_pending_incidents, counts.incidents)
                v.findViewById<TextView>(R.id.txtPendingSessions).text = getString(R.string.sync_pending_sessions, counts.sessions)
            }
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

        btn.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.sync_syncing)
        status.setTextColor(resources.getColor(R.color.on_surface_variant, null))

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ServiceLocator.syncService.fullSync()

            btn.isEnabled = true
            progress.visibility = View.GONE

            if (result.success) {
                status.text = getString(R.string.sync_success, result.logsUploaded, result.workersAdded, result.workersUpdated)
                status.setTextColor(resources.getColor(R.color.granted, null))
            } else {
                status.text = getString(R.string.sync_error, result.error ?: "Error desconocido")
                status.setTextColor(resources.getColor(R.color.error, null))
            }

            loadPendingCounts()
            loadLastSync()
            // Refresh connectivity indicators after sync attempt
            checkConnectivity()
        }
    }
}
