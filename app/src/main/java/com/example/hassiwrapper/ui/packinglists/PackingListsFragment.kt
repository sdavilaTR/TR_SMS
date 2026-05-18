package com.example.hassiwrapper.ui.packinglists

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.network.dto.SmsPackingListDto
import com.example.hassiwrapper.network.dto.SpoolDto
import com.google.gson.Gson
import com.google.gson.JsonParser
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

        adapter = PLAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        swipe.setOnRefreshListener { load(forceRefresh = true) }
        load(forceRefresh = true)
    }

    private fun load(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                Log.d("PackingListsDebug", "load() forceRefresh=$forceRefresh projectId=$projectId")

                val cached = ServiceLocator.smsPackingListDao.getByProject(projectId)
                Log.d("PackingListsDebug", "DB cache: ${cached.size} packing lists for project $projectId")

                if (cached.isNotEmpty() && !forceRefresh) {
                    items.clear()
                    items += cached
                    adapter.notifyDataSetChanged()
                    showLoading(false)
                    refreshCounts()
                    return@launch
                }

                val project = ServiceLocator.projectDao.getById(projectId)
                Log.d("PackingListsDebug", "Project lookup id=$projectId → $project")
                val projectCode = project?.project_code
                if (projectCode.isNullOrBlank()) {
                    val msg = "projectCode nulo/vacío para project_id=$projectId. ¿Se hizo sync?"
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
                    if (entities.isNotEmpty()) {
                        ServiceLocator.smsPackingListDao.insertAll(entities)
                    }
                    items.clear()
                    items += ServiceLocator.smsPackingListDao.getByProject(projectId)
                    Log.d("PackingListsDebug", "Displaying ${items.size} packing lists after insert")
                    adapter.notifyDataSetChanged()
                } else {
                    val errBody = resp.errorBody()?.string().orEmpty()
                    Log.e("PackingListsDebug", "HTTP error ${resp.code()}: $errBody")
                    showError(getString(R.string.packing_lists_error_http, resp.code()))
                    if (items.isEmpty()) {
                        items += ServiceLocator.smsPackingListDao.getByProject(projectId)
                        adapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e("PackingListsDebug", "Exception in load()", e)
                showError(e.message ?: e.javaClass.simpleName)
                if (items.isEmpty()) {
                    try {
                        val fallbackId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                        items += ServiceLocator.smsPackingListDao.getByProject(fallbackId)
                        adapter.notifyDataSetChanged()
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

    private fun parsePackingListEntities(raw: String, defaultProjectId: Int): List<SmsPackingListEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "packingLists", "packing_lists").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsPackingListDto::class.java)
                    val entity = dto.toEntity(defaultProjectId)
                    if (entity.packing_list_id == 0L) null else entity
                } catch (e: Exception) {
                    Log.w("PackingListsJSON", "Failed to parse PL element", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("PackingListsJSON", "Parse error", e)
            emptyList()
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
            val name: TextView = view.findViewById(R.id.txtPLName)
            val sub:  TextView = view.findViewById(R.id.txtPLSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_packing_list, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val pl = items[position]
            h.name.text = pl.packing_list_name.ifBlank { "PL ${pl.packing_list_id}" }
            val sub = buildString {
                append("ID ${pl.packing_list_id}")
                pl.total_spools_count?.let { append(" · $it spools") }
                if (pl.packing_date.isNotBlank()) append(" · ${pl.packing_date.take(10)}")
            }
            h.sub.text = sub
            h.sub.visibility = View.VISIBLE
            h.itemView.setOnClickListener { openSpools(pl) }
        }
    }

    private fun openSpools(pl: SmsPackingListEntity) {
        val title = pl.packing_list_name.ifBlank { "PL ${pl.packing_list_id}" }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage("Cargando spools...")
            .setPositiveButton(android.R.string.ok, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                var spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                if (spools.isEmpty()) {
                    val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                        ?: run { dialog.setMessage("Falta projectCode"); return@launch }
                    val resp = ServiceLocator.apiClient.getService()
                        .getPackingListSpools(projectCode, pl.packing_list_id.toString())
                    if (!resp.isSuccessful) {
                        dialog.setMessage("HTTP ${resp.code()}"); return@launch
                    }
                    val raw = resp.body()?.string().orEmpty()
                    Log.d("PackingListsJSON", "Spools raw: $raw")
                    val entities = parseSpoolEntities(raw, projectId, pl.packing_list_id)
                    if (entities.isNotEmpty()) {
                        ServiceLocator.smsSpoolDao.insertAll(entities)
                    }
                    spools = ServiceLocator.smsSpoolDao.getByPackingList(pl.packing_list_id)
                }
                if (spools.isEmpty()) {
                    dialog.setMessage("Sin spools en este packing list.")
                    return@launch
                }
                val body = spools.joinToString("\n") { s ->
                    "• ${s.displayCode}" + (s.line_code?.let { "  — $it" } ?: "")
                }
                dialog.setMessage("${spools.size} spool(s):\n\n$body")
            } catch (e: Exception) {
                dialog.setMessage(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun parseSpoolEntities(raw: String, defaultProjectId: Int, packingListId: Long): List<com.example.hassiwrapper.data.db.entities.SmsSpoolEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "spools").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SpoolDto::class.java)
                    val entity = dto.toEntity(defaultPackingListId = packingListId)
                    if (entity.spool_id == 0L) null
                    else entity.copy(project_id = defaultProjectId)
                } catch (e: Exception) {
                    Log.w("PackingListsJSON", "Failed to parse spool element", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("PackingListsJSON", "Parse error", e)
            emptyList()
        }
    }
}
