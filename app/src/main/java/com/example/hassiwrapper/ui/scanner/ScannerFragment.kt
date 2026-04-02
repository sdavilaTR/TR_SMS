package com.example.hassiwrapper.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.ImageViewCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.services.ClockingService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class ScannerFragment : Fragment() {

    private var isProcessing = false
    private var projectId: Int = 1
    private var accessPointId: Int = 1
    private var lastScanId: String? = null
    private var lastScanTime: Long = 0
    private val SCAN_COOLDOWN_MS = 1500L

    private var soundPool: SoundPool? = null
    private var soundAllowed: Int = 0
    private var soundDenied: Int = 0
    private val loadedSounds = mutableSetOf<Int>()

    private val scanLog = mutableListOf<ScanLogEntry>()

    data class ScanLogEntry(val name: String, val badge: String, val granted: Boolean, val time: String)

    // ZXing camera scanner launcher (fallback when DataWedge laser is not available)
    private val cameraScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onBarcodeScanned(it) }
    }

    // Runtime camera permission request
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCameraScanner()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()
        soundPool!!.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSounds.add(sampleId)
        }
        soundAllowed = soundPool!!.load(requireContext(), R.raw.beep_allowed, 1)
        soundDenied = soundPool!!.load(requireContext(), R.raw.beep_denied, 1)

        // Scan log RecyclerView
        val rv = view.findViewById<RecyclerView>(R.id.rvScanLog)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = ScanLogAdapter()

        // Camera scan button (fallback for devices without laser scanner)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCameraScan)
            .setOnClickListener {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCameraScanner()
                } else {
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

        // Load project context
        viewLifecycleOwner.lifecycleScope.launch {
            val configProjectId = ServiceLocator.configRepo.getInt("current_project_id")
            if (configProjectId != null) projectId = configProjectId
            val configApId = ServiceLocator.configRepo.getInt("access_point_id")
            if (configApId != null) accessPointId = configApId
        }

        // Listen for DataWedge laser scans (primary method — Honeywell EDA52)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val activity = requireActivity() as? MainActivity ?: return@repeatOnLifecycle
                activity.dataWedgeManager.scanFlow.collect { barcode ->
                    onBarcodeScanned(barcode)
                }
            }
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

    private fun onBarcodeScanned(raw: String) {
        if (isProcessing) return

        val identifier = raw.trim()

        // Debounce same scan
        val now = System.currentTimeMillis()
        if (identifier == lastScanId && (now - lastScanTime) < SCAN_COOLDOWN_MS) return
        lastScanId = identifier
        lastScanTime = now

        isProcessing = true
        view?.findViewById<TextView>(R.id.txtScannerStatus)?.text = getString(R.string.scanner_status_processing)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = ServiceLocator.clockingService.processScan(
                    identifier, accessPointId, projectId, "LASER"
                )
                addToScanLog(result)
                showResultOverlay(result)
            } catch (e: Exception) {
                val err = ClockingService.ScanResult(
                    success = false,
                    reason = e.message,
                    failure_reason = e.message
                )
                addToScanLog(err)
                showResultOverlay(err)
            }
        }
    }

    private fun addToScanLog(result: ClockingService.ScanResult) {
        val p = result.person
        val name = if (p != null) "${p.given_name} ${p.family_name}".trim() else getString(R.string.scanner_unknown)
        val badge = p?.badge_number ?: ""
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        scanLog.add(0, ScanLogEntry(name, badge, result.success, timeStr))
        if (scanLog.size > 8) scanLog.removeAt(scanLog.size - 1)
        view?.findViewById<RecyclerView>(R.id.rvScanLog)?.adapter?.notifyDataSetChanged()
    }

    private fun showResultOverlay(result: ClockingService.ScanResult) {
        val view = this.view ?: return
        val overlay = view.findViewById<FrameLayout>(R.id.scanResultOverlay)
        val icon = view.findViewById<ImageView>(R.id.resultIcon)
        val title = view.findViewById<TextView>(R.id.resultTitle)
        val name = view.findViewById<TextView>(R.id.resultWorkerName)
        val badge = view.findViewById<TextView>(R.id.resultBadge)
        val reason = view.findViewById<TextView>(R.id.resultReason)

        overlay.visibility = View.VISIBLE

        if (result.success) {
            playBeep(true)
            icon.setImageResource(R.drawable.ic_baseline_check_circle_24)
            icon.setBackgroundColor(resources.getColor(R.color.granted_bg, null))
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(resources.getColor(R.color.granted, null)))
            title.text = getString(R.string.scanner_granted)
            title.setTextColor(resources.getColor(R.color.granted, null))
            val p = result.person
            name.text = if (p != null) "${p.given_name} ${p.family_name}" else getString(R.string.scanner_worker)
            badge.text = p?.badge_number ?: ""
            reason.text = ""
        } else {
            playBeep(false)
            icon.setImageResource(R.drawable.ic_cancel_circle_24)
            icon.setBackgroundColor(resources.getColor(R.color.denied_bg, null))
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(resources.getColor(R.color.denied, null)))
            title.text = if (result.result == "ERROR") getString(R.string.scanner_result_error) else getString(R.string.scanner_denied)
            title.setTextColor(resources.getColor(R.color.denied, null))
            val p = result.person
            name.text = if (p != null) "${p.given_name} ${p.family_name}" else getString(R.string.scanner_unknown)
            badge.text = p?.badge_number ?: ""
            reason.text = result.reason ?: result.failure_reason ?: ""
            reason.setTextColor(resources.getColor(R.color.denied, null))
        }

        // Allow next scan immediately
        isProcessing = false
        this.view?.findViewById<TextView>(R.id.txtScannerStatus)?.text = getString(R.string.scanner_status_waiting)

        // Auto-dismiss overlay after 3 seconds
        overlay.postDelayed({
            overlay.visibility = View.GONE
        }, 3000)
    }

    private fun playBeep(allowed: Boolean) {
        val soundId = if (allowed) soundAllowed else soundDenied
        if (soundId != 0 && loadedSounds.contains(soundId)) {
            soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        soundPool?.release()
        soundPool = null
        loadedSounds.clear()
    }

    inner class ScanLogAdapter : RecyclerView.Adapter<ScanLogAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val stripe: View = view.findViewById(R.id.stripe)
            val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
            val txtId: TextView = view.findViewById(android.R.id.text1)
            val txtTime: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scan_log, parent, false)
            view.setBackgroundColor(resources.getColor(R.color.surface, null))
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = scanLog[position]
            val badge = if (entry.badge.isNotEmpty()) " · ${entry.badge}" else ""
            holder.txtId.text = "${entry.name}$badge"
            val resultColor = resources.getColor(
                if (entry.granted) R.color.granted else R.color.denied, null
            )
            holder.txtId.setTextColor(resultColor)
            holder.stripe.setBackgroundColor(resultColor)
            holder.ivStatus.setImageResource(
                if (entry.granted) R.drawable.ic_baseline_check_circle_24 else R.drawable.ic_cancel_circle_24
            )
            ImageViewCompat.setImageTintList(holder.ivStatus, ColorStateList.valueOf(resultColor))
            holder.txtTime.text = entry.time
            holder.txtTime.setTextColor(resources.getColor(R.color.on_surface_variant, null))
        }

        override fun getItemCount() = scanLog.size
    }
}
