package com.example.hassiwrapper.ui.createspool

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
 * "Spools List" — shows the contents of [sms].[sms_spool] from ATLAS via
 * GET /api/atlas/projects/{projectCode}/spools.
 *
 * The backend returns objects with these keys (camelCase):
 *   spoolId, description, diameter, priority, status, zone, inTransit,
 *   qrGenerated, packingListName, packingListId, lastModifiedBy,
 *   prohectCode (sic), assignedUnit
 */
class CreateSpoolFragment : Fragment() {

    private val items = mutableListOf<JsonObject>()
    private lateinit var adapter: SpoolAdapter

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtError: TextView
    private lateinit var txtCount: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_create_spool, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv       = view.findViewById(R.id.rvSpools)
        swipe    = view.findViewById(R.id.swipeRefresh)
        progress = view.findViewById(R.id.progress)
        txtEmpty = view.findViewById(R.id.txtEmpty)
        txtError = view.findViewById(R.id.txtError)
        txtCount = view.findViewById(R.id.txtCount)

        adapter = SpoolAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        swipe.setOnRefreshListener { loadSpools() }
        loadSpools()
    }

    private fun loadSpools() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                if (projectCode.isNullOrBlank()) {
                    showError(getString(R.string.spools_list_error_prefix, "missing projectCode for project_id=$projectId"))
                    return@launch
                }
                val api = ServiceLocator.apiClient.getService()
                val response = api.getSpools(projectCode)
                if (response.isSuccessful) {
                    val raw = response.body()?.string().orEmpty()
                    Log.d("SpoolsJSON", "Raw response (${raw.length} chars): $raw")
                    val parsed: List<JsonObject> = try {
                        val el = JsonParser.parseString(raw)
                        when {
                            el.isJsonArray -> el.asJsonArray.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                            el.isJsonObject -> {
                                val obj = el.asJsonObject
                                val arr = listOf("data", "items", "results", "spools").asSequence()
                                    .mapNotNull { obj.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
                                arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
                            }
                            else -> emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e("SpoolsJSON", "Parse error", e); emptyList()
                    }
                    items.clear()
                    items += parsed
                    adapter.notifyDataSetChanged()
                } else {
                    showError(getString(R.string.spools_list_error_http, response.code()))
                }
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
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
        txtError.text = getString(R.string.spools_list_error_prefix, message)
    }

    private fun refreshCounts() {
        txtCount.text = getString(R.string.spools_list_count, items.size)
        txtEmpty.visibility = if (items.isEmpty() && txtError.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun JsonObject.str(key: String): String? {
        val v = this.get(key) ?: return null
        if (v.isJsonNull) return null
        return try { v.asString } catch (_: Exception) { v.toString().trim('"') }
    }

    private inner class SpoolAdapter : RecyclerView.Adapter<SpoolAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val code:    TextView = view.findViewById(R.id.txtSpoolCode)
            val line:    TextView = view.findViewById(R.id.txtSpoolLine)
            val details: TextView = view.findViewById(R.id.txtSpoolDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spool, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val o = items[position]
            h.code.text = o.str("spoolSuffix")
                ?: o.str("spool_suffix")
                ?: o.str("suffix")
                ?: o.str("spoolId")
                ?: "(sin código)"
            h.line.visibility = View.GONE
            h.details.visibility = View.GONE
            h.itemView.setOnClickListener { showSpoolDialog(o) }
        }
    }

    private fun showSpoolDialog(o: JsonObject) {
        val labels = mapOf(
            "spoolId"         to "Spool ID",
            "description"     to "Descripción",
            "diameter"        to "Diámetro",
            "priority"        to "Prioridad",
            "status"          to "Estado",
            "zone"            to "Zona",
            "inTransit"       to "En tránsito",
            "qrGenerated"     to "QR generado",
            "packingListName" to "Packing list",
            "packingListId"   to "Packing list ID",
            "lastModifiedBy"  to "Modificado por",
            "prohectCode"     to "Project code",
            "assignedUnit"    to "Unidad asignada"
        )
        val body = buildString {
            // First the known fields in the order above
            for ((key, label) in labels) {
                val v = o.str(key)
                append(label).append(": ").append(if (v.isNullOrBlank()) "—" else v).append('\n')
            }
            // Then any unexpected extra keys, so nothing is hidden
            for (k in o.keySet()) {
                if (k !in labels) {
                    val v = o.str(k)
                    append(k).append(": ").append(if (v.isNullOrBlank()) "—" else v).append('\n')
                }
            }
        }.trimEnd()
        AlertDialog.Builder(requireContext())
            .setTitle(o.str("spoolId") ?: "Spool")
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
