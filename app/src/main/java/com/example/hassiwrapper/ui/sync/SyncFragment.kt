package com.example.hassiwrapper.ui.sync

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

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ServiceLocator.syncService.fullSync()

            btn.isEnabled = true
            progress.visibility = View.GONE

            if (result.success) {
                status.text = getString(R.string.sync_success, result.logsUploaded, result.workersAdded, result.workersUpdated)
                status.setTextColor(resources.getColor(R.color.granted, null))
            } else {
                status.text = getString(R.string.sync_error, result.error)
                status.setTextColor(resources.getColor(R.color.error, null))
            }

            loadPendingCounts()
            loadLastSync()
        }
    }
}
