package com.example.hassiwrapper.ui.createspool

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
import com.example.hassiwrapper.data.db.entities.SmsSubPositionEntity
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
    private var positionCodes: Map<Int, String> = emptyMap()
    private var subPositionLabels: Map<Long, String> = emptyMap()
    private var subPositionPositionIds: Map<Long, Int> = emptyMap()
    private val expandedZones = mutableSetOf<String>()
    private lateinit var adapter: SpoolAdapter
    private lateinit var spinnerSubPos: Spinner
    private var selectedSubPositionId: Long? = null
    private var subPositionItems: List<SmsSubPositionEntity> = emptyList()

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
    private lateinit var chartScrollView: ScrollView
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
        cardZoneChart  = view.findViewById(R.id.cardZoneChart)
        txtChartTitle  = view.findViewById(R.id.txtChartTitle)
        chartScrollView = view.findViewById(R.id.chartScrollView)
        chartZoneRows  = view.findViewById(R.id.chartZoneRows)
        btnToggleView = view.findViewById(R.id.btnToggleView)
        spinnerSubPos = view.findViewById(R.id.spinnerSubPos)
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
            selectedSubPositionId = null
            subPositionItems = emptyList()
            spinnerSubPos.visibility = View.GONE
            expandSelectedTab(checkedId)
            applyFilter()
            viewLifecycleOwner.lifecycleScope.launch { updateSubPositionSpinner() }
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
        val posFiltered = if (targetCode == null) {
            allItems
        } else {
            allItems.filter { spool ->
                spoolPositionCode(spool)?.equals(targetCode, ignoreCase = true) == true
            }
        }
        val subId = selectedSubPositionId
        items += if (subId == null) posFiltered else posFiltered.filter { it.sub_position_id == subId }
        adapter.notifyDataSetChanged()
        refreshCounts()
        refreshViewState()
    }

    private suspend fun updateSubPositionSpinner() {
        val code = filter.positionCode
        if (code == null) {
            spinnerSubPos.visibility = View.GONE
            return
        }
        val positionId = positionCodes.entries.firstOrNull { it.value.equals(code, ignoreCase = true) }?.key
        if (positionId == null) {
            spinnerSubPos.visibility = View.GONE
            return
        }
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        subPositionItems = ServiceLocator.smsSubPositionDao.getByPosition(projectId, positionId)
        if (subPositionItems.isEmpty()) {
            spinnerSubPos.visibility = View.GONE
            return
        }
        val labels = mutableListOf(getString(R.string.spools_subpos_all))
        subPositionItems.forEach { sp -> labels += sp.full_path.ifBlank { sp.name.ifBlank { sp.code } } }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSubPos.onItemSelectedListener = null
        spinnerSubPos.adapter = spinnerAdapter
        spinnerSubPos.setSelection(0)
        spinnerSubPos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newSubId = if (position == 0) null else subPositionItems[position - 1].sub_position_id
                if (newSubId == selectedSubPositionId) return
                selectedSubPositionId = newSubId
                applyFilter()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSubPos.visibility = View.VISIBLE
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

    // drillCode != null marks a position whose bar expands into per-sub-position sub-rows.
    private data class ZoneStat(val label: String, val count: Int, val colorRes: Int, val drillCode: String? = null)

    private fun updateZoneChart() {
        fun countFor(code: String) = allItems.count { spool ->
            spoolPositionCode(spool)?.equals(code, ignoreCase = true) == true
        }

        val workshop = countFor("WORKSHOP")
        val laydown = countFor("LAYDOWN")
        val site = countFor("SITE")
        val unassigned = allItems.size - workshop - laydown - site
        val total = allItems.size

        val zones = listOf(
            ZoneStat(getString(R.string.spools_filter_workshop), workshop, R.color.chart_zone_workshop),
            ZoneStat(getString(R.string.spools_filter_laydown), laydown, R.color.chart_zone_laydown, "LAYDOWN"),
            ZoneStat(getString(R.string.spools_filter_site), site, R.color.chart_zone_site, "SITE"),
            ZoneStat(getString(R.string.spools_chart_zone_unassigned), unassigned, R.color.chart_zone_unassigned)
        ).sortedByDescending { it.count }
        val max = zones.maxOf { it.count }.coerceAtLeast(1)

        txtChartTitle.text = getString(R.string.spools_chart_title, total)
        chartZoneRows.removeAllViews()
        chartCountViews.clear()
        zones.forEach { zone ->
            addChartRow(zone, max, total, parentCount = null)
            val code = zone.drillCode
            if (code != null && zone.count > 0 && expandedZones.contains(code)) {
                val children = subStatsFor(code, zone.colorRes)
                val childMax = children.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                children.forEach { child -> addChartRow(child, childMax, total, parentCount = zone.count) }
            }
        }
        capChartScrollHeight()
    }

    /** Renders one chart row. Parent rows are % of [total]; child sub-rows are % of [parentCount]
     *  (i.e. share of that Laydown/Site bucket). Drillable parents toggle expansion on tap. */
    private fun addChartRow(zone: ZoneStat, max: Int, total: Int, parentCount: Int?) {
        val isChild = parentCount != null
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_zone_chart_row, chartZoneRows, false)
        val color = ContextCompat.getColor(requireContext(), zone.colorRes)
        row.findViewById<View>(R.id.dotZone).backgroundTintList = ColorStateList.valueOf(color)
        val drillable = zone.drillCode != null && zone.count > 0
        val expanded = drillable && expandedZones.contains(zone.drillCode)
        row.findViewById<TextView>(R.id.txtZoneLabel).text = when {
            expanded  -> "▾ ${zone.label}"
            drillable -> "▸ ${zone.label}"
            isChild   -> "↳ ${zone.label}"
            else      -> zone.label
        }
        val denom = if (isChild) parentCount!! else total
        val pct = if (denom > 0) (zone.count * 100 / denom) else 0
        val txtCount = row.findViewById<TextView>(R.id.txtZoneCount)
        txtCount.text = if (showZonePct) "$pct%" else zone.count.toString()
        txtCount.setOnClickListener { toggleZonePct() }
        chartCountViews += Triple(txtCount, zone.count, pct)
        val barFill = row.findViewById<View>(R.id.barFill)
        val barSpacer = row.findViewById<View>(R.id.barSpacer)
        barFill.backgroundTintList = ColorStateList.valueOf(color)
        (barFill.layoutParams as LinearLayout.LayoutParams).weight = zone.count.toFloat()
        (barSpacer.layoutParams as LinearLayout.LayoutParams).weight = (max - zone.count).toFloat()
        if (isChild) {
            row.alpha = 0.85f
            (row.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = dp(20)
        }
        if (drillable) {
            row.isClickable = true
            row.setOnClickListener {
                if (!expandedZones.add(zone.drillCode!!)) expandedZones.remove(zone.drillCode)
                TransitionManager.beginDelayedTransition(chartZoneRows)
                updateZoneChart()
            }
        }
        chartZoneRows.addView(row)
    }

    private fun capChartScrollHeight() {
        val maxPx = dp(280)
        chartScrollView.post {
            val lp = chartScrollView.layoutParams
            lp.height = if (chartZoneRows.height > maxPx) maxPx else ViewGroup.LayoutParams.WRAP_CONTENT
            chartScrollView.requestLayout()
        }
    }

    /** Groups the spools of a Laydown/Site bucket by their per-spool sub_position_id.
     *  Null sub_position_id, or a sub_position that belongs to a different zone, falls
     *  into a "(sin sub)" bucket so cross-zone assignments don't bleed into this chart. */
    private fun subStatsFor(code: String, colorRes: Int): List<ZoneStat> {
        val zonePositionId = positionCodes.entries.firstOrNull { it.value.equals(code, ignoreCase = true) }?.key
        val inZone = allItems.filter { s ->
            spoolPositionCode(s)?.equals(code, ignoreCase = true) == true
        }
        return inZone.groupingBy { it.sub_position_id }.eachCount().entries
            .map { (subId, c) ->
                val validId = subId?.takeIf { zonePositionId != null && subPositionPositionIds[it] == zonePositionId }
                val label = validId?.let { subPositionLabels[it] } ?: getString(R.string.spools_chart_subpos_none)
                ZoneStat(label, c, colorRes)
            }
            .sortedByDescending { it.count }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Resolves the display label for every distinct sub_position_id currently loaded. */
    private suspend fun refreshSubPositionLabels() {
        val ids = allItems.mapNotNull { it.sub_position_id }.toSet()
        if (ids.isEmpty()) {
            subPositionLabels = emptyMap()
            subPositionPositionIds = emptyMap()
            return
        }
        val dao = ServiceLocator.smsSubPositionDao
        val entities = ids.mapNotNull { id -> dao.getById(id) }
        subPositionLabels      = entities.associate { sp -> sp.sub_position_id to sp.full_path.ifBlank { sp.name.ifBlank { sp.code } } }
        subPositionPositionIds = entities.associate { sp -> sp.sub_position_id to sp.position_id }
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
        positionCodes = ServiceLocator.smsPositionDao.getAll().associate { it.position_id to it.code }
    }

    /** Resolves a position code for a spool with 3 levels of fallback:
     *  1. PL's own position String (set on receive/send).
     *  2. Spool zone prefix match (handles "LAYDOWN/SECTOR-1" → "LAYDOWN", or exact "LAYDOWN").
     *  3. Spool position_id → code lookup (newly created spools with no PL). */
    private fun spoolPositionCode(spool: com.example.hassiwrapper.data.db.entities.SmsSpoolEntity): String? {
        val fromPl = spool.packing_list_id?.let { plPositions[it] }
        if (!fromPl.isNullOrBlank()) return fromPl
        val zone = spool.zone
        if (!zone.isNullOrBlank()) {
            val zUp = zone.uppercase()
            positionCodes.values.firstOrNull { code -> zUp == code || zUp.startsWith("$code/") }
                ?.let { return it }
        }
        return spool.position_id?.let { positionCodes[it] }
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
                    refreshSubPositionLabels()
                    updateSubPositionSpinner()
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
                    refreshSubPositionLabels()
                    updateSubPositionSpinner()
                    applyFilter()
                } else {
                    val err = response.errorBody()?.string().orEmpty()
                    Log.e("SpoolsDebug", "HTTP ${response.code()}: $err")
                    showError(getString(R.string.spools_list_error_http, response.code()))
                    if (allItems.isEmpty()) {
                        allItems += ServiceLocator.smsSpoolDao.getByProject(projectId)
                        refreshSubPositionLabels()
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
