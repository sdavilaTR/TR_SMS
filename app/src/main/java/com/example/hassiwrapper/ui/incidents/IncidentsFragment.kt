package com.example.hassiwrapper.ui.incidents

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.ui.common.SwipeTabContainer
import com.example.hassiwrapper.data.db.entities.SmsIncidentEntity
import com.example.hassiwrapper.services.SmsIncidentService
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class IncidentsFragment : Fragment() {

    private val allIncidents = mutableListOf<SmsIncidentEntity>()
    private val items = mutableListOf<SmsIncidentEntity>()
    private lateinit var adapter: IncidentAdapter
    private var severityFilter: String? = null
    private var statusFilter = "OPEN"

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView
    private lateinit var fabNew: ExtendedFloatingActionButton
    private lateinit var chipGroupSeverity: ChipGroup
    private lateinit var tabLayoutStatus: TabLayout

    private val severityFilterByChipId = mapOf(
        R.id.chipSeverityCritical to "CRITICAL",
        R.id.chipSeverityHigh to "HIGH",
        R.id.chipSeverityMedium to "MEDIUM",
        R.id.chipSeverityLow to "LOW"
    )

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_incidents, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv         = view.findViewById(R.id.rvIncidents)
        swipe      = view.findViewById(R.id.swipeRefresh)
        progress   = view.findViewById(R.id.progressIncidents)
        txtEmpty   = view.findViewById(R.id.txtIncidentsEmpty)
        txtCount   = view.findViewById(R.id.txtIncidentsCount)
        fabNew     = view.findViewById(R.id.fabNewIncident)
        chipGroupSeverity = view.findViewById(R.id.chipGroupSeverityFilter)
        tabLayoutStatus = view.findViewById(R.id.tabLayoutStatus)

        tabLayoutStatus.addTab(tabLayoutStatus.newTab().setText(getString(R.string.incidents_tab_open)))
        tabLayoutStatus.addTab(tabLayoutStatus.newTab().setText(getString(R.string.incidents_tab_closed)))

        adapter = IncidentAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        fabNew.setOnClickListener {
            findNavController().navigate(R.id.action_incidentsFragment_to_newIncidentFragment)
        }

        tabLayoutStatus.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                statusFilter = if (tab.position == 0) "OPEN" else "CLOSED"
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        chipGroupSeverity.setOnCheckedStateChangeListener { _, checkedIds ->
            severityFilter = checkedIds.firstOrNull()?.let { severityFilterByChipId[it] }
            applyFilter()
        }

        view.findViewById<SwipeTabContainer>(R.id.swipeTabContainer).apply {
            onSwipeLeft = {
                val next = tabLayoutStatus.selectedTabPosition + 1
                if (next < tabLayoutStatus.tabCount) tabLayoutStatus.getTabAt(next)?.select()
            }
            onSwipeRight = {
                val prev = tabLayoutStatus.selectedTabPosition - 1
                if (prev >= 0) tabLayoutStatus.getTabAt(prev)?.select()
            }
        }

        swipe.setOnRefreshListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = if (allIncidents.isEmpty()) View.VISIBLE else View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val incidents = ServiceLocator.smsIncidentService.getIncidents(projectId)
                allIncidents.clear()
                allIncidents += incidents
                applyFilter()
            } finally {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
            }
        }
    }

    private fun applyFilter() {
        items.clear()
        items += allIncidents
            .filter { it.status == statusFilter }
            .let { if (severityFilter == null) it else it.filter { inc -> inc.severity == severityFilter } }
        adapter.notifyDataSetChanged()
        txtCount.text = getString(R.string.incidents_title) + " (${items.size})"
        txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun severityLabel(code: String): String =
        severityLabels[code]?.let { getString(it) } ?: code

    private fun locationLabel(code: String): String =
        locationLabels[code]?.let { getString(it) } ?: code

    private inner class IncidentAdapter : RecyclerView.Adapter<IncidentAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: CardView = view.findViewById(R.id.cardIncident)
            val spool: TextView = view.findViewById(R.id.txtIncidentSpool)
            val severity: TextView = view.findViewById(R.id.txtIncidentSeverity)
            val description: TextView = view.findViewById(R.id.txtIncidentDescription)
            val meta: TextView = view.findViewById(R.id.txtIncidentMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_incident_card, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.spool.text = if (!item.spool_suffix.isNullOrBlank()) {
                "${item.spool_code}-${item.spool_suffix}"
            } else item.spool_code

            h.severity.text = severityLabel(item.severity)
            h.severity.background.setTint(SmsIncidentService.getSeverityColor(item.severity))

            h.description.text = item.description.ifBlank { getString(R.string.incident_card_no_description) }

            val metaParts = mutableListOf(locationLabel(item.location_type))
            item.device_code?.takeIf { it.isNotBlank() }?.let { metaParts += it }
            item.vehicle_plate?.takeIf { it.isNotBlank() }?.let { metaParts += it }
            metaParts += item.event_date.take(16).replace('T', ' ')
            h.meta.text = metaParts.joinToString("  •  ")

            h.card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
            h.card.setOnClickListener {
                findNavController().navigate(
                    R.id.action_incidentsFragment_to_incidentDetailFragment,
                    androidx.core.os.bundleOf("incidentId" to item.id)
                )
            }
        }
    }
}
