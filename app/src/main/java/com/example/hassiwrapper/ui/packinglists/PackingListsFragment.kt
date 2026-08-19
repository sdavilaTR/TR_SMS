package com.example.hassiwrapper.ui.packinglists

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsPackingListHistoricalEntity
import com.example.hassiwrapper.parsePackingListEntities
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class PackingListsFragment : Fragment() {

    private val items = mutableListOf<SmsPackingListEntity>()
    private lateinit var adapter: PLAdapter

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtError: TextView
    private lateinit var txtCount: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var tabLayout: TabLayout

    /** Tab 0 = Actuales (open/in-flow PLs), tab 1 = Históricos (delivered, see
     *  ReceivePackingListFragment/sms_packing_list_historical). */
    private var showHistorical = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_packing_lists, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv       = view.findViewById(R.id.rvPackingLists)
        swipe    = view.findViewById(R.id.swipeRefresh)
        progress = view.findViewById(R.id.progress)
        txtEmpty = view.findViewById(R.id.txtEmpty)
        txtError = view.findViewById(R.id.txtError)
        txtCount = view.findViewById(R.id.txtCount)
        fab      = view.findViewById(R.id.fabNewPackingList)
        tabLayout = view.findViewById(R.id.tabLayoutPlStatus)
        // Creación manual de PL deshabilitada: los PLs ahora se crean solo desde los envíos.
        fab.visibility = View.GONE

        adapter = PLAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        swipe.setOnRefreshListener { load(forceRefresh = true) }

        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.packing_lists_tab_current)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.packing_lists_tab_historical)))
        // This view (and TabLayout) gets destroyed+recreated on every back-stack return from
        // PackingListDetailFragment while this Fragment instance — and showHistorical — survives.
        // A fresh TabLayout always auto-selects tab 0 without notifying the listener, so without
        // this the tab UI silently resets to "Actuales" while showHistorical (and the data on
        // screen) can stay stuck on "Históricos" — device-reproduced 2026-08-03. Select before
        // registering the listener below so this sync doesn't trigger a redundant load().
        tabLayout.getTabAt(if (showHistorical) 1 else 0)?.select()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showHistorical = tab.position == 1
                load(forceRefresh = false)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        load(forceRefresh = false)
    }

    /** packing_list_id → marked_at, populated only for the Históricos tab (see applyItems). */
    private var historicalDates: Map<Long, String> = emptyMap()

    private suspend fun displayList(projectId: Int): List<SmsPackingListEntity> =
        if (showHistorical) ServiceLocator.smsPackingListDao.getHistoricalByProject(projectId)
        else ServiceLocator.smsPackingListDao.getCurrentByProject(projectId)

    /** Single choke point for populating `items` so historicalDates always tracks what's on
     *  screen — every load() path (cache hit, network refresh, error fallback) routes through here. */
    private suspend fun applyItems(list: List<SmsPackingListEntity>) {
        items.clear()
        items += list
        historicalDates = if (showHistorical)
            ServiceLocator.smsPackingListHistoricalDao.getByIds(list.map { it.packing_list_id })
                .associate { it.packing_list_id to it.marked_at }
        else emptyMap()
        adapter.notifyDataSetChanged()
    }

    /** Self-heal ghost PLs: a vehicle still attached but zero actual spools left on it (bug:
     *  spools all got re-scanned onto another load, e.g. PL 5922, and nothing ever released the
     *  vehicle). New sends already avoid creating these (see SendPackingListFragment's
     *  vacatedPlIds handling), but this sweeps up ones that already exist in the DB/server —
     *  runs on every load() so it also catches ones written before that fix shipped. Reuses
     *  clearVehicleAndDeliver (sets synced=false, so the release uploads) + markHistorical so the
     *  PL survives as a record instead of vanishing, same treatment as a normal delivery.
     */
    private suspend fun reconcileGhostPls(projectId: Int) {
        val current = ServiceLocator.smsPackingListDao.getCurrentByProject(projectId)
        val ghosts = current.filter { pl ->
            pl.vehicle_id != null && ServiceLocator.smsSpoolDao.countByPackingList(pl.packing_list_id) == 0
        }
        if (ghosts.isEmpty()) return
        Log.d("PackingListsDebug", "reconcileGhostPls: releasing ${ghosts.size} vehicle-attached 0-spool PL(s): ${ghosts.map { it.packing_list_id }}")
        val markedAt = java.time.LocalDateTime.now().toString()
        // delivered_at en UTC, igual que en una recepción normal: es la misma columna y la leen
        // terminales en husos distintos. markedAt es local porque su tabla es local y sólo ordena.
        val deliveredAtUtc = java.time.Instant.now().toString()
        ghosts.forEach { pl ->
            ServiceLocator.smsPackingListDao.clearVehicleAndDeliver(pl.packing_list_id, deliveredAtUtc)
            ServiceLocator.smsPackingListHistoricalDao.markHistorical(
                SmsPackingListHistoricalEntity(packing_list_id = pl.packing_list_id, marked_at = markedAt)
            )
        }
    }

    private fun load(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                Log.d("PackingListsDebug", "load() forceRefresh=$forceRefresh projectId=$projectId showHistorical=$showHistorical")

                reconcileGhostPls(projectId)

                // Gate on the project's total local PL count, not this tab's — otherwise an empty
                // Históricos tab (the common case right after this feature ships) would re-hit the
                // network on every open/onResume even though Actuales already has a fresh cache.
                val hasLocalCache = ServiceLocator.smsPackingListDao.countByProject(projectId) > 0
                val cached = displayList(projectId)
                Log.d("PackingListsDebug", "DB cache: ${cached.size} packing lists for project $projectId (hasLocalCache=$hasLocalCache)")

                if (hasLocalCache && !forceRefresh) {
                    applyItems(cached)
                    showLoading(false)
                    refreshCounts()
                    return@launch
                }

                val project = ServiceLocator.projectDao.getById(projectId)
                Log.d("PackingListsDebug", "Project lookup id=$projectId → $project")
                val projectCode = project?.project_code
                if (projectCode.isNullOrBlank()) {
                    val msg = getString(R.string.packing_lists_error_project_code, projectId)
                    Log.e("PackingListsDebug", msg)
                    showError(msg)
                    return@launch
                }

                Log.d("PackingListsDebug", "Calling API getPackingLists(projectCode=$projectCode)")
                val resp = ServiceLocator.apiClient.getService().getPackingLists(projectCode)
                Log.d("PackingListsDebug", "API response: code=${resp.code()} successful=${resp.isSuccessful}")

                if (resp.isSuccessful) {
                    val raw = resp.body()?.string().orEmpty()
                    Log.d("PackingListsJSON", "Raw (first 500): ${raw.take(500)}")
                    val entities = parsePackingListEntities(raw, projectId)
                    Log.d("PackingListsDebug", "Parsed ${entities.size} packing list entities")
                    val (active, inactive) = entities.partition { it.is_active }
                    inactive.forEach { ServiceLocator.smsPackingListDao.deleteById(it.packing_list_id) }
                    if (active.isNotEmpty()) ServiceLocator.smsPackingListDao.insertAll(active)
                    applyItems(displayList(projectId))
                    Log.d("PackingListsDebug", "Displaying ${items.size} packing lists after insert")
                } else {
                    val errBody = resp.errorBody()?.string().orEmpty()
                    Log.e("PackingListsDebug", "HTTP error ${resp.code()}: $errBody")
                    showError(getString(R.string.packing_lists_error_http, resp.code()))
                    if (items.isEmpty()) applyItems(displayList(projectId))
                }
            } catch (e: Exception) {
                Log.e("PackingListsDebug", "Exception in load()", e)
                showError(e.message ?: e.javaClass.simpleName)
                if (items.isEmpty()) {
                    try {
                        val fallbackId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                        applyItems(displayList(fallbackId))
                    } catch (e2: Exception) {
                        Log.e("PackingListsDebug", "Fallback DB read also failed", e2)
                    }
                }
            } finally {
                showLoading(false)
                refreshCounts()
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading && items.isEmpty()) View.VISIBLE else View.GONE
        if (!loading) swipe.isRefreshing = false
    }

    private fun showError(message: String) {
        txtError.visibility = View.VISIBLE
        txtError.text = getString(R.string.packing_lists_error_prefix, message)
    }

    private fun refreshCounts() {
        txtCount.text = getString(R.string.packing_lists_count, items.size)
        txtEmpty.visibility = if (items.isEmpty() && txtError.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private inner class PLAdapter : RecyclerView.Adapter<PLAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card:    View      = view.findViewById(R.id.cardPL)
            val iconBg:  View      = view.findViewById(R.id.iconBgPL)
            val icon:    android.widget.ImageView = view.findViewById(R.id.iconPL)
            val name:    TextView  = view.findViewById(R.id.txtPLName)
            val sub:     TextView  = view.findViewById(R.id.txtPLSub)
            val badge:   TextView  = view.findViewById(R.id.txtPLBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_packing_list, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val pl = items[position]
            val markedAt = historicalDates[pl.packing_list_id]
            h.name.text = pl.packing_list_name.ifBlank { "PL ${pl.packing_list_id}" }
            val sub = buildString {
                append("ID ${pl.packing_list_id}")
                pl.total_spools_count?.let { append(" · $it spools") }
                if (markedAt != null) append(" · ${getString(R.string.pl_delivered_on, markedAt.take(10))}")
                else if (pl.packing_date.isNotBlank()) append(" · ${pl.packing_date.take(10)}")
            }
            h.sub.text = sub
            h.sub.visibility = View.VISIBLE

            // Históricos get a visually distinct, subdued look — separate from Actuales at a
            // glance even out of tab context (e.g. after a deep link or scroll).
            if (markedAt != null) {
                h.card.alpha = 0.72f
                h.iconBg.setBackgroundResource(R.drawable.bg_kpi_gray)
                h.icon.setImageResource(R.drawable.ic_history_24)
                h.icon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.on_surface_variant))
                h.badge.visibility = View.VISIBLE
            } else {
                h.card.alpha = 1f
                h.iconBg.setBackgroundResource(R.drawable.bg_kpi_success)
                h.icon.setImageResource(R.drawable.ic_clipboard_check)
                h.icon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.secondary))
                h.badge.visibility = View.GONE
            }

            h.itemView.setOnClickListener {
                val bundle = Bundle().apply { putLong("packingListId", pl.packing_list_id) }
                findNavController().navigate(R.id.action_global_packingListDetailFragment, bundle)
            }
        }
    }

}
