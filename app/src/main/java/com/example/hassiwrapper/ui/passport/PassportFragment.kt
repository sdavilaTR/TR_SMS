package com.example.hassiwrapper.ui.passport

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.ContractorEntity
import com.example.hassiwrapper.data.db.entities.PersonEntity
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PassportFragment : Fragment() {

    private var soundPool: SoundPool? = null
    private var soundAllowed: Int = 0
    private val loadedSounds = mutableSetOf<Int>()

    private val cameraScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { lookupWorker(it) }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCameraScanner()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_passport, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()
        soundPool!!.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSounds.add(sampleId)
        }
        soundAllowed = soundPool!!.load(requireContext(), R.raw.beep_allowed, 1)

        view.findViewById<MaterialButton>(R.id.btnCameraScanWaiting).setOnClickListener {
            requestCameraIfNeededAndScan()
        }
        view.findViewById<MaterialButton>(R.id.btnCameraScanLoaded).setOnClickListener {
            requestCameraIfNeededAndScan()
        }
        view.findViewById<MaterialButton>(R.id.btnClosePassport).setOnClickListener {
            showWaiting()
        }

        // Listen to DataWedge laser scanner (same as ScannerFragment)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { barcode ->
                    lookupWorker(barcode.trim())
                }
            }
        }
    }

    private fun playBeep() {
        if (soundAllowed != 0 && loadedSounds.contains(soundAllowed)) {
            soundPool?.play(soundAllowed, 1f, 1f, 1, 0, 1f)
        }
    }

    private fun requestCameraIfNeededAndScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraScanner()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt(getString(R.string.scanner_camera_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setCameraId(0)
        }
        cameraScanner.launch(options)
    }

    private fun lookupWorker(identifier: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val person = ServiceLocator.personDao.getByUuid(identifier)
                ?: ServiceLocator.personDao.getByBadge(identifier)

            if (person == null) {
                Toast.makeText(requireContext(), getString(R.string.passport_worker_not_found), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val contractor = person.contractor_id?.let { ServiceLocator.contractorDao.getById(it) }
            playBeep()
            showPassport(person, contractor)
        }
    }

    private fun showPassport(person: PersonEntity, contractor: ContractorEntity?) {
        val view = this.view ?: return

        view.findViewById<TextView>(R.id.txtFamilyName).text =
            person.family_name.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtGivenName).text =
            person.given_name.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtPosition).text =
            person.position.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtCompany).text =
            contractor?.contractor_name?.ifBlank { "—" } ?: "—"

        view.findViewById<TextView>(R.id.txtDocument).text =
            person.unique_id_value.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtBadgeNumber).text =
            person.badge_number.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtCategory).text =
            person.category_code.ifBlank { "—" }

        view.findViewById<TextView>(R.id.txtValidFrom).text =
            person.valid_from?.take(10)?.replace('-', '/') ?: "—"

        view.findViewById<TextView>(R.id.txtValidTo).text =
            person.valid_to?.take(10)?.replace('-', '/') ?: "—"

        val time = SimpleDateFormat("HH:mm:ss  dd/MM/yyyy", Locale.getDefault()).format(Date())
        view.findViewById<TextView>(R.id.txtScanTime).text =
            getString(R.string.passport_scanned_at, time)

        val statusView = view.findViewById<TextView>(R.id.txtActiveStatus)
        if (person.is_active) {
            statusView.text = getString(R.string.passport_status_active)
            statusView.setBackgroundColor(resources.getColor(R.color.granted_bg, null))
            statusView.setTextColor(resources.getColor(R.color.granted, null))
        } else {
            statusView.text = getString(R.string.passport_status_inactive)
            statusView.setBackgroundColor(resources.getColor(R.color.denied_bg, null))
            statusView.setTextColor(resources.getColor(R.color.denied, null))
        }

        view.findViewById<View>(R.id.layoutWaiting).visibility = View.GONE
        val loaded = view.findViewById<ScrollView>(R.id.layoutLoaded)
        loaded.visibility = View.VISIBLE
        loaded.scrollTo(0, 0)
    }

    private fun showWaiting() {
        val view = this.view ?: return
        view.findViewById<View>(R.id.layoutLoaded).visibility = View.GONE
        view.findViewById<View>(R.id.layoutWaiting).visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        soundPool?.release()
        soundPool = null
        loadedSounds.clear()
    }
}
