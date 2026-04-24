package com.example.hassiwrapper.ui.vehicles

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.VehicleEntity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VehiclesFragment : Fragment() {

    private val vehicles = mutableListOf<VehicleEntity>()
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vehicles, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvVehicles)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = VehicleAdapter()

        val search = view.findViewById<EditText>(R.id.inputSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    loadVehicles(s.toString().trim())
                }
            }
        })

        loadVehicles("")
    }

    private fun loadVehicles(query: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (query.isNotEmpty()) {
                ServiceLocator.vehicleDao.search(query)
            } else {
                ServiceLocator.vehicleDao.getAll()
            }
            vehicles.clear()
            vehicles.addAll(result)
            view?.let { v ->
                v.findViewById<TextView>(R.id.txtVehicleCount).text = getString(R.string.vehicles_count, vehicles.size)
                v.findViewById<RecyclerView>(R.id.rvVehicles).adapter?.notifyDataSetChanged()
            }
        }
    }

    inner class VehicleAdapter : RecyclerView.Adapter<VehicleAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.txtVehicleTitle)
            val subtitle: TextView = view.findViewById(R.id.txtVehicleSubtitle)
            val packetList: TextView = view.findViewById(R.id.txtPacketList)
            val btnAssign: MaterialButton = view.findViewById(R.id.btnAssignPacketList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vehicle_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = vehicles[position]
            holder.title.text = "${v.identifier} — ${v.asset_name}"
            holder.subtitle.text = buildString {
                if (v.license_plate.isNotBlank()) append(v.license_plate)
                if (v.vehicle_type_name.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append(v.vehicle_type_name)
                }
                if (v.contractor_name.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append(v.contractor_name)
                }
            }

            // Packet-list association is not yet stored in the DB — show
            // a placeholder and a stub button until the ATLAS schema is in place.
            holder.packetList.text = getString(R.string.vehicles_packet_list_none)
            holder.btnAssign.setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    R.string.vehicles_assign_packet_list,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun getItemCount() = vehicles.size
    }
}
