package com.example.hassiwrapper.ui.vehicles

import android.os.Bundle
import android.util.Log
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
import com.example.hassiwrapper.data.db.entities.SmsPackingListEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.SmsVehicleDto
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

private data class VehicleItem(
    val vehicle: SmsVehicleEntity,
    val packingLists: List<SmsPackingListEntity>,
    val originName: String? = null
)

class VehiclesFragment : Fragment() {

    private val items = mutableListOf<VehicleItem>()
    private lateinit var adapter: VehicleAdapter

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtError: TextView
    private lateinit var txtCount: TextView
    private lateinit var fabNewVehicle: FloatingActionButton
    private lateinit var fabSend: ExtendedFloatingActionButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_vehicles, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv            = view.findViewById(R.id.rvVehicles)
        swipe         = view.findViewById(R.id.swipeRefresh)
        progress      = view.findViewById(R.id.progress)
        txtEmpty      = view.findViewById(R.id.txtEmpty)
        txtError      = view.findViewById(R.id.txtError)
        txtCount      = view.findViewById(R.id.txtCount)
        fabNewVehicle = view.findViewById(R.id.fabNewVehicle)
        fabSend       = view.findViewById(R.id.fabSend)

        adapter = VehicleAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        fabNewVehicle.setOnClickListener {
            findNavController().navigate(R.id.action_global_newVehicleFragment)
        }

        fabSend.setOnClickListener {
            findNavController().navigate(R.id.action_global_sendPackingListFragment)
        }

        swipe.setOnRefreshListener { load(forceRefresh = true) }
        load(forceRefresh = true)
    }

    private fun load(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            txtError.visibility = View.GONE
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                Log.d("VehiclesDebug", "load() forceRefresh=$forceRefresh projectId=$projectId")

                val cached = ServiceLocator.smsVehicleDao.getByProject(projectId)
                Log.d("VehiclesDebug", "DB cache: ${cached.size} vehicles for project $projectId")

                if (cached.isNotEmpty() && !forceRefresh) {
                    items.clear()
                    items += buildVehicleItems(cached)
                    adapter.notifyDataSetChanged()
                    showLoading(false)
                    refreshCounts()
                    return@launch
                }

                val project = ServiceLocator.projectDao.getById(projectId)
                Log.d("VehiclesDebug", "Project lookup id=$projectId → $project")
                val projectCode = project?.project_code
                if (projectCode.isNullOrBlank()) {
                    val msg = getString(R.string.vehicles_error_project_code, projectId)
                    Log.e("VehiclesDebug", msg)
                    showError(getString(R.string.vehicles_error_prefix, msg))
                    return@launch
                }

                Log.d("VehiclesDebug", "Calling API getVehicles(projectCode=$projectCode)")
                val resp = ServiceLocator.apiClient.getService().getVehicles(projectCode)
                Log.d("VehiclesDebug", "API response: code=${resp.code()} successful=${resp.isSuccessful}")

                if (resp.isSuccessful) {
                    val raw = resp.body()?.string().orEmpty()
                    Log.d("VehiclesJSON", "Raw (first 500): ${raw.take(500)}")
                    val entities = parseVehicleEntities(raw, projectId)
                    Log.d("VehiclesDebug", "Parsed ${entities.size} vehicle entities")
                    if (entities.isNotEmpty()) {
                        ServiceLocator.smsVehicleDao.insertAll(entities)
                    }
                    val vehicles = ServiceLocator.smsVehicleDao.getByProject(projectId)
                    Log.d("VehiclesDebug", "Displaying ${vehicles.size} vehicles after insert")
                    items.clear()
                    items += buildVehicleItems(vehicles)
                    adapter.notifyDataSetChanged()
                } else {
                    val errBody = resp.errorBody()?.string().orEmpty()
                    Log.e("VehiclesDebug", "HTTP error ${resp.code()}: $errBody")
                    showError(getString(R.string.vehicles_error_http, resp.code()))
                    if (items.isEmpty()) {
                        val fallback = ServiceLocator.smsVehicleDao.getByProject(projectId)
                        items += buildVehicleItems(fallback)
                        adapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e("VehiclesDebug", "Exception in load()", e)
                showError(e.message ?: e.javaClass.simpleName)
                if (items.isEmpty()) {
                    try {
                        val fallbackId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                        val fallback = ServiceLocator.smsVehicleDao.getByProject(fallbackId)
                        items += buildVehicleItems(fallback)
                        adapter.notifyDataSetChanged()
                    } catch (e2: Exception) {
                        Log.e("VehiclesDebug", "Fallback DB read also failed", e2)
                    }
                }
            } finally {
                showLoading(false)
                refreshCounts()
            }
        }
    }

    private suspend fun buildVehicleItems(vehicles: List<SmsVehicleEntity>): List<VehicleItem> =
        vehicles.map { v ->
            val pls = ServiceLocator.smsPackingListDao.getByVehiclePlate(v.license_plate)
            val originName = resolveOriginName(pls)
            VehicleItem(v, pls, originName)
        }

    private suspend fun resolveOriginName(pls: List<SmsPackingListEntity>): String? {
        val positionIds = pls.mapNotNull { it.position_id }.distinct()
        if (positionIds.isEmpty()) return null
        val names = positionIds.mapNotNull { id ->
            ServiceLocator.smsPositionDao.getById(id)?.name?.takeIf { it.isNotBlank() }
        }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> names[0]
            else -> names.joinToString(" / ")
        }
    }

    private fun parseVehicleEntities(raw: String, defaultProjectId: Int): List<SmsVehicleEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("data", "items", "results", "vehicles").asSequence()
                        .mapNotNull { obj.get(it) }
                        .firstOrNull { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsVehicleDto::class.java)
                    val entity = dto.toEntity(defaultProjectId)
                    if (entity.vehicle_id == 0L) null else entity
                } catch (e: Exception) {
                    Log.w("VehiclesJSON", "Failed to parse vehicle element", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("VehiclesJSON", "Parse error", e)
            emptyList()
        }
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading && items.isEmpty()) View.VISIBLE else View.GONE
        if (!loading) swipe.isRefreshing = false
    }

    private fun showError(message: String) {
        txtError.visibility = View.VISIBLE
        txtError.text = getString(R.string.vehicles_error_prefix, message)
    }

    private fun refreshCounts() {
        txtCount.text = getString(R.string.vehicles_count, items.size)
        txtEmpty.visibility = if (items.isEmpty() && txtError.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private inner class VehicleAdapter : RecyclerView.Adapter<VehicleAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card:        CardView = view.findViewById(R.id.cardVehicle)
            val title:       TextView = view.findViewById(R.id.txtVehicleTitle)
            val subtitle:    TextView = view.findViewById(R.id.txtVehicleSubtitle)
            val readyBadge:  TextView = view.findViewById(R.id.txtReadyBadge)
            val originBadge: TextView = view.findViewById(R.id.txtOriginBadge)
            val packetList:  TextView = view.findViewById(R.id.txtPacketList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vehicle_card, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            val v = item.vehicle
            val pls = item.packingLists

            h.title.text = v.license_plate.ifBlank { getString(R.string.vehicles_label_fallback, v.vehicle_id) }
            val sub = listOfNotNull(v.vehicle_name, v.vehicle_type, v.company).joinToString(" · ")
            h.subtitle.text = sub.ifBlank { getString(R.string.vehicles_plate_format, v.license_plate) }

            val readyToSend = pls.isNotEmpty() && pls.all { it.ready_to_send }

            when {
                readyToSend -> {
                    h.card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_ready))
                    h.packetList.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
                    h.packetList.text = pls.joinToString("\n") { it.packing_list_name }
                }
                pls.isEmpty() -> {
                    h.card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface_warning))
                    h.packetList.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning))
                    h.packetList.text = getString(R.string.vehicles_no_packing_lists)
                }
                else -> {
                    h.card.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface))
                    h.packetList.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                    h.packetList.text = pls.joinToString("\n") { "• ${it.packing_list_name}" }
                }
            }

            h.readyBadge.visibility = if (readyToSend) View.VISIBLE else View.GONE

            if (item.originName != null) {
                h.originBadge.text = getString(R.string.vehicles_origin_badge, item.originName)
                h.originBadge.visibility = View.VISIBLE
            } else {
                h.originBadge.visibility = View.GONE
            }

            h.card.setOnClickListener { navigateToVehicleDetail(item) }
        }
    }

    private fun navigateToVehicleDetail(item: VehicleItem) {
        val args = Bundle().apply { putLong("vehicleId", item.vehicle.vehicle_id) }
        findNavController().navigate(R.id.action_global_vehicleDetailFragment, args)
    }
}
