package com.example.hassiwrapper.ui.vehicles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.network.dto.UpdateVehicleRequest
import com.example.hassiwrapper.services.OutboxService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EditVehicleFragment : Fragment() {

    private var vehicleId = 0L

    private lateinit var tilLicensePlate: TextInputLayout
    private lateinit var etLicensePlate: TextInputEditText
    private lateinit var etVehicleName: TextInputEditText
    private lateinit var etCompany: TextInputEditText
    private lateinit var etVehicleType: TextInputEditText
    private lateinit var etCapacityKg: TextInputEditText
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnCancel: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicleId = arguments?.getLong("vehicleId") ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_edit_vehicle, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = { findNavController().navigateUp() }
        tilLicensePlate = view.findViewById(R.id.tilLicensePlate)
        etLicensePlate  = view.findViewById(R.id.etLicensePlate)
        etVehicleName   = view.findViewById(R.id.etVehicleName)
        etCompany       = view.findViewById(R.id.etCompany)
        etVehicleType   = view.findViewById(R.id.etVehicleType)
        etCapacityKg    = view.findViewById(R.id.etCapacityKg)
        btnUpdate       = view.findViewById(R.id.btnUpdateVehicle)
        btnCancel       = view.findViewById(R.id.btnCancelEdit)

        btnCancel.setOnClickListener { findNavController().navigateUp() }
        btnUpdate.setOnClickListener { saveEdit() }

        viewLifecycleOwner.lifecycleScope.launch { prefill() }
    }

    private suspend fun prefill() {
        val v = ServiceLocator.smsVehicleDao.getById(vehicleId) ?: return
        etLicensePlate.setText(v.license_plate)
        etVehicleName.setText(v.vehicle_name.orEmpty())
        etCompany.setText(v.company.orEmpty())
        etVehicleType.setText(v.vehicle_type.orEmpty())
        etCapacityKg.setText(v.capacity_weight_kg?.toString().orEmpty())
    }

    private fun saveEdit() {
        val plate = etLicensePlate.text?.toString()?.trim().orEmpty().uppercase()
        tilLicensePlate.error = null
        if (plate.isBlank()) {
            tilLicensePlate.error = getString(R.string.new_vehicle_error_plate_required)
            return
        }
        btnUpdate.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val existing = ServiceLocator.smsVehicleDao.getById(vehicleId) ?: run {
                    btnUpdate.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.edit_vehicle_error_not_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val company     = etCompany.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val vehicleName = etVehicleName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val vehicleType = etVehicleType.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val capacityKg  = etCapacityKg.text?.toString()?.trim()?.toDoubleOrNull()

                val updated = existing.copy(
                    license_plate      = plate,
                    company            = company,
                    vehicle_name       = vehicleName,
                    vehicle_type       = vehicleType,
                    capacity_weight_kg = capacityKg,
                    updated_at         = now,
                    synced             = false
                )
                ServiceLocator.smsVehicleDao.insertAll(listOf(updated))

                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
                ServiceLocator.outboxService.enqueue(
                    OutboxService.Entity.VEHICLE, OutboxService.Op.UPDATE, vehicleId, projectId,
                    payload = UpdateVehicleRequest(
                        vehicleId        = vehicleId,
                        licensePlate     = plate,
                        company          = company,
                        vehicleName      = vehicleName,
                        vehicleType      = vehicleType,
                        capacityWeightKg = capacityKg,
                        updatedBy        = "APP",
                        projectCode      = projectCode ?: ""
                    )
                )

                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.VEHICULO_EDITADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_VEHICULO,
                    vehicleId, plate, projectId = projectId
                )
                Toast.makeText(requireContext(), getString(R.string.edit_vehicle_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnUpdate.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.edit_vehicle_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
