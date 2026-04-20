package com.example.hassiwrapper.ui.observation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.services.ObservationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

class ObservationFragment : Fragment() {

    companion object {
        private const val TAG = "ObservationFragment"
        const val ARG_UNIQUE_ID = "unique_id_value"
        const val ARG_NAME = "observed_name"
        const val ARG_BADGE = "observed_badge"
        const val ARG_DEPARTMENT = "observed_department"
        const val ARG_POSITION = "observed_position"
        const val ARG_CONTRACTOR = "observed_contractor"
        const val ARG_TARGET_TYPE = "target_type"
        const val ARG_OBSERVER_UNIQUE_ID = "observer_unique_id"
        const val ARG_OBSERVER_NAME = "observer_name"
        const val ARG_OBSERVER_POSITION = "observer_position"
        const val ARG_OBSERVER_CONTRACTOR = "observer_contractor"
        const val ARG_VEHICLE_ASSET_ID = "vehicle_asset_id"
        const val ARG_VEHICLE_IDENTIFIER = "vehicle_identifier"
        const val ARG_VEHICLE_NAME = "vehicle_name"
        const val ARG_VEHICLE_TYPE = "vehicle_type"
        const val ARG_VEHICLE_CONTRACTOR = "vehicle_contractor"

        private const val MAX_PHOTOS = 8
        private const val MAX_DIM = 1920
        private const val JPEG_QUALITY = 80
    }

    // Pending photos captured before save (observation not yet created)
    private val pendingPhotoPaths = mutableListOf<String>()
    private val tempObsUuid: String = UUID.randomUUID().toString()
    private var cameraTempUri: Uri? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraTempUri
            if (success && uri != null) handlePhotoUri(uri)
        }

    private val pickPhotosLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            uris?.forEach { handlePhotoUri(it) }
        }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_observation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments ?: Bundle()
        val targetType = args.getString(ARG_TARGET_TYPE) ?: ObservationService.TARGET_WORKER
        val uniqueId = args.getString(ARG_UNIQUE_ID)
        val name = args.getString(ARG_NAME) ?: ""
        val badge = args.getString(ARG_BADGE) ?: ""
        val department = args.getString(ARG_DEPARTMENT) ?: ""
        val position = args.getString(ARG_POSITION) ?: ""
        val contractor = args.getString(ARG_CONTRACTOR) ?: ""

        val observerName = args.getString(ARG_OBSERVER_NAME) ?: ""
        val observerPosition = args.getString(ARG_OBSERVER_POSITION) ?: ""
        val observerUniqueId = args.getString(ARG_OBSERVER_UNIQUE_ID)
        val observerContractor = args.getString(ARG_OBSERVER_CONTRACTOR)

        val vehicleAssetId = if (args.containsKey(ARG_VEHICLE_ASSET_ID)) args.getLong(ARG_VEHICLE_ASSET_ID) else null
        val vehicleIdentifier = args.getString(ARG_VEHICLE_IDENTIFIER)
        val vehicleName = args.getString(ARG_VEHICLE_NAME)
        val vehicleType = args.getString(ARG_VEHICLE_TYPE)
        val vehicleContractor = args.getString(ARG_VEHICLE_CONTRACTOR)

        // Target banner
        val bannerText = when (targetType) {
            ObservationService.TARGET_VEHICLE -> getString(R.string.obs_target_vehicle_banner)
            ObservationService.TARGET_SITE -> getString(R.string.obs_target_site_banner)
            ObservationService.TARGET_EQUIPMENT -> getString(R.string.obs_target_equipment_banner)
            else -> getString(R.string.obs_target_worker_banner)
        }
        view.findViewById<TextView>(R.id.txtTargetBanner).text = bannerText

        // Observer summary
        if (observerName.isNotBlank()) {
            val summary = getString(R.string.obs_observer_format, observerName, observerPosition.ifBlank { "—" })
            view.findViewById<TextView>(R.id.txtObserverSummary).apply {
                text = getString(R.string.obs_observer_label) + ": " + summary
                visibility = View.VISIBLE
            }
        }

        // Target-specific sections
        val cardWorker = view.findViewById<View>(R.id.cardWorkerInfo)
        val layoutVehicle = view.findViewById<View>(R.id.layoutVehicleInfo)
        val layoutEquipment = view.findViewById<View>(R.id.layoutEquipment)

        when (targetType) {
            ObservationService.TARGET_WORKER -> {
                cardWorker.visibility = View.VISIBLE
                layoutVehicle.visibility = View.GONE
                layoutEquipment.visibility = View.GONE
                view.findViewById<TextView>(R.id.txtObsWorkerName).text = name.ifBlank { "—" }
                view.findViewById<TextView>(R.id.txtObsBadge).text = badge.ifBlank { "—" }
                view.findViewById<TextView>(R.id.txtObsContractor).text = contractor.ifBlank { "—" }
                view.findViewById<TextView>(R.id.txtObsPosition).text = position.ifBlank { "—" }
            }
            ObservationService.TARGET_VEHICLE -> {
                cardWorker.visibility = View.GONE
                layoutVehicle.visibility = View.VISIBLE
                layoutEquipment.visibility = View.GONE
                val info = buildString {
                    append(vehicleIdentifier ?: "—")
                    if (!vehicleName.isNullOrBlank()) append(" · ").append(vehicleName)
                    if (!vehicleType.isNullOrBlank()) append(" (").append(vehicleType).append(")")
                    if (!vehicleContractor.isNullOrBlank()) append("\n").append(vehicleContractor)
                }
                view.findViewById<TextView>(R.id.txtVehicleInfo).text = info
            }
            ObservationService.TARGET_EQUIPMENT -> {
                cardWorker.visibility = View.GONE
                layoutVehicle.visibility = View.GONE
                layoutEquipment.visibility = View.VISIBLE
            }
            ObservationService.TARGET_SITE -> {
                cardWorker.visibility = View.GONE
                layoutVehicle.visibility = View.GONE
                layoutEquipment.visibility = View.GONE
            }
        }

        // Categories filtered by target_type
        val allowed = ObservationService.CATEGORIES_BY_TARGET[targetType]
            ?: ObservationService.CATEGORY_CODES
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)
        allowed.forEach { code ->
            val chip = Chip(requireContext()).apply {
                text = getCategoryLabel(code)
                isCheckable = true
                tag = code
            }
            chipGroup.addView(chip)
        }

        // Photos
        val photoContainer = view.findViewById<ViewGroup>(R.id.rvObsPhotos)
        // Using LinearLayout-like via RecyclerView? The layout uses RecyclerView but we render as simple
        // horizontal LinearLayout via adapter-less approach: we replace RecyclerView visually by
        // just using its parent. Easier: swap to LinearLayout via addView of ImageViews.
        // Since fragment_observation uses RecyclerView, we still cast to ViewGroup and addView works
        // only with LinearLayout. Safer: skip thumbnails rendering for now — just count and warn.
        view.findViewById<MaterialButton>(R.id.btnAddPhotoCamera).setOnClickListener {
            if (pendingPhotoPaths.size >= MAX_PHOTOS) {
                Toast.makeText(requireContext(), getString(R.string.obs_photo_max), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        view.findViewById<MaterialButton>(R.id.btnAddPhotoGallery).setOnClickListener {
            if (pendingPhotoPaths.size >= MAX_PHOTOS) {
                Toast.makeText(requireContext(), getString(R.string.obs_photo_max), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickPhotosLauncher.launch("image/*")
        }

        view.findViewById<MaterialButton>(R.id.btnSaveObservation).setOnClickListener {
            saveObservation(
                view, targetType,
                uniqueId, name, badge, department, position, contractor,
                observerUniqueId, observerName, observerPosition, observerContractor,
                vehicleAssetId, vehicleIdentifier, vehicleName, vehicleType, vehicleContractor
            )
        }

        view.findViewById<MaterialButton>(R.id.btnBackObservation).setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun launchCamera() {
        val dir = File(requireContext().filesDir, "observations/$tempObsUuid").apply { mkdirs() }
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
                    pendingPhotoPaths.add(destPath)
                    view?.findViewById<TextView>(R.id.txtObsPhotoCount)
                        ?.text = "${pendingPhotoPaths.size} / $MAX_PHOTOS"
                    Toast.makeText(
                        requireContext(),
                        "Foto ${pendingPhotoPaths.size}/${MAX_PHOTOS}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photo import failed: ${e.message}")
            }
        }
    }

    private fun compressAndSave(uri: Uri): String? {
        val ctx = requireContext()
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

        // Decode with inSampleSize
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val larger = maxOf(bounds.outWidth, bounds.outHeight)
        while (larger / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        // Scale to MAX_DIM
        val scaleFactor = MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scaleFactor < 1f) {
            val w = (bmp.width * scaleFactor).toInt()
            val h = (bmp.height * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp

        val dir = File(ctx.filesDir, "observations/$tempObsUuid").apply { mkdirs() }
        val dest = File(dir, "photo_${pendingPhotoPaths.size}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(dest).use { fos ->
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
        }
        return dest.absolutePath
    }

    private val categoryStringIds = mapOf(
        "PPE" to R.string.obs_cat_ppe,
        "SITUATIONAL_AWARENESS" to R.string.obs_cat_situational_awareness,
        "SAFETY_DEVICES" to R.string.obs_cat_safety_devices,
        "ISOLATION_LOCKOUT" to R.string.obs_cat_isolation_lockout,
        "SAFETY_SIGNAGE" to R.string.obs_cat_safety_signage,
        "TOOLS_EQUIPMENT" to R.string.obs_cat_tools_equipment,
        "LINE_OF_FIRE" to R.string.obs_cat_line_of_fire,
        "HEALTH_HYGIENE" to R.string.obs_cat_health_hygiene,
        "WORKPLACE_ENVIRONMENT" to R.string.obs_cat_workplace_environment,
        "LIFTING" to R.string.obs_cat_lifting,
        "MANUAL_HANDLING" to R.string.obs_cat_manual_handling,
        "HOUSEKEEPING" to R.string.obs_cat_housekeeping,
        "TOXIC_FLAMMABLE" to R.string.obs_cat_toxic_flammable,
        "WORK_PLANNING" to R.string.obs_cat_work_planning,
        "WORKING_AT_HEIGHT" to R.string.obs_cat_working_at_height,
        "CONFINED_SPACE" to R.string.obs_cat_confined_space,
        "HOT_WORK" to R.string.obs_cat_hot_work,
        "EXCAVATION" to R.string.obs_cat_excavation,
        "DRIVING_VEHICLES" to R.string.obs_cat_driving_vehicles,
        "SUPERVISION" to R.string.obs_cat_supervision,
        "PROCEDURES" to R.string.obs_cat_procedures,
        "SECURITY" to R.string.obs_cat_security,
        "IMPROVEMENT_OPPORTUNITY" to R.string.obs_cat_improvement_opportunity,
        "EMERGENCY_RESPONSE" to R.string.obs_cat_emergency_response
    )

    private fun getCategoryLabel(code: String): String {
        val resId = categoryStringIds[code] ?: return code
        return getString(resId)
    }

    private fun saveObservation(
        view: View,
        targetType: String,
        uniqueId: String?,
        name: String,
        badge: String,
        department: String,
        position: String,
        contractor: String,
        observerUniqueId: String?,
        observerName: String,
        observerPosition: String,
        observerContractor: String?,
        vehicleAssetId: Long?,
        vehicleIdentifier: String?,
        vehicleName: String?,
        vehicleType: String?,
        vehicleContractor: String?
    ) {
        val description = view.findViewById<EditText>(R.id.editDescription).text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.obs_description_required), Toast.LENGTH_SHORT).show()
            return
        }

        val obsTypeGroup = view.findViewById<RadioGroup>(R.id.rgObservationType)
        val obsType = when (obsTypeGroup.checkedRadioButtonId) {
            R.id.rbCondition -> "CONDITION"
            R.id.rbBehaviour -> "BEHAVIOUR"
            else -> { Toast.makeText(requireContext(), getString(R.string.obs_type_required), Toast.LENGTH_SHORT).show(); return }
        }

        val safetyGroup = view.findViewById<RadioGroup>(R.id.rgSafetyType)
        val safetyType = when (safetyGroup.checkedRadioButtonId) {
            R.id.rbSafe -> "SAFE"
            R.id.rbUnsafe -> "UNSAFE"
            else -> { Toast.makeText(requireContext(), getString(R.string.obs_safety_required), Toast.LENGTH_SHORT).show(); return }
        }

        val interventionGroup = view.findViewById<RadioGroup>(R.id.rgIntervention)
        val intervention = when (interventionGroup.checkedRadioButtonId) {
            R.id.rbNoIntervene -> "DID_NOT_INTERVENE"
            R.id.rbIntervened -> "INTERVENED"
            R.id.rbSpotCoaching -> "SPOT_COACHING"
            R.id.rbPositiveReinforcement -> "POSITIVE_REINFORCEMENT"
            R.id.rbPositiveObservation -> "POSITIVE_OBSERVATION"
            R.id.rbNonPeer -> "NON_PEER"
            else -> null
        }

        val outcomeGroup = view.findViewById<RadioGroup>(R.id.rgOutcome)
        val outcome = when (outcomeGroup.checkedRadioButtonId) {
            R.id.rbCorrected -> "CORRECTED"
            R.id.rbPartlyCorrected -> "PARTLY_CORRECTED"
            R.id.rbNotCorrected -> "NOT_CORRECTED"
            R.id.rbOutcomePositive -> "POSITIVE_REINFORCEMENT"
            else -> null
        }

        val coachingGroup = view.findViewById<RadioGroup>(R.id.rgCoaching)
        val coaching = when (coachingGroup.checkedRadioButtonId) {
            R.id.rbCoachingNotRequired -> "NOT_REQUIRED"
            R.id.rbCoachingPending -> "TO_BE_CONDUCTED"
            R.id.rbCoachingDone -> "CONDUCTED"
            else -> "NOT_REQUIRED"
        }

        val location = view.findViewById<EditText>(R.id.editLocation).text.toString().trim().ifBlank { null }
        val areaAuthority = view.findViewById<EditText>(R.id.editAreaAuthority).text.toString().trim().ifBlank { null }
        val actionTaken = view.findViewById<EditText>(R.id.editActionTaken).text.toString().trim().ifBlank { null }
        val additionalComments = view.findViewById<EditText>(R.id.editAdditionalComments).text.toString().trim().ifBlank { null }
        val equipmentDesc = view.findViewById<EditText>(R.id.editEquipmentDescription).text.toString().trim().ifBlank { null }

        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)
        val selectedCategories = mutableListOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) {
                selectedCategories.add(chip.tag as String)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val (_, obsUuid) = ServiceLocator.observationService.createObservation(
                uniqueIdValue = if (targetType == ObservationService.TARGET_WORKER) uniqueId else null,
                observedName = if (targetType == ObservationService.TARGET_WORKER) name.takeIf { it.isNotBlank() } else null,
                observedBadge = if (targetType == ObservationService.TARGET_WORKER) badge.ifBlank { null } else null,
                observedDepartment = if (targetType == ObservationService.TARGET_WORKER) department.ifBlank { null } else null,
                observedPosition = if (targetType == ObservationService.TARGET_WORKER) position.ifBlank { null } else null,
                observedContractor = if (targetType == ObservationService.TARGET_WORKER) contractor.ifBlank { null } else null,
                description = description,
                observationType = obsType,
                safetyType = safetyType,
                location = location,
                areaAuthority = areaAuthority,
                interventionAction = intervention,
                outcome = outcome,
                actionTaken = actionTaken,
                coachingStatus = coaching,
                additionalComments = additionalComments,
                categories = selectedCategories,
                targetType = targetType,
                observerUniqueId = observerUniqueId,
                observerName = observerName.ifBlank { null },
                observerPosition = observerPosition.ifBlank { null },
                observerContractor = observerContractor,
                vehicleAssetId = if (targetType == ObservationService.TARGET_VEHICLE) vehicleAssetId else null,
                vehicleIdentifier = if (targetType == ObservationService.TARGET_VEHICLE) vehicleIdentifier else null,
                vehicleName = if (targetType == ObservationService.TARGET_VEHICLE) vehicleName else null,
                vehicleType = if (targetType == ObservationService.TARGET_VEHICLE) vehicleType else null,
                vehicleContractor = if (targetType == ObservationService.TARGET_VEHICLE) vehicleContractor else null,
                equipmentDescription = if (targetType == ObservationService.TARGET_EQUIPMENT) equipmentDesc else null
            )

            // Persist photos linked to final obsUuid
            pendingPhotoPaths.forEachIndexed { idx, path ->
                try {
                    ServiceLocator.observationService.addPhoto(
                        observationUuid = obsUuid,
                        localPath = path,
                        fileName = File(path).name,
                        sortOrder = idx
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist photo $path: ${e.message}")
                }
            }

            Toast.makeText(requireContext(), getString(R.string.obs_saved), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }
}
