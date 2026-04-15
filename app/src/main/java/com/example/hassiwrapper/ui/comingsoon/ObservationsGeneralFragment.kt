package com.example.hassiwrapper.ui.comingsoon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.PersonEntity
import com.example.hassiwrapper.data.db.entities.VehicleEntity
import com.example.hassiwrapper.services.ObservationService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Observations HSE hub: scan observer, select target type, navigate to ObservationFragment.
 */
class ObservationsGeneralFragment : Fragment() {

    private enum class ScanMode { NONE, OBSERVER, WORKER, VEHICLE }

    private var scanMode: ScanMode = ScanMode.NONE
    private var observer: PersonEntity? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_observations_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btnScanObserver).setOnClickListener {
            scanMode = ScanMode.OBSERVER
            Toast.makeText(requireContext(), getString(R.string.obs_scan_observer), Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnTargetWorker).setOnClickListener {
            if (!requireObserver()) return@setOnClickListener
            scanMode = ScanMode.WORKER
            Toast.makeText(requireContext(), getString(R.string.obs_target_worker), Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnTargetVehicle).setOnClickListener {
            if (!requireObserver()) return@setOnClickListener
            scanMode = ScanMode.VEHICLE
            Toast.makeText(requireContext(), getString(R.string.obs_target_vehicle), Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnTargetSite).setOnClickListener {
            if (!requireObserver()) return@setOnClickListener
            navigateToObservation(ObservationService.TARGET_SITE, null, null)
        }

        view.findViewById<MaterialButton>(R.id.btnTargetEquipment).setOnClickListener {
            if (!requireObserver()) return@setOnClickListener
            navigateToObservation(ObservationService.TARGET_EQUIPMENT, null, null)
        }

        // Listen DataWedge scans (same pattern as ScannerFragment)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { code ->
                    handleScan(code.trim())
                }
            }
        }
    }

    private fun requireObserver(): Boolean {
        if (observer == null) {
            Toast.makeText(requireContext(), getString(R.string.obs_observer_required), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun handleScan(code: String) {
        when (scanMode) {
            ScanMode.OBSERVER -> handleObserverScan(code)
            ScanMode.WORKER -> handleWorkerScan(code)
            ScanMode.VEHICLE -> handleVehicleScan(code)
            ScanMode.NONE -> { /* ignore */ }
        }
    }

    private fun handleObserverScan(code: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val person = ServiceLocator.personDao.getByBadge(code)
                ?: ServiceLocator.personDao.getByUuid(code)
            if (person == null) {
                Toast.makeText(requireContext(), getString(R.string.obs_observer_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!ObservationService.isHseRole(person)) {
                Toast.makeText(requireContext(), getString(R.string.obs_not_hse_error), Toast.LENGTH_LONG).show()
                return@launch
            }
            observer = person
            scanMode = ScanMode.NONE
            val name = "${person.given_name} ${person.family_name}".trim()
            val summary = getString(R.string.obs_observer_format, name, person.position.ifBlank { "—" })
            view?.findViewById<TextView>(R.id.txtObserverInfo)?.text = summary
        }
    }

    private fun handleWorkerScan(code: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val person = ServiceLocator.personDao.getByBadge(code)
                ?: ServiceLocator.personDao.getByUuid(code)
            if (person == null) {
                Toast.makeText(requireContext(), getString(R.string.obs_observer_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            scanMode = ScanMode.NONE
            navigateToObservation(ObservationService.TARGET_WORKER, person, null)
        }
    }

    private fun handleVehicleScan(code: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val vDao = ServiceLocator.vehicleDao
            val vehicle = vDao.getByUuid(code)
                ?: vDao.search(code).firstOrNull { it.identifier == code || it.license_plate == code }
                ?: vDao.search(code).firstOrNull()
            if (vehicle == null) {
                Toast.makeText(requireContext(), getString(R.string.obs_observer_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            scanMode = ScanMode.NONE
            navigateToObservation(ObservationService.TARGET_VEHICLE, null, vehicle)
        }
    }

    private fun navigateToObservation(targetType: String, worker: PersonEntity?, vehicle: VehicleEntity?) {
        val obs = observer ?: return
        val contractorName = obs.contractor_id?.let { cid ->
            // Best-effort sync lookup; observer contractor often missing, fallback blank
            null
        }
        val args = Bundle().apply {
            putString("target_type", targetType)
            putString("observer_unique_id", obs.unique_id_value)
            putString("observer_name", "${obs.given_name} ${obs.family_name}".trim())
            putString("observer_position", obs.position)
            putString("observer_contractor", contractorName)
            if (worker != null) {
                putString("unique_id_value", worker.unique_id_value)
                putString("observed_name", "${worker.given_name} ${worker.family_name}".trim())
                putString("observed_badge", worker.badge_number)
                putString("observed_department", worker.category_code)
                putString("observed_position", worker.position)
            }
            if (vehicle != null) {
                putLong("vehicle_asset_id", vehicle.asset_id)
                putString("vehicle_identifier", vehicle.identifier)
                putString("vehicle_name", vehicle.asset_name)
                putString("vehicle_type", vehicle.vehicle_type_name)
                putString("vehicle_contractor", vehicle.contractor_name)
            }
        }
        findNavController().navigate(R.id.observationFragment, args)
    }
}
