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
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.network.dto.SpoolDto
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

class CreateSpoolFragment : Fragment() {

    private enum class Filter { ALL, ASSIGNED, UNASSIGNED }

    private val allItems  = mutableListOf<SmsSpoolEntity>()
    private val items     = mutableListOf<SmsSpoolEntity>()
    private var filter    = Filter.ALL
    private lateinit var adapter: SpoolAdapter

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtError: TextView
    private lateinit var txtCount: TextView
    private lateinit var toggleFilter: MaterialButtonToggleGroup

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

        adapter = SpoolAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        toggleFilter.check(R.id.btnFilterAll)
        toggleFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.btnFilterAssigned   -> Filter.ASSIGNED
                R.id.btnFilterUnassigned -> Filter.UNASSIGNED
                else                     -> Filter.ALL
            }
            applyFilter()
        }

        swipe.setOnRefreshListener { loadSpools(forceRefresh = true) }
        loadSpools(forceRefresh = true)
    }

    private fun applyFilter() {
        items.clear()
        items += when (filter) {
            Filter.ALL        -> allItems
            Filter.ASSIGNED   -> allItems.filter { it.packing_list_id != null }
            Filter.UNASSIGNED -> allItems.filter { it.packing_list_id == null }
        }
        adapter.notifyDataSetChanged()
        refreshCounts()
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
                allInDb.forEach { s -> Log.d("SpoolsDebug", "  spool_id=${s.spool_id} code=${s.spool_code} pid=${s.project_id} active=${s.is_active} pl=${s.packing_list_id}") }
                txtCount.text = "DBG pid=$projectId total=$totalInDb pids=$projectIds proj=$countForProj active=$countActive ignoreActive=${allInDb.size}"

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
                    if (entities.isNotEmpty()) {
                        ServiceLocator.smsSpoolDao.deleteByProject(projectId)
                        ServiceLocator.smsSpoolDao.insertAll(entities)
                        Log.d("SpoolsDebug", "Inserted ${entities.size} spools")
                    } else {
                        Log.w("SpoolsDebug", "Parse returned 0 entities. Raw snippet: ${raw.take(300)}")
                    }
                    allItems.clear()
                    allItems += ServiceLocator.smsSpoolDao.getByProject(projectId)
                    Log.d("SpoolsDebug", "After insert, getByProject($projectId) = ${allItems.size}")
                    // If still empty, try ignoring is_active filter
                    if (allItems.isEmpty()) {
                        val ignoreActive = ServiceLocator.smsSpoolDao.getByProjectIgnoreActive(projectId)
                        Log.w("SpoolsDebug", "getByProjectIgnoreActive($projectId) = ${ignoreActive.size} (is_active may be 0)")
                        if (ignoreActive.isNotEmpty()) {
                            allItems += ignoreActive
                            showError("Advertencia: ${ignoreActive.size} spools con is_active=false")
                        }
                    }
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

    private fun parseSpoolEntities(raw: String, defaultProjectId: Int): List<SmsSpoolEntity> {
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
            array.mapIndexedNotNull { idx, element ->
                if (!element.isJsonObject) return@mapIndexedNotNull null
                Log.d("SpoolsRAW", "element[$idx]: $element")
                try {
                    val dto = gson.fromJson(element, SpoolDto::class.java)
                    val entity = dto.toEntity()
                    if (entity.spool_id == 0L) return@mapIndexedNotNull null
                    // When spoolId is a non-numeric string (no true PK from API), mix in the
                    // array index so identical-looking records get distinct primary keys.
                    val finalId = if (dto.spoolId?.toDoubleOrNull() == null && !dto.spoolId.isNullOrEmpty()) {
                        val key = "${dto.spoolId}-${dto.spoolSuffix.orEmpty()}-$idx"
                        val crc = java.util.zip.CRC32()
                        crc.update(key.toByteArray())
                        crc.value.toLong().takeIf { it != 0L } ?: (idx + 1L)
                    } else entity.spool_id
                    entity.copy(spool_id = finalId, project_id = defaultProjectId)
                } catch (e: Exception) {
                    Log.w("SpoolsJSON", "Failed to parse spool element[$idx]", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("SpoolsJSON", "Parse error", e)
            emptyList()
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
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val code:    TextView = view.findViewById(R.id.txtSpoolCode)
            val line:    TextView = view.findViewById(R.id.txtSpoolLine)
            val details: TextView = view.findViewById(R.id.txtSpoolDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spool, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val spool = items[position]
            h.code.text = spool.displayCode.ifBlank { spool.spool_id.toString() }
            if (!spool.line_code.isNullOrBlank()) {
                h.line.text = spool.line_code
                h.line.visibility = View.VISIBLE
            } else {
                h.line.visibility = View.GONE
            }
            val detail = listOfNotNull(spool.service, spool.train, spool.module).joinToString(" · ")
            if (detail.isNotBlank()) {
                h.details.text = detail
                h.details.visibility = View.VISIBLE
            } else {
                h.details.visibility = View.GONE
            }
            h.itemView.setOnClickListener { showSpoolDialog(spool) }
        }
    }

    private fun showSpoolDialog(spool: SmsSpoolEntity) {
        val body = buildString {
            appendLine("ID: ${spool.spool_id}")
            appendLine("Código: ${spool.displayCode}")
            spool.line_code?.let { appendLine("Línea: $it") }
            spool.service?.let { appendLine("Servicio: $it") }
            spool.train?.let { appendLine("Tren: $it") }
            spool.module?.let { appendLine("Módulo: $it") }
            spool.iso_revision_date?.let { appendLine("Fecha ISO: $it") }
            spool.area_id?.let { appendLine("Área ID: $it") }
            spool.spec_id?.let { appendLine("Spec ID: $it") }
            spool.subcontractor_id?.let { appendLine("Subcontratista ID: $it") }
            spool.packing_list_id?.let { appendLine("Packing List ID: $it") }
                ?: appendLine("Sin Packing List asignado")
            appendLine("Activo: ${if (spool.is_active) "Sí" else "No"}")
            appendLine("Creado: ${spool.created_at} por ${spool.created_by}")
            spool.updated_at?.let { appendLine("Actualizado: $it por ${spool.updated_by.orEmpty()}") }
        }.trimEnd()
        AlertDialog.Builder(requireContext())
            .setTitle(spool.displayCode.ifBlank { "Spool ${spool.spool_id}" })
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
