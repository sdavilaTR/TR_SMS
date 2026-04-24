package com.example.hassiwrapper.ui.vehicles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.hassiwrapper.R
import com.google.android.material.button.MaterialButton

/**
 * Vehicles belong to Smart Material System, not to the ATLAS access-control
 * project — so this screen intentionally does NOT read from vehicleDao.
 * It stays as a placeholder until the ATLAS backend exposes its own vehicle
 * entity with a packet-list association.
 */
class VehiclesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_vehicles, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnAddVehicle).setOnClickListener {
            Toast.makeText(requireContext(), R.string.vehicles_add, Toast.LENGTH_SHORT).show()
        }
    }
}
