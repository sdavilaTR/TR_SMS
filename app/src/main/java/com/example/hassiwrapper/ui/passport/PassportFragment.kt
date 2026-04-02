package com.example.hassiwrapper.ui.passport

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PassportFragment : Fragment() {

    companion object {
        private const val TAG = "PassportFragment"
    }

    private var soundPool: SoundPool? = null
    private var soundAllowed: Int = 0
    private val loadedSounds = mutableSetOf<Int>()

    private var currentPerson: PersonEntity? = null

    private val cameraScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { lookupWorker(it) }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCameraScanner()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    private val requestPhotoCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchPhotoCameraCapture()
        else Toast.makeText(requireContext(), getString(R.string.scanner_error_camera_permission), Toast.LENGTH_SHORT).show()
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) onPhotoCaptured(bitmap)
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
        view.findViewById<FrameLayout>(R.id.photoContainer).setOnClickListener {
            if (currentPerson != null) requestPhotoCameraIfNeededAndCapture()
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
        currentPerson = person

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

        // Load existing photo or show placeholder
        loadWorkerPhoto(person.photo_url)

        view.findViewById<View>(R.id.layoutWaiting).visibility = View.GONE
        val loaded = view.findViewById<ScrollView>(R.id.layoutLoaded)
        loaded.visibility = View.VISIBLE
        loaded.scrollTo(0, 0)
    }

    // ── Photo: local cache helpers ──────────────────────────────────────────

    private fun getPhotoCacheDir(): File {
        val dir = File(requireContext().filesDir, "worker-photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getCachedPhotoFile(personUuid: String): File {
        return File(getPhotoCacheDir(), "$personUuid.jpg")
    }

    // ── Photo: load (offline-first) ─────────────────────────────────────────

    private fun loadWorkerPhoto(photoUrl: String?) {
        val view = this.view ?: return
        val person = currentPerson ?: return
        val imgView = view.findViewById<ImageView>(R.id.imgWorkerPhoto)
        val placeholder = view.findViewById<View>(R.id.layoutPhotoPlaceholder)

        val cachedFile = getCachedPhotoFile(person.unique_id_value)

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Try local cache first (instant)
            val cachedBitmap = withContext(Dispatchers.IO) {
                if (cachedFile.exists()) BitmapFactory.decodeFile(cachedFile.absolutePath) else null
            }
            if (cachedBitmap != null && isAdded) {
                imgView.setImageBitmap(cachedBitmap)
                imgView.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            }

            // 2. If there's a remote URL, try to download with 5s timeout (background refresh)
            if (!photoUrl.isNullOrBlank()) {
                val remoteBitmap = withTimeoutOrNull(5_000L) {
                    withContext(Dispatchers.IO) {
                        try {
                            val client = OkHttpClient.Builder()
                                .connectTimeout(3, TimeUnit.SECONDS)
                                .readTimeout(5, TimeUnit.SECONDS)
                                .build()
                            val request = Request.Builder().url(photoUrl).build()
                            val response = client.newCall(request).execute()
                            response.body?.byteStream()?.let { stream ->
                                val bmp = BitmapFactory.decodeStream(stream)
                                // Save to cache
                                if (bmp != null) {
                                    cachedFile.outputStream().use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                    }
                                }
                                bmp
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Remote photo download failed: ${e.message}")
                            null
                        }
                    }
                }
                if (remoteBitmap != null && isAdded) {
                    imgView.setImageBitmap(remoteBitmap)
                    imgView.visibility = View.VISIBLE
                    placeholder.visibility = View.GONE
                    return@launch
                }
            }

            // 3. If no cached and no remote, show placeholder
            if (cachedBitmap == null && isAdded) {
                imgView.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
            }
        }
    }

    // ── Photo: capture ──────────────────────────────────────────────────────

    private fun requestPhotoCameraIfNeededAndCapture() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchPhotoCameraCapture()
        } else {
            requestPhotoCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchPhotoCameraCapture() {
        takePhotoLauncher.launch(null)
    }

    private fun onPhotoCaptured(bitmap: Bitmap) {
        val view = this.view ?: return
        val person = currentPerson ?: return

        // Show preview immediately
        val imgView = view.findViewById<ImageView>(R.id.imgWorkerPhoto)
        val placeholder = view.findViewById<View>(R.id.layoutPhotoPlaceholder)
        imgView.setImageBitmap(bitmap)
        imgView.visibility = View.VISIBLE
        placeholder.visibility = View.GONE

        val projectId = person.project_id
        if (projectId == null) {
            Toast.makeText(requireContext(), getString(R.string.passport_photo_error, "No project ID"), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Always save to local cache first
            val cachedFile = savePhotoToCache(person.unique_id_value, bitmap)

            // Try upload with 5s timeout
            val uploaded = tryUploadPhoto(projectId, person.unique_id_value, bitmap)

            if (uploaded) {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.passport_photo_uploaded), Toast.LENGTH_SHORT).show()
                }
            } else {
                // Queue for later upload during sync
                enqueuePendingPhoto(person.unique_id_value, projectId, cachedFile.absolutePath)
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.passport_photo_no_connection), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun savePhotoToCache(personUuid: String, bitmap: Bitmap): File {
        return withContext(Dispatchers.IO) {
            val file = getCachedPhotoFile(personUuid)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file
        }
    }

    /**
     * Attempts to upload the photo to the API. Returns true if successful,
     * false if network is unavailable or the request fails/times out.
     */
    private suspend fun tryUploadPhoto(projectId: Int, personUuid: String, bitmap: Bitmap): Boolean {
        return try {
            val result = withTimeoutOrNull(5_000L) {
                withContext(Dispatchers.IO) {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    val bytes = baos.toByteArray()

                    val requestBody = bytes.toRequestBody("image/jpeg".toMediaType())
                    val filePart = MultipartBody.Part.createFormData("file", "photo.jpg", requestBody)

                    val api = ServiceLocator.apiClient.getService()
                    val response = api.uploadWorkerPhoto(projectId, personUuid, filePart)

                    if (response.isSuccessful) {
                        val photoUrl = response.body()?.photoUrl
                        if (photoUrl != null) {
                            ServiceLocator.personDao.updatePhotoUrl(personUuid, photoUrl)
                            currentPerson = currentPerson?.copy(photo_url = photoUrl)
                        }
                        true
                    } else {
                        Log.w(TAG, "Photo upload failed: HTTP ${response.code()}")
                        false
                    }
                }
            }
            result == true
        } catch (e: Exception) {
            Log.w(TAG, "Photo upload exception: ${e.message}")
            false
        }
    }

    private suspend fun enqueuePendingPhoto(personUuid: String, projectId: Int, localPath: String) {
        ServiceLocator.pendingPhotoDao.insert(
            com.example.hassiwrapper.data.db.entities.PendingPhotoEntity(
                unique_id_value = personUuid,
                project_id = projectId,
                local_path = localPath,
                created_at = Instant.now().toString()
            )
        )
        Log.i(TAG, "Photo queued for offline sync: $personUuid")
    }

    private fun showWaiting() {
        val view = this.view ?: return
        currentPerson = null
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
