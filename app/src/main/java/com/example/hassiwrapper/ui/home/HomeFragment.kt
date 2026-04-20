package com.example.hassiwrapper.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnGoScanner).setOnClickListener {
            findNavController().navigate(R.id.scannerFragment)
        }
        view.findViewById<View>(R.id.btnGoSync).setOnClickListener {
            findNavController().navigate(R.id.syncFragment)
        }

        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val workerCount = ServiceLocator.personDao.countActive()
                val vehicleCount = ServiceLocator.vehicleDao.countActive()
                val todayStr = LocalDate.now().toString() + "T00:00:00Z"
                val scansToday = ServiceLocator.accessLogDao.getTodayCount(todayStr)
                val pending = ServiceLocator.syncService.getPendingCounts()
                val incidents = ServiceLocator.incidentDao.getUnresolvedCount()
                val lastSync = ServiceLocator.configRepo.get("last_sync")

                val terminalName     = ServiceLocator.configRepo.get("device_name")     ?: "—"
                val terminalLocation = ServiceLocator.configRepo.get("device_location") ?: ""

                view?.let { v ->
                    v.findViewById<TextView>(R.id.txtTerminalName).text = terminalName
                    val txtLoc = v.findViewById<TextView>(R.id.txtTerminalLocation)
                    if (terminalLocation.isNotBlank()) {
                        txtLoc.text = terminalLocation
                        txtLoc.visibility = android.view.View.VISIBLE
                    } else {
                        txtLoc.visibility = android.view.View.GONE
                    }
                    v.findViewById<TextView>(R.id.txtWorkerCount).text = workerCount.toString()
                    v.findViewById<TextView>(R.id.txtVehicleCount).text = vehicleCount.toString()
                    v.findViewById<TextView>(R.id.txtScansToday).text = scansToday.toString()
                    v.findViewById<TextView>(R.id.txtPendingCount).text = (pending.logs + pending.incidents + pending.sessions).toString()
                    v.findViewById<TextView>(R.id.txtIncidentCount).text = incidents.toString()
                    v.findViewById<TextView>(R.id.txtLastSync).text = if (lastSync != null) {
                        getString(R.string.home_last_sync_format, lastSync.take(19).replace('T', ' '))
                    } else getString(R.string.home_last_sync_none)
                }
            } catch (_: Exception) { }
        }
    }
}
