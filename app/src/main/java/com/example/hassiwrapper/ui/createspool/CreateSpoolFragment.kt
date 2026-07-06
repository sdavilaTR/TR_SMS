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
import androidx.core.widget.doOnTextChanged
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
import com.google.android.material.textfield.TextInputEditText
import androidx.transition.TransitionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateSpoolFragment : Fragment() {

    private enum class Filter(val positionCode: String?) {
        ALL(null), WORKSHOP("WORKSHOP"), LAYDOWN("LAYDOWN"), SITE("SITE")
    }

    private companion object {
        const val PAGE_SIZE = 300
        const val SEARCH_DEBOUNCE_MS = 250L
    }

    private enum class ViewMode { LIST, CHART }

    // List/chart data comes straight from SQL — with 100k+ spools per project the old
    // load-everything-into-memory approach took over a minute on the device.
    private val items     = mutableListOf<SmsSpoolEntity>()
    private var comboCounts: List<com.example.hassiwrapper.data.db.dao.SpoolComboCount> = emptyList()
    private var chartCounts: Map<String?, Int> = emptyMap()
    private var chartTotal = 0
    private var subCountsRaw: Map<String, Map<Long?, Int>> = emptyMap()
    private var listJob: Job? = null
    private var filter    = Filter.ALL
    private var viewMode  = ViewMode.CHART
    private var searchQuery = ""
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
        rv.setHasFixedSize(true)
        rv.adapter = adapter

        view.findViewById<TextInputEditText>(R.id.editSearch).doOnTextChanged { text, _, _, _ ->
            val query = text?.toString()?.trim().orEmpty()
            if (query == searchQuery) return@doOnTextChanged
            searchQuery = query
            refreshViewState()
            reloadList(debounceMs = SEARCH_DEBOUNCE_MS)
        }

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
            refreshViewState()
            reloadList()
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

    /** Reloads the visible page from SQL (position + sub-position + search filters).
     *  Only the first [PAGE_SIZE] rows are materialized; the count query gives the real total. */
    private fun reloadList(debounceMs: Long = 0L) {
        listJob?.cancel()
        listJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val code = filter.positionCode
            val subId = selectedSubPositionId
            // Without a search term the total is derivable from the cheap combo aggregates;
            // the SQL count (full scan with LIKEs) is only paid while searching.
            val total = if (searchQuery.isEmpty()) {
                comboCounts.asSequence()
                    .filter { code == null || resolveComboPosition(it) == code }
                    .filter { subId == null || it.subId == subId }
                    .sumOf { it.cnt }
            } else {
                ServiceLocator.smsSpoolDao.countFiltered(projectId, code, subId, searchQuery)
            }
            val page = ServiceLocator.smsSpoolDao.getFilteredPage(projectId, code, subId, searchQuery, PAGE_SIZE)
            items.clear()
            items += page
            adapter.notifyDataSetChanged()
            txtCount.text = if (total > page.size)
                getString(R.string.spools_list_count_capped, total, page.size)
            else
                getString(R.string.spools_list_count, total)
            txtEmpty.visibility = if (page.isEmpty() && txtError.visibility != View.VISIBLE) View.VISIBLE else View.GONE
            refreshViewState()
        }
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
                reloadList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSubPos.visibility = View.VISIBLE
    }

    private fun refreshViewState() {
        btnToggleView.visibility = if (filter == Filter.ALL && searchQuery.isEmpty()) View.VISIBLE else View.GONE
        // Searching always shows the list — the chart aggregates zones, not individual matches.
        val showChart = filter == Filter.ALL && viewMode == ViewMode.CHART && chartTotal > 0 && searchQuery.isEmpty()
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
        fun countFor(code: String) = chartCounts[code] ?: 0

        val workshop = countFor("WORKSHOP")
        val laydown = countFor("LAYDOWN")
        val site = countFor("SITE")
        val unassigned = chartTotal - workshop - laydown - site
        val total = chartTotal

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

    /** Groups the spools of a Laydown/Site bucket by their per-spool sub_position_id
     *  (pre-aggregated in SQL, see refreshChartData). Null sub_position_id, or a
     *  sub_position that belongs to a different zone, falls into a "(sin sub)" bucket
     *  so cross-zone assignments don't bleed into this chart. */
    private fun subStatsFor(code: String, colorRes: Int): List<ZoneStat> {
        val zonePositionId = positionCodes.entries.firstOrNull { it.value.equals(code, ignoreCase = true) }?.key
        return (subCountsRaw[code] ?: emptyMap()).entries
            .groupBy { (subId, _) ->
                subId?.takeIf { zonePositionId != null && subPositionPositionIds[it] == zonePositionId }
            }
            .map { (validId, entries) ->
                val label = validId?.let { subPositionLabels[it] } ?: getString(R.string.spools_chart_subpos_none)
                ZoneStat(label, entries.sumOf { it.value }, colorRes)
            }
            .sortedByDescending { it.count }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Loads chart aggregates from one GROUP BY over the raw location combos (covered by
     *  index, few result rows) and resolves each combo to a position code in Kotlin. */
    private suspend fun refreshChartData(projectId: Int) {
        refreshSubPositionLabels(projectId)
        comboCounts = ServiceLocator.smsSpoolDao.countByLocationCombo(projectId)
        chartTotal = comboCounts.sumOf { it.cnt }
        val byCode = mutableMapOf<String?, Int>()
        val bySub = mutableMapOf<String, MutableMap<Long?, Int>>()
        comboCounts.forEach { combo ->
            val code = resolveComboPosition(combo)
            byCode.merge(code, combo.cnt, Int::plus)
            if (code == "LAYDOWN" || code == "SITE") {
                bySub.getOrPut(code) { mutableMapOf() }.merge(combo.subId, combo.cnt, Int::plus)
            }
        }
        chartCounts = byCode
        subCountsRaw = bySub
    }

    /** Kotlin twin of the DAO's SPOOL_RESOLVED_POSITION SQL expression — same 3-level
     *  fallback (PL position → zone exact/prefix match → position_id), uppercased.
     *  Keep both in sync. */
    private fun resolveComboPosition(combo: com.example.hassiwrapper.data.db.dao.SpoolComboCount): String? {
        val fromPl = combo.plId?.let { plPositions[it] }
        if (!fromPl.isNullOrBlank()) return fromPl.uppercase()
        val zone = combo.zone
        if (!zone.isNullOrBlank()) {
            val zUp = zone.uppercase()
            positionCodes.values.firstOrNull { code ->
                zUp == code.uppercase() || zUp.startsWith("${code.uppercase()}/")
            }?.let { return it.uppercase() }
        }
        return combo.positionId?.let { positionCodes[it]?.uppercase() }
    }

    /** Resolves the display label for every sub-position of the project (small table). */
    private suspend fun refreshSubPositionLabels(projectId: Int) {
        val entities = ServiceLocator.smsSubPositionDao.getByProject(projectId)
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

    private fun loadSpools(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

                refreshPackingListMap(projectId)
                if (!forceRefresh && ServiceLocator.smsSpoolDao.countActiveByProject(projectId) > 0) {
                    refreshAllViews(projectId)
                    return@launch
                }

                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                if (projectCode.isNullOrBlank()) {
                    showError(getString(R.string.spools_list_error_prefix, "Sin proyecto id=$projectId en BD"))
                    return@launch
                }

                val response = ServiceLocator.apiClient.getService().getSpools(projectCode)
                if (response.isSuccessful) {
                    // Body read (IO) + JSON parse (CPU) off the main thread — with thousands
                    // of spools this froze the UI for seconds.
                    // Snapshot: the live set is mutated on the main thread while we parse.
                    val locallyDeleted = SpoolDetailBottomSheet.locallyDeletedSpoolIds.toSet()
                    val activeEntities = withContext(Dispatchers.Default) {
                        val raw = response.body()?.string().orEmpty()
                        parseSpoolEntities(raw, projectId)
                            .filter { it.is_active && it.spool_id !in locallyDeleted }
                    }
                    ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                    if (activeEntities.isNotEmpty()) ServiceLocator.smsSpoolDao.insertAll(activeEntities)
                    refreshAllViews(projectId)
                } else {
                    showError(getString(R.string.spools_list_error_http, response.code()))
                    refreshAllViews(projectId)   // whatever is cached locally
                }
            } catch (e: Exception) {
                Log.e("CreateSpoolFragment", "loadSpools failed", e)
                showError(e.message ?: e.javaClass.simpleName)
                try {
                    val fallbackId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                    refreshAllViews(fallbackId)
                } catch (_: Exception) {}
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun refreshAllViews(projectId: Int) {
        refreshChartData(projectId)
        updateSubPositionSpinner()
        reloadList()
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading && chartTotal == 0 && items.isEmpty()) View.VISIBLE else View.GONE
        if (!loading) swipe.isRefreshing = false
    }

    private fun showError(message: String) {
        txtError.visibility = View.VISIBLE
        txtError.text = getString(R.string.spools_list_error_prefix, message)
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
