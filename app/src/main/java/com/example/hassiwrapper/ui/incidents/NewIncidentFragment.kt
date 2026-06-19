package com.example.hassiwrapper.ui.incidents

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.services.SmsIncidentService
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class NewIncidentFragment : Fragment() {

    companion object {
        private const val TAG = "NewIncidentFragment"
        private const val MAX_DIM = 1920
        private const val JPEG_QUALITY = 80
    }

    private val tempUuid: String = UUID.randomUUID().toString()
    private var cameraTempUri: Uri? = null
    private var photoPath: String? = null

    private lateinit var imgPhoto: ImageView
    private lateinit var btnTakePhoto: MaterialButton
    private lateinit var btnScanCode: MaterialButton
    private lateinit var actvLocation: AutoCompleteTextView
    private lateinit var actvSeverity: AutoCompleteTextView
    private lateinit var txtAutoPosition: android.widget.TextView
    private lateinit var txtAutoAuthor: android.widget.TextView
    private lateinit var etSpoolCode: TextInputEditText
    private lateinit var etSpoolSuffix: TextInputEditText
    private lateinit var etVehiclePlate: TextInputEditText

    private val locationCodes = SmsIncidentService.LOCATION_TYPES
    private val severityCodes = SmsIncidentService.SEVERITIES

    private val locationLabelIds = mapOf(
        "LAYDOWN" to R.string.incident_location_laydown,
        "SITE" to R.string.incident_location_site,
        "WORKSHOP" to R.string.incident_location_workshop
    )
    private val severityLabelIds = mapOf(
        "LOW" to R.string.incident_severity_low,
        "MEDIUM" to R.string.incident_severity_medium,
        "HIGH" to R.string.incident_severity_high,
        "CRITICAL" to R.string.incident_severity_critical
    )

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraTempUri
            if (success && uri != null) handlePhotoUri(uri)
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
        }

    private val scanCodeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val raw = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.trim()
                if (!raw.isNullOrBlank()) handleScannedCode(raw)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_incident, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = { findNavController().navigateUp() }

        imgPhoto = view.findViewById(R.id.imgIncidentPhoto)
        btnTakePhoto = view.findViewById(R.id.btnTakePhoto)
        btnScanCode = view.findViewById(R.id.btnScanIncidentCode)
        actvLocation = view.findViewById(R.id.actvIncidentLocation)
        actvSeverity = view.findViewById(R.id.actvIncidentSeverity)
        txtAutoPosition = view.findViewById(R.id.txtIncidentAutoPosition)
        txtAutoAuthor = view.findViewById(R.id.txtIncidentAutoAuthor)
        etSpoolCode = view.findViewById(R.id.etIncidentSpoolCode)
        etSpoolSuffix = view.findViewById(R.id.etIncidentSpoolSuffix)
        etVehiclePlate = view.findViewById(R.id.etIncidentVehiclePlate)

        val etDescription = view.findViewById<TextInputEditText>(R.id.etIncidentDescription)
        val etLocationDetail = view.findViewById<TextInputEditText>(R.id.etIncidentLocationDetail)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveIncident)

        setupDropdown(actvLocation, locationCodes) { locationLabelIds[it]?.let(::getString) ?: it }
        setupDropdown(actvSeverity, severityCodes) { severityLabelIds[it]?.let(::getString) ?: it }
        actvLocation.setText(locationLabelIds[locationCodes.first()]?.let(::getString), false)
        actvSeverity.setText(severityLabelIds[severityCodes.first()]?.let(::getString), false)

        loadAutoFilledFields()

        btnScanCode.setOnClickListener {
            scanCodeLauncher.launch(Intent(requireContext(), CustomScannerActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { raw ->
                    handleScannedCode(raw.trim())
                }
            }
        }

        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnSave.setOnClickListener {
            val spoolCode = etSpoolCode.text.toString().trim()
            val spoolSuffix = etSpoolSuffix.text.toString().trim().ifBlank { null }
            val description = etDescription.text.toString().trim()
            val vehiclePlate = etVehiclePlate.text.toString().trim().ifBlank { null }
            val locationDetail = etLocationDetail.text.toString().trim().ifBlank { null }
            val locationType = locationCodes.getOrNull(selectedIndex(actvLocation, locationCodes, locationLabelIds))
                ?: locationCodes.first()
            val severity = severityCodes.getOrNull(selectedIndex(actvSeverity, severityCodes, severityLabelIds))
                ?: severityCodes.first()

            if (spoolCode.isBlank()) {
                Toast.makeText(requireContext(), R.string.incident_error_missing_spool_code, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (description.isBlank()) {
                Toast.makeText(requireContext(), R.string.incident_error_missing_description, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val incident = ServiceLocator.smsIncidentService.createIncident(
                        spoolCode = spoolCode,
                        spoolSuffix = spoolSuffix,
                        description = description,
                        vehiclePlate = vehiclePlate,
                        locationType = locationType,
                        locationDetail = locationDetail,
                        severity = severity,
                        photoPath = photoPath
                    )
                    ServiceLocator.auditLogService.log(
                        com.example.hassiwrapper.services.AuditLogService.INCIDENCIA_CREADA,
                        com.example.hassiwrapper.services.AuditLogService.ENTITY_INCIDENCIA,
                        incident.id, incident.spool_code, projectId = incident.project_id
                    )
                    Toast.makeText(requireContext(), R.string.incident_saved_ok, Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                } catch (e: Exception) {
                    Log.e(TAG, "createIncident failed", e)
                    Toast.makeText(requireContext(), getString(R.string.incident_error_save, e.message), Toast.LENGTH_LONG).show()
                } finally {
                    btnSave.isEnabled = true
                }
            }
        }
    }

    private fun setupDropdown(actv: AutoCompleteTextView, codes: List<String>, label: (String) -> String) {
        val labels = codes.map(label)
        actv.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels))
        actv.setOnItemClickListener { _, _, _, _ -> }
    }

    private fun selectedIndex(actv: AutoCompleteTextView, codes: List<String>, labelIds: Map<String, Int>): Int {
        val current = actv.text.toString()
        return codes.indexOfFirst { labelIds[it]?.let(::getString) == current }.let { if (it < 0) 0 else it }
    }

    private fun handleScannedCode(raw: String) {
        if (raw.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = parseQr(raw)) {
                is QrResult.Spool -> {
                    etSpoolCode.setText(result.spoolCode)
                    etSpoolSuffix.setText(result.spoolSuffix ?: "")
                    Toast.makeText(requireContext(), getString(R.string.incident_scan_spool_filled, result.spoolCode), Toast.LENGTH_SHORT).show()
                }
                is QrResult.VehicleId -> {
                    val vehicle = ServiceLocator.smsVehicleDao.getById(result.id)
                    fillVehiclePlate(vehicle?.license_plate)
                }
                is QrResult.VehiclePlate -> {
                    val vehicle = ServiceLocator.smsVehicleDao.getByLicensePlate(result.plate)
                    fillVehiclePlate(vehicle?.license_plate ?: result.plate)
                }
                is QrResult.VehicleBadge, is QrResult.Unknown -> {
                    Toast.makeText(requireContext(), R.string.incident_scan_not_recognized, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fillVehiclePlate(plate: String?) {
        if (plate.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.incident_scan_vehicle_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        etVehiclePlate.setText(plate)
        Toast.makeText(requireContext(), getString(R.string.incident_scan_vehicle_filled, plate), Toast.LENGTH_SHORT).show()
    }

    private fun loadAutoFilledFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            val service = ServiceLocator.smsIncidentService
            val position = service.getCurrentPosition()
            val author = service.getAssignedOperatorName()
            txtAutoPosition.text = position?.let { "${it.code} · ${it.name}".trim(' ', '·') } ?: getString(R.string.incident_value_unknown)
            txtAutoAuthor.text = author?.takeIf { it.isNotBlank() } ?: getString(R.string.incident_value_unknown)
        }
    }

    private fun launchCamera() {
        val dir = File(requireContext().filesDir, "incidents/$tempUuid").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            file
        )
        cameraTempUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun handlePhotoUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val destPath = withContext(Dispatchers.IO) { compressAndSave(uri) }
                if (destPath != null) {
                    photoPath = destPath
                    imgPhoto.setImageURI(Uri.fromFile(File(destPath)))
                    imgPhoto.visibility = View.VISIBLE
                    btnTakePhoto.setText(R.string.incident_btn_retake_photo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photo capture failed: ${e.message}")
            }
        }
    }

    private fun compressAndSave(uri: Uri): String? {
        val ctx = requireContext()
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val larger = maxOf(bounds.outWidth, bounds.outHeight)
        while (larger / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val scaleFactor = MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scaleFactor < 1f) {
            val w = (bmp.width * scaleFactor).toInt()
            val h = (bmp.height * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp

        val dir = File(ctx.filesDir, "incidents/$tempUuid").apply { mkdirs() }
        val dest = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        FileOutputStream(dest).use { fos ->
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        return dest.absolutePath
    }
}
