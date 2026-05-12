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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

/**
 * Packing Lists screen.
 * GET /api/atlas/projects/{projectCode}/packing-lists                → list
 * GET /api/atlas/projects/{projectCode}/packing-lists/{id}/spools    → spools per PL
 *
 * Field names are discovered at runtime (backend uses camelCase); the title
 * is whatever string-ish "name" / "id" key we can find.
 */
class PackingListsFragment : Fragment() {

    private val items = mutableListOf<JsonObject>()
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
        swipe.setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                if (projectCode.isNullOrBlank()) {
                    showError("missing projectCode for project_id=$projectId")
                    return@launch
                }
                val resp = ServiceLocator.apiClient.getService().getPackingLists(projectCode)
                if (resp.isSuccessful) {
                    val raw = resp.body()?.string().orEmpty()
                    Log.d("PackingListsJSON", "Raw: $raw")
                    val parsed = parseObjects(raw)
                    if (parsed.isNotEmpty()) {
                        Log.d("PackingListsJSON", "First keys: ${parsed[0].keySet()}")
                    }
                    items.clear()
                    items += parsed
                    adapter.notifyDataSetChanged()
                } else {
                    showError(getString(R.string.packing_lists_error_http, resp.code()))
                }
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
            } finally {
                showLoading(false)
                refreshCounts()
            }
        }
    }

    private fun parseObjects(raw: String): List<JsonObject> = try {
        val el = JsonParser.parseString(raw)
        when {
            el.isJsonArray -> el.asJsonArray.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                val arr = listOf("data", "items", "results", "packingLists", "packing_lists").asSequence()
                    .mapNotNull { obj.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
                arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
            }
            else -> emptyList()
        }
    } catch (e: Exception) {
        Log.e("PackingListsJSON", "Parse error", e); emptyList()
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

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this.get(k) ?: continue
            if (v.isJsonNull) continue
            val s = try { v.asString } catch (_: Exception) { v.toString().trim('"') }
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    private fun titleFor(o: JsonObject): String =
        o.str("name", "packingListName", "packing_list_name", "code", "title") ?: "PL"

    private fun idFor(o: JsonObject): String? =
        o.str("id", "packingListId", "packing_list_id", "Id", "pkId")

    private inner class PLAdapter : RecyclerView.Adapter<PLAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.txtPLName)
            val sub:  TextView = view.findViewById(R.id.txtPLSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_packing_list, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val o = items[position]
            h.name.text = titleFor(o)
            val id = idFor(o)
            h.sub.text = if (id != null) "ID $id" else ""
            h.sub.visibility = if (id != null) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { openSpools(o) }
        }
    }

    private fun openSpools(pl: JsonObject) {
        val id = idFor(pl) ?: run {
            AlertDialog.Builder(requireContext())
                .setTitle(titleFor(pl))
                .setMessage("Sin id en JSON; no se puede consultar los spools.\n\n$pl")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val title = titleFor(pl)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage("Cargando spools...")
            .setPositiveButton(android.R.string.ok, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                    ?: run { dialog.setMessage("Falta projectCode"); return@launch }
                val resp = ServiceLocator.apiClient.getService().getPackingListSpools(projectCode, id)
                if (!resp.isSuccessful) {
                    dialog.setMessage("HTTP ${resp.code()}"); return@launch
                }
                val raw = resp.body()?.string().orEmpty()
                Log.d("PackingListsJSON", "Spools raw: $raw")
                val spools = parseObjects(raw)
                if (spools.isEmpty()) {
                    dialog.setMessage("Sin spools en este packing list."); return@launch
                }
                val body = spools.joinToString("\n") { s ->
                    "• ${s.str("spoolId", "spool_id", "id") ?: "?"}" +
                        (s.str("description", "spoolCode")?.let { "  — $it" } ?: "")
                }
                dialog.setMessage("${spools.size} spool(s):\n\n$body")
            } catch (e: Exception) {
                dialog.setMessage(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
