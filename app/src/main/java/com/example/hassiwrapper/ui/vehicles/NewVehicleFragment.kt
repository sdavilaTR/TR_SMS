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
import com.example.hassiwrapper.data.db.entities.SmsVehicleEntity
import com.example.hassiwrapper.network.dto.CreateVehicleRequest
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NewVehicleFragment : Fragment() {

    private lateinit var tilLicensePlate: TextInputLayout
    private lateinit var etLicensePlate: TextInputEditText
    private lateinit var etVehicleName: TextInputEditText
    private lateinit var etCompany: TextInputEditText
    private lateinit var etVehicleType: TextInputEditText
    private lateinit var etCapacityKg: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_vehicle, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tilLicensePlate = view.findViewById(R.id.tilLicensePlate)
        etLicensePlate  = view.findViewById(R.id.etLicensePlate)
        etVehicleName   = view.findViewById(R.id.etVehicleName)
        etCompany       = view.findViewById(R.id.etCompany)
        etVehicleType   = view.findViewById(R.id.etVehicleType)
        etCapacityKg    = view.findViewById(R.id.etCapacityKg)
        btnSave         = view.findViewById(R.id.btnSaveVehicle)
        btnSave.setOnClickListener { saveVehicle() }
    }

    private fun saveVehicle() {
        val plate = etLicensePlate.text?.toString()?.trim().orEmpty().uppercase()
        tilLicensePlate.error = null
        if (plate.isBlank()) {
            tilLicensePlate.error = getString(R.string.new_vehicle_error_plate_required)
            return
        }
        btnSave.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId   = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val vehicleId   = (ServiceLocator.smsVehicleDao.getMaxId() ?: 0L) + 1L
                val now         = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val company     = etCompany.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val vehicleName = etVehicleName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val vehicleType = etVehicleType.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val capacityKg  = etCapacityKg.text?.toString()?.trim()?.toDoubleOrNull()

                val entity = SmsVehicleEntity(
                    vehicle_id         = vehicleId,
                    project_id         = projectId,
                    license_plate      = plate,
                    company            = company,
                    vehicle_name       = vehicleName,
                    vehicle_type       = vehicleType,
                    capacity_weight_kg = capacityKg,
                    is_active          = true,
                    created_at         = now,
                    created_by         = "APP",
                    synced             = false
                )
                ServiceLocator.smsVehicleDao.insertAll(listOf(entity))

                try {
                    val project = ServiceLocator.projectDao.getById(projectId)
                    val projectCode = project?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val body = CreateVehicleRequest(
                            licensePlate     = plate,
                            company          = company,
                            vehicleName      = vehicleName,
                            vehicleType      = vehicleType,
                            capacityWeightKg = capacityKg,
                            createdBy        = "APP",
                            projectCode      = projectCode
                        )
                        val resp = ServiceLocator.apiClient.getService().createVehicle(projectCode, body)
                        if (resp.isSuccessful) {
                            val raw = resp.body()?.string().orEmpty()
                            val serverId = parseCreatedVehicleId(raw)
                            if (serverId != null && serverId > 0L && serverId != vehicleId) {
                                ServiceLocator.smsVehicleDao.deleteById(vehicleId)
                                ServiceLocator.smsVehicleDao.insertAll(listOf(entity.copy(vehicle_id = serverId, synced = true)))
                            } else {
                                ServiceLocator.smsVehicleDao.markSynced(listOf(vehicleId))
                            }
                        }
                    }
                } catch (_: Exception) {}

                Toast.makeText(requireContext(), getString(R.string.new_vehicle_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.new_vehicle_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseCreatedVehicleId(raw: String): Long? {
        return try {
            val el = JsonParser.parseString(raw)
            val obj = when {
                el.isJsonObject && el.asJsonObject.has("data") && !el.asJsonObject.get("data").isJsonNull ->
                    el.asJsonObject.getAsJsonObject("data")
                el.isJsonObject -> el.asJsonObject
                else -> return null
            }
            obj.get("vehicleId")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("vehicle_id")?.takeIf { !it.isJsonNull }?.asLong
                ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        } catch (_: Exception) { null }
    }
}
