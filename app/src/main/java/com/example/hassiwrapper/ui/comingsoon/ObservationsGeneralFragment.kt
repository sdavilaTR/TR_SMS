package com.example.hassiwrapper.ui.comingsoon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import kotlinx.coroutines.launch

/**
 * Observations HSE wizard: 3-step flow (observer → target type → target scan).
 * Passport shortcut: when `preloaded_worker_uuid` is present, after step 1 jumps
 * straight to the observation form skipping steps 2 and 3.
 */
class ObservationsGeneralFragment : Fragment() {

    companion object {
        const val ARG_PRELOADED_WORKER_UUID = "preloaded_worker_uuid"
    }

    private enum class WizardStep { OBSERVER, TARGET_TYPE, TARGET_SCAN }
    private enum class TargetMode { WORKER, VEHICLE }

    private var currentStep: WizardStep = WizardStep.OBSERVER
    private var observer: PersonEntity? = null
    private var preloadedWorker: PersonEntity? = null
    private var targetMode: TargetMode = TargetMode.WORKER

    private var observerCameraActive = false
    private var observerFlashOn = false
    private var observerBarcodeView: BarcodeView? = null

    private var targetCameraActive = false
    private var targetFlashOn = false
    private var targetBarcodeView: BarcodeView? = null

    private var lastScanTime: Long = 0L
    private val scanCooldownMs = 1200L

    private val requestObserverCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) activateObserverCamera()
        else toastCameraPermission()
    }

    private val requestTargetCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) activateTargetCamera()
        else toastCameraPermission()
    }

    private val observerBarcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            result?.text?.let { handleObserverScan(it.trim()) }
        }
    }

    private val targetBarcodeCallback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            result?.text?.let { code ->
                val trimmed = code.trim()
                when (targetMode) {
                    TargetMode.WORKER -> handleWorkerScan(trimmed)
                    TargetMode.VEHICLE -> handleVehicleScan(trimmed)
                }
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (currentStep) {
                WizardStep.TARGET_SCAN -> showStep(WizardStep.TARGET_TYPE)
                WizardStep.TARGET_TYPE -> {
                    observer = null
                    showStep(WizardStep.OBSERVER)
                }
                WizardStep.OBSERVER -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_observations_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observerBarcodeView = view.findViewById(R.id.observerBarcodeView)
        targetBarcodeView = view.findViewById(R.id.targetBarcodeView)

        val preloadedUuid = arguments?.getString(ARG_PRELOADED_WORKER_UUID)
        if (!preloadedUuid.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                preloadedWorker = ServiceLocator.personDao.getByUuid(preloadedUuid)
                    ?: ServiceLocator.personDao.getByBadge(preloadedUuid)
            }
        }

        // Step 1 — observer scanner controls
        view.findViewById<MaterialButton>(R.id.btnObserverCameraToggle).setOnClickListener {
            if (observerCameraActive) deactivateObserverCamera()
            else requestObserverCameraIfNeeded()
        }
        view.findViewById<MaterialButton>(R.id.btnObserverFlash).setOnClickListener {
            observerFlashOn = !observerFlashOn
            observerBarcodeView?.setTorch(observerFlashOn)
            updateFlashIcon(view.findViewById(R.id.btnObserverFlash), observerFlashOn)
        }

        // Step 2 — target type selection
        view.findViewById<MaterialButton>(R.id.btnTargetWorker).setOnClickListener {
            targetMode = TargetMode.WORKER
            showStep(WizardStep.TARGET_SCAN)
        }
        view.findViewById<MaterialButton>(R.id.btnTargetVehicle).setOnClickListener {
            targetMode = TargetMode.VEHICLE
            showStep(WizardStep.TARGET_SCAN)
        }
        view.findViewById<MaterialButton>(R.id.btnTargetSite).setOnClickListener {
            navigateToObservation(ObservationService.TARGET_SITE, null, null)
        }
        view.findViewById<MaterialButton>(R.id.btnTargetEquipment).setOnClickListener {
            navigateToObservation(ObservationService.TARGET_EQUIPMENT, null, null)
        }

        // Step 3 — target scanner controls
        view.findViewById<MaterialButton>(R.id.btnTargetCameraToggle).setOnClickListener {
            if (targetCameraActive) deactivateTargetCamera()
            else requestTargetCameraIfNeeded()
        }
        view.findViewById<MaterialButton>(R.id.btnTargetFlash).setOnClickListener {
            targetFlashOn = !targetFlashOn
            targetBarcodeView?.setTorch(targetFlashOn)
            updateFlashIcon(view.findViewById(R.id.btnTargetFlash), targetFlashOn)
        }

        // Bottom-bar back
        view.findViewById<MaterialButton>(R.id.btnWizardBack).setOnClickListener {
            backPressedCallback.handleOnBackPressed()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        // Hardware DataWedge scans
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { code ->
                    val trimmed = code.trim()
                    when (currentStep) {
                        WizardStep.OBSERVER -> handleObserverScan(trimmed)
                        WizardStep.TARGET_SCAN -> when (targetMode) {
                            TargetMode.WORKER -> handleWorkerScan(trimmed)
                            TargetMode.VEHICLE -> handleVehicleScan(trimmed)
                        }
                        WizardStep.TARGET_TYPE -> { /* ignore */ }
                    }
                }
            }
        }

        showStep(WizardStep.OBSERVER)
    }

    // ── Step transitions ────────────────────────────────────────────────

    private fun showStep(step: WizardStep) {
        val v = view ?: return
        currentStep = step

        v.findViewById<View>(R.id.stepObserver).visibility =
            if (step == WizardStep.OBSERVER) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.stepTargetType).visibility =
            if (step == WizardStep.TARGET_TYPE) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.stepTargetScan).visibility =
            if (step == WizardStep.TARGET_SCAN) View.VISIBLE else View.GONE

        // Progress bar coloring
        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.divider)
        val activeText = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.on_surface_variant)

        v.findViewById<View>(R.id.stepBar1).setBackgroundColor(activeColor)
        v.findViewById<View>(R.id.stepBar2).setBackgroundColor(
            if (step == WizardStep.TARGET_TYPE || step == WizardStep.TARGET_SCAN) activeColor else inactiveColor
        )
        v.findViewById<View>(R.id.stepBar3).setBackgroundColor(
            if (step == WizardStep.TARGET_SCAN) activeColor else inactiveColor
        )

        v.findViewById<TextView>(R.id.stepLabel1).setTextColor(activeText)
        v.findViewById<TextView>(R.id.stepLabel2).setTextColor(
            if (step == WizardStep.TARGET_TYPE || step == WizardStep.TARGET_SCAN) activeText else inactiveText
        )
        v.findViewById<TextView>(R.id.stepLabel3).setTextColor(
            if (step == WizardStep.TARGET_SCAN) activeText else inactiveText
        )

        // Bottom bar visible from step 2
        v.findViewById<View>(R.id.bottomBar).visibility =
            if (step == WizardStep.OBSERVER) View.GONE else View.VISIBLE

        // Title / subtitle
        val title = v.findViewById<TextView>(R.id.txtStepTitle)
        val subtitle = v.findViewById<TextView>(R.id.txtStepSubtitle)
        when (step) {
            WizardStep.OBSERVER -> {
                title.setText(R.string.obs_hub_title)
                subtitle.setText(R.string.obs_scan_observer_prompt)
            }
            WizardStep.TARGET_TYPE -> {
                title.setText(R.string.obs_hub_title)
                subtitle.setText(R.string.obs_select_target)
                observer?.let { o ->
                    val name = "${o.given_name} ${o.family_name}".trim()
                    val summary = getString(R.string.obs_observer_format, name, o.position.ifBlank { "—" })
                    v.findViewById<TextView>(R.id.txtObserverInfo).text = summary
                }
            }
            WizardStep.TARGET_SCAN -> {
                title.setText(R.string.obs_hub_title)
                val promptRes = if (targetMode == TargetMode.WORKER)
                    R.string.obs_scan_worker_title else R.string.obs_scan_vehicle_title
                subtitle.setText(promptRes)
                v.findViewById<TextView>(R.id.txtTargetStatus).setText(promptRes)
            }
        }

        // Pause any camera not in use
        if (step != WizardStep.OBSERVER && observerCameraActive) deactivateObserverCamera()
        if (step != WizardStep.TARGET_SCAN && targetCameraActive) deactivateTargetCamera()
    }

    // ── Observer step ───────────────────────────────────────────────────

    private fun handleObserverScan(code: String) {
        if (currentStep != WizardStep.OBSERVER) return
        if (!debounce()) return
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
            if (observerCameraActive) deactivateObserverCamera()

            preloadedWorker?.let { worker ->
                navigateToObservation(ObservationService.TARGET_WORKER, worker, null)
                return@launch
            }
            showStep(WizardStep.TARGET_TYPE)
        }
    }

    private fun requestObserverCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) activateObserverCamera()
        else requestObserverCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun activateObserverCamera() {
        val v = view ?: return
        observerCameraActive = true
        v.findViewById<View>(R.id.observerLaserArea).visibility = View.GONE
        v.findViewById<View>(R.id.observerCameraArea).visibility = View.VISIBLE
        val btn = v.findViewById<MaterialButton>(R.id.btnObserverCameraToggle)
        btn.setText(R.string.scanner_btn_laser)
        btn.setIconResource(R.drawable.ic_scanner)
        v.findViewById<MaterialButton>(R.id.btnObserverFlash).visibility = View.VISIBLE
        observerBarcodeView?.decodeContinuous(observerBarcodeCallback)
        observerBarcodeView?.resume()
    }

    private fun deactivateObserverCamera() {
        val v = view ?: return
        observerCameraActive = false
        observerFlashOn = false
        observerBarcodeView?.setTorch(false)
        observerBarcodeView?.pause()
        v.findViewById<View>(R.id.observerLaserArea).visibility = View.VISIBLE
        v.findViewById<View>(R.id.observerCameraArea).visibility = View.GONE
        val btn = v.findViewById<MaterialButton>(R.id.btnObserverCameraToggle)
        btn.setText(R.string.scanner_btn_camera)
        btn.setIconResource(R.drawable.ic_camera)
        val btnFlash = v.findViewById<MaterialButton>(R.id.btnObserverFlash)
        btnFlash.visibility = View.GONE
        updateFlashIcon(btnFlash, false)
    }

    // ── Target scan step ────────────────────────────────────────────────

    private fun handleWorkerScan(code: String) {
        if (!debounce()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val person = ServiceLocator.personDao.getByBadge(code)
                ?: ServiceLocator.personDao.getByUuid(code)
            if (person == null) {
                Toast.makeText(requireContext(), getString(R.string.obs_observer_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (targetCameraActive) deactivateTargetCamera()
            navigateToObservation(ObservationService.TARGET_WORKER, person, null)
        }
    }

    private fun handleVehicleScan(code: String) {
        if (!debounce()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val vDao = ServiceLocator.vehicleDao
            val vehicle = vDao.getByUuid(code)
                ?: vDao.search(code).firstOrNull { it.identifier == code || it.license_plate == code }
                ?: vDao.search(code).firstOrNull()
            if (vehicle == null) {
                Toast.makeText(requireContext(), getString(R.string.obs_observer_not_found), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (targetCameraActive) deactivateTargetCamera()
            navigateToObservation(ObservationService.TARGET_VEHICLE, null, vehicle)
        }
    }

    private fun requestTargetCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) activateTargetCamera()
        else requestTargetCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun activateTargetCamera() {
        val v = view ?: return
        targetCameraActive = true
        v.findViewById<View>(R.id.targetLaserArea).visibility = View.GONE
        v.findViewById<View>(R.id.targetCameraArea).visibility = View.VISIBLE
        val btn = v.findViewById<MaterialButton>(R.id.btnTargetCameraToggle)
        btn.setText(R.string.scanner_btn_laser)
        btn.setIconResource(R.drawable.ic_scanner)
        v.findViewById<MaterialButton>(R.id.btnTargetFlash).visibility = View.VISIBLE
        targetBarcodeView?.decodeContinuous(targetBarcodeCallback)
        targetBarcodeView?.resume()
    }

    private fun deactivateTargetCamera() {
        val v = view ?: return
        targetCameraActive = false
        targetFlashOn = false
        targetBarcodeView?.setTorch(false)
        targetBarcodeView?.pause()
        v.findViewById<View>(R.id.targetLaserArea).visibility = View.VISIBLE
        v.findViewById<View>(R.id.targetCameraArea).visibility = View.GONE
        val btn = v.findViewById<MaterialButton>(R.id.btnTargetCameraToggle)
        btn.setText(R.string.scanner_btn_camera)
        btn.setIconResource(R.drawable.ic_camera)
        val btnFlash = v.findViewById<MaterialButton>(R.id.btnTargetFlash)
        btnFlash.visibility = View.GONE
        updateFlashIcon(btnFlash, false)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun updateFlashIcon(btn: MaterialButton, on: Boolean) {
        btn.setIconResource(if (on) R.drawable.ic_flashlight_off else R.drawable.ic_flashlight_on)
    }

    private fun debounce(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScanTime < scanCooldownMs) return false
        lastScanTime = now
        return true
    }

    private fun toastCameraPermission() {
        Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (currentStep == WizardStep.OBSERVER && observerCameraActive) observerBarcodeView?.resume()
        if (currentStep == WizardStep.TARGET_SCAN && targetCameraActive) targetBarcodeView?.resume()
    }

    override fun onPause() {
        super.onPause()
        if (observerCameraActive) observerBarcodeView?.pause()
        if (targetCameraActive) targetBarcodeView?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        observerBarcodeView?.pause()
        targetBarcodeView?.pause()
        observerBarcodeView = null
        targetBarcodeView = null
    }

    private fun navigateToObservation(targetType: String, worker: PersonEntity?, vehicle: VehicleEntity?) {
        val obs = observer ?: return
        val args = Bundle().apply {
            putString("target_type", targetType)
            putString("observer_unique_id", obs.unique_id_value)
            putString("observer_name", "${obs.given_name} ${obs.family_name}".trim())
            putString("observer_position", obs.position)
            putString("observer_contractor", null)
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
