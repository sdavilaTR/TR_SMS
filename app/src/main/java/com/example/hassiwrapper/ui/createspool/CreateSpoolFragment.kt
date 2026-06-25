package com.example.hassiwrapper.ui.createspool

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.parseSpoolEntities
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.transition.TransitionManager
import kotlinx.coroutines.launch

class CreateSpoolFragment : Fragment() {

    private enum class Filter(val positionCode: String?) {
        ALL(null), WORKSHOP("WORKSHOP"), LAYDOWN("LAYDOWN"), SITE("SITE")
    }

    private enum class ViewMode { LIST, CHART }

    private val allItems  = mutableListOf<SmsSpoolEntity>()
    private val items     = mutableListOf<SmsSpoolEntity>()
    private var filter    = Filter.ALL
    private var viewMode  = ViewMode.CHART
    private var showZonePct = false
    private val chartCountViews = mutableListOf<Triple<TextView, Int, Int>>()
    private var plPositions: Map<Long, String?> = emptyMap()
    private lateinit var adapter: SpoolAdapter

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtError: TextView
    private lateinit var txtCount: TextView
    private lateinit var toggleFilter: MaterialButtonToggleGroup
    private lateinit var fabNewSpool: FloatingActionButton
    private lateinit var cardZoneChart: View
    private lateinit var txtChartTitle: TextView
    private lateinit var chartZoneRows: LinearLayout
    private lateinit var filterButtons: List<MaterialButton>
    private lateinit var btnToggleView: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_create_spool, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv           = view.findViewById(R.id.rvSpools)
        swipe        = view.findViewById(R.id.swipeRefresh)
        progress     = view.findViewById(R.id.progress)
        txtEmpty     = view.findViewById(R.id.txtEmpty)
        txtError     = view.findViewById(R.id.txtError)
        txtCount     = view.findViewById(R.id.txtCount)
        toggleFilter = view.findViewById(R.id.toggleFilter)
        fabNewSpool  = view.findViewById(R.id.fabNewSpool)
        cardZoneChart = view.findViewById(R.id.cardZoneChart)
        txtChartTitle = view.findViewById(R.id.txtChartTitle)
        chartZoneRows = view.findViewById(R.id.chartZoneRows)
        btnToggleView = view.findViewById(R.id.btnToggleView)
        filterButtons = listOf(
            view.findViewById(R.id.btnFilterAll),
            view.findViewById(R.id.btnFilterWorkshop),
            view.findViewById(R.id.btnFilterLaydown),
            view.findViewById(R.id.btnFilterSite)
        )
        fabNewSpool.setOnClickListener {
            findNavController().navigate(R.id.action_global_newSpoolFragment)
        }

        adapter = SpoolAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        toggleFilter.check(R.id.btnFilterAll)
        toggleFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.btnFilterWorkshop -> Filter.WORKSHOP
                R.id.btnFilterLaydown  -> Filter.LAYDOWN
                R.id.btnFilterSite     -> Filter.SITE
                else                   -> Filter.ALL
            }
            expandSelectedTab(checkedId)
            applyFilter()
        }

        btnToggleView.setOnClickListener {
            viewMode = if (viewMode == ViewMode.CHART) ViewMode.LIST else ViewMode.CHART
            refreshViewState()
        }

        swipe.setOnRefreshListener { loadSpools(forceRefresh = true) }
        loadSpools(forceRefresh = false)
    }

    private fun expandSelectedTab(selectedId: Int) {
        TransitionManager.beginDelayedTransition(toggleFilter)
        filterButtons.forEach { btn ->
            (btn.layoutParams as LinearLayout.LayoutParams).weight = if (btn.id == selectedId) 1.6f else 1f
            btn.requestLayout()
        }
    }

    private fun applyFilter() {
        items.clear()
        val targetCode = filter.positionCode
        items += if (targetCode == null) {
            allItems
        } else {
            allItems.filter { spool ->
                val plId = spool.packing_list_id ?: return@filter false
                plPositions[plId]?.equals(targetCode, ignoreCase = true) == true
            }
        }
        adapter.notifyDataSetChanged()
        refreshCounts()
        refreshViewState()
    }

    private fun refreshViewState() {
        btnToggleView.visibility = if (filter == Filter.ALL) View.VISIBLE else View.GONE
        val showChart = filter == Filter.ALL && viewMode == ViewMode.CHART && allItems.isNotEmpty()
        if (viewMode == ViewMode.CHART) {
            btnToggleView.text = getString(R.string.spools_view_action_list)
            btnToggleView.setIconResource(R.drawable.ic_view_list)
        } else {
            btnToggleView.text = getString(R.string.spools_view_action_chart)
            btnToggleView.setIconResource(R.drawable.ic_view_chart)
        }
        if (showChart) updateZoneChart()
        cardZoneChart.visibility = if (showChart) View.VISIBLE else View.GONE
        swipe.visibility = if (showChart) View.GONE else View.VISIBLE
    }

    private data class ZoneStat(val label: String, val count: Int, val colorRes: Int)

    private fun updateZoneChart() {
        fun countFor(code: String) = allItems.count { spool ->
            val plId = spool.packing_list_id ?: return@count false
            plPositions[plId]?.equals(code, ignoreCase = true) == true
        }

        val workshop = countFor("WORKSHOP")
        val laydown = countFor("LAYDOWN")
        val site = countFor("SITE")
        val unassigned = allItems.size - workshop - laydown - site
        val total = allItems.size

        val zones = listOf(
            ZoneStat(getString(R.string.spools_filter_workshop), workshop, R.color.chart_zone_workshop),
            ZoneStat(getString(R.string.spools_filter_laydown), laydown, R.color.chart_zone_laydown),
            ZoneStat(getString(R.string.spools_filter_site), site, R.color.chart_zone_site),
            ZoneStat(getString(R.string.spools_chart_zone_unassigned), unassigned, R.color.chart_zone_unassigned)
        ).sortedByDescending { it.count }
        val max = zones.maxOf { it.count }.coerceAtLeast(1)

        txtChartTitle.text = getString(R.string.spools_chart_title, total)
        chartZoneRows.removeAllViews()
        chartCountViews.clear()
        zones.forEach { zone ->
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_zone_chart_row, chartZoneRows, false)
            val color = ContextCompat.getColor(requireContext(), zone.colorRes)
            row.findViewById<View>(R.id.dotZone).backgroundTintList = ColorStateList.valueOf(color)
            row.findViewById<TextView>(R.id.txtZoneLabel).text = zone.label
            val pct = if (total > 0) (zone.count * 100 / total) else 0
            val txtCount = row.findViewById<TextView>(R.id.txtZoneCount)
            txtCount.text = if (showZonePct) "$pct%" else zone.count.toString()
            txtCount.setOnClickListener { toggleZonePct() }
            chartCountViews += Triple(txtCount, zone.count, pct)
            val barFill = row.findViewById<View>(R.id.barFill)
            val barSpacer = row.findViewById<View>(R.id.barSpacer)
            barFill.backgroundTintList = ColorStateList.valueOf(color)
            (barFill.layoutParams as LinearLayout.LayoutParams).weight = zone.count.toFloat()
            (barSpacer.layoutParams as LinearLayout.LayoutParams).weight = (max - zone.count).toFloat()
            chartZoneRows.addView(row)
        }
    }

    private fun toggleZonePct() {
        showZonePct = !showZonePct
        chartCountViews.forEach { (txtCount, count, pct) ->
            txtCount.text = if (showZonePct) "$pct%" else count.toString()
        }
    }

    private suspend fun refreshPackingListMap(projectId: Int) {
        val pls = ServiceLocator.smsPackingListDao.getByProject(projectId)
        adapter.packingLists = pls.associate { it.packing_list_id to it.packing_list_name }
        plPositions = pls.associate { it.packing_list_id to it.position }
    }

    private fun loadSpools(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

                val totalInDb      = ServiceLocator.smsSpoolDao.countAll()
                val projectIds     = ServiceLocator.smsSpoolDao.distinctProjectIds()
                val countForProj   = ServiceLocator.smsSpoolDao.countByProject(projectId)
                val countActive    = ServiceLocator.smsSpoolDao.countActiveByProject(projectId)
                val allInDb        = ServiceLocator.smsSpoolDao.getByProjectIgnoreActive(projectId)
                Log.d("SpoolsDebug", "projectId=$projectId | totalInDb=$totalInDb | projectIds=$projectIds | countForProj=$countForProj | countActive=$countActive | forceRefresh=$forceRefresh")
                Log.d("SpoolsDebug", "getByProjectIgnoreActive($projectId) = ${allInDb.size} items")
                allInDb.forEach { s -> Log.d("SpoolsDebug", "  spool_id=${s.spool_id} code=${s.spool_code} pid=${s.project_id} active=${s.is_active} pl=${s.packing_list_id} position_id=${s.position_id}") }
                txtCount.text = "DBG pid=$projectId total=$totalInDb pids=$projectIds proj=$countForProj active=$countActive ignoreActive=${allInDb.size}"

                refreshPackingListMap(projectId)
                val cached = ServiceLocator.smsSpoolDao.getByProject(projectId)
                if (cached.isNotEmpty() && !forceRefresh) {
                    Log.d("SpoolsDebug", "Using cache: ${cached.size} spools")
                    allItems.clear()
                    allItems += cached
                    applyFilter()
                    showLoading(false)
                    return@launch
                }

                val project = ServiceLocator.projectDao.getById(projectId)
                Log.d("SpoolsDebug", "projectDao.getById($projectId) = $project")
                val projectCode = project?.project_code
                if (projectCode.isNullOrBlank()) {
                    val allProjects = ServiceLocator.projectDao.getAll()
                    Log.e("SpoolsDebug", "No project for id=$projectId. All projects in DB: $allProjects")
                    showError(getString(R.string.spools_list_error_prefix, "Sin proyecto id=$projectId en BD. Proyectos: ${allProjects.map { it.project_id }}"))
                    return@launch
                }

                Log.d("SpoolsDebug", "Fetching from API: getSpools($projectCode)")
                val response = ServiceLocator.apiClient.getService().getSpools(projectCode)
                Log.d("SpoolsDebug", "API response: code=${response.code()} ok=${response.isSuccessful}")
                if (response.isSuccessful) {
                    val raw = response.body()?.string().orEmpty()
                    Log.d("SpoolsJSON", "Raw (${raw.length} chars): ${raw.take(500)}")
                    val entities = parseSpoolEntities(raw, projectId)
                    Log.d("SpoolsDebug", "Parsed ${entities.size} entities from API")
                    entities.forEach { e -> Log.d("SpoolsDebug", "  entity spool_id=${e.spool_id} code=${e.spool_code} suffix=${e.spool_suffix} active=${e.is_active} pl=${e.packing_list_id}") }
                    val locallyDeleted = SpoolDetailBottomSheet.locallyDeletedSpoolIds
                    val activeEntities = entities.filter { it.is_active && it.spool_id !in locallyDeleted }
                    if (activeEntities.isNotEmpty()) {
                        ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                        ServiceLocator.smsSpoolDao.insertAll(activeEntities)
                        Log.d("SpoolsDebug", "Inserted ${activeEntities.size} spools (${entities.size - activeEntities.size} inactive/deleted skipped)")
                    } else {
                        ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                        Log.d("SpoolsDebug", "No active spools to insert — cleared synced, kept unsynced")
                    }
                    allItems.clear()
                    allItems += ServiceLocator.smsSpoolDao.getByProject(projectId)
                    Log.d("SpoolsDebug", "After insert, getByProject($projectId) = ${allItems.size}")
                    applyFilter()
                } else {
                    val err = response.errorBody()?.string().orEmpty()
                    Log.e("SpoolsDebug", "HTTP ${response.code()}: $err")
                    showError(getString(R.string.spools_list_error_http, response.code()))
                    if (allItems.isEmpty()) {
                        allItems += ServiceLocator.smsSpoolDao.getByProject(projectId)
                        applyFilter()
                    }
                }
            } catch (e: Exception) {
                Log.e("SpoolsDebug", "Exception in loadSpools", e)
                showError(e.message ?: e.javaClass.simpleName)
                if (allItems.isEmpty()) {
                    try {
                        val fallbackId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                        allItems += ServiceLocator.smsSpoolDao.getByProject(fallbackId)
                        applyFilter()
                    } catch (_: Exception) {}
                }
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading && allItems.isEmpty()) View.VISIBLE else View.GONE
        if (!loading) swipe.isRefreshing = false
    }

    private fun showError(message: String) {
        txtError.visibility = View.VISIBLE
        txtError.text = getString(R.string.spools_list_error_prefix, message)
    }

    private fun refreshCounts() {
        txtCount.text = getString(R.string.spools_list_count, items.size)
        txtEmpty.visibility = if (items.isEmpty() && txtError.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private inner class SpoolAdapter : RecyclerView.Adapter<SpoolAdapter.VH>() {
        var packingLists: Map<Long, String> = emptyMap()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val code:        TextView = view.findViewById(R.id.txtSpoolCode)
            val suffix:      TextView = view.findViewById(R.id.txtSpoolSuffix)
            val line:        TextView = view.findViewById(R.id.txtSpoolLine)
            val details:     TextView = view.findViewById(R.id.txtSpoolDetails)
            val packingList: TextView = view.findViewById(R.id.txtPackingList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spool, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val spool = items[position]
            Log.d("SpoolsUI", "bind[${spool.spool_id}] code=${spool.spool_code} suffix=${spool.spool_suffix}")
            h.code.text = spool.spool_code.ifBlank { spool.spool_id.toString() }
            h.suffix.text = spool.spool_suffix.orEmpty()
            if (!spool.line_code.isNullOrBlank()) {
                h.line.text = spool.line_code
                h.line.visibility = View.VISIBLE
            } else {
                h.line.visibility = View.GONE
            }
            h.details.visibility = View.GONE
            val plId = spool.packing_list_id
            if (plId != null) {
                val plName = packingLists[plId] ?: "PL #$plId"
                h.packingList.text = getString(R.string.spool_item_pl_assigned, plName)
                h.packingList.setTextColor(requireContext().getColor(R.color.on_surface_variant))
            } else {
                h.packingList.text = getString(R.string.spool_item_pl_none)
                h.packingList.setTextColor(requireContext().getColor(R.color.on_surface_variant))
            }
            h.itemView.setOnClickListener { showSpoolDialog(spool) }
        }
    }

    private fun showSpoolDialog(spool: SmsSpoolEntity) {
        SpoolDetailBottomSheet.newInstance(spool.spool_id).also { sheet ->
            sheet.onSpoolUpdated = { loadSpools(forceRefresh = false) }
            sheet.show(childFragmentManager, "spool_detail")
        }
    }
}
