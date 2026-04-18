package com.example.hassiwrapper.ui.passport

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.hassiwrapper.data.db.entities.ContractorEntity
import com.example.hassiwrapper.data.db.entities.PersonEntity
import com.google.android.material.button.MaterialButton
import android.app.Activity
import android.content.Intent
import android.widget.ProgressBar
import com.example.hassiwrapper.network.dto.DocumentComplianceDto
import com.example.hassiwrapper.network.dto.TrainingComplianceDto
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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

    private val cameraScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)?.let { lookupWorker(it) }
        }
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

    /** URI where the camera will write the full-resolution photo. */
    private var pendingPhotoUri: Uri? = null
    private var pendingPhotoFile: File? = null

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = pendingPhotoFile
            if (file != null && file.exists()) {
                onPhotoCapturedFile(file)
            } else {
                Log.w(TAG, "Camera returned success but photo file missing")
            }
        }
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

        view.findViewById<MaterialButton>(R.id.btnLaserScanWaiting).setOnClickListener {
            // Laser scan is handled by DataWedge hardware — this button is a visual hint
            Toast.makeText(requireContext(), getString(R.string.scanner_laser_hint), Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btnCameraScanWaiting).setOnClickListener {
            requestCameraIfNeededAndScan()
        }
        view.findViewById<MaterialButton>(R.id.btnLaserScanLoaded).setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.scanner_laser_hint), Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btnCameraScanLoaded).setOnClickListener {
            requestCameraIfNeededAndScan()
        }
        view.findViewById<MaterialButton>(R.id.btnClosePassport).setOnClickListener {
            showWaiting()
        }
        view.findViewById<MaterialButton>(R.id.btnOpenObservation).setOnClickListener {
            openObservation()
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
        val intent = Intent(requireContext(), CustomScannerActivity::class.java)
        cameraScanner.launch(intent)
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
            person.badge_number.ifBlank { "—" }

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
        val loaded = view.findViewById<LinearLayout>(R.id.layoutLoaded)
        loaded.visibility = View.VISIBLE

        // Load compliance data from API (background, non-blocking)
        loadComplianceData(person)
    }

    private fun loadComplianceData(person: PersonEntity) {
        val view = this.view ?: return

        // Reset sections
        view.findViewById<LinearLayout>(R.id.layoutTrainingItems).removeAllViews()
        view.findViewById<LinearLayout>(R.id.layoutDocItems).removeAllViews()
        view.findViewById<TextView>(R.id.txtNoTrainings).visibility = View.GONE
        view.findViewById<TextView>(R.id.txtNoDocs).visibility = View.GONE
        view.findViewById<ProgressBar>(R.id.progressTrainings).progress = 0
        view.findViewById<ProgressBar>(R.id.progressDocs).progress = 0
        view.findViewById<TextView>(R.id.txtTrainingsCount).text = ""
        view.findViewById<TextView>(R.id.txtDocsCount).text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val trainings = withContext(Dispatchers.IO) {
                ServiceLocator.trainingComplianceDao.getByPerson(person.unique_id_value)
            }
            if (isAdded) renderTrainings(trainings.map { t ->
                TrainingComplianceDto(
                    trainingDefinitionId = t.training_definition_id,
                    trainingCode = t.training_code,
                    trainingName = t.training_name,
                    isMandatory = t.is_mandatory,
                    status = t.status,
                    completedDate = t.completed_date,
                    expiryDate = t.expiry_date
                )
            })

            val docEntities = withContext(Dispatchers.IO) {
                ServiceLocator.documentComplianceDao.getByPerson(person.unique_id_value)
            }
            if (isAdded) renderDocuments(docEntities.map { d ->
                DocumentComplianceDto(
                    documentTypeId = d.document_type_id,
                    typeCode = d.type_code,
                    typeName = d.type_name,
                    isMandatory = d.is_mandatory,
                    status = d.status
                )
            }, person, docEntities)
        }
    }

    private fun renderTrainings(trainings: List<TrainingComplianceDto>) {
        val view = this.view ?: return
        val container = view.findViewById<LinearLayout>(R.id.layoutTrainingItems)
        val countView = view.findViewById<TextView>(R.id.txtTrainingsCount)
        val progressView = view.findViewById<ProgressBar>(R.id.progressTrainings)
        val emptyView = view.findViewById<TextView>(R.id.txtNoTrainings)

        container.removeAllViews()

        if (trainings.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }

        val mandatory = trainings.filter { it.isMandatory }
        val completed = mandatory.count { it.status.equals("COMPLETED", true) }
        val total = mandatory.size
        val pct = if (total > 0) (completed * 100) / total else 0

        countView.text = "$completed ${getString(R.string.passport_of)} $total"
        progressView.progress = pct

        // Show mandatory first, then others
        val sorted = trainings.sortedWith(compareByDescending<TrainingComplianceDto> { it.isMandatory }
            .thenBy { statusOrder(it.status) })

        for (t in sorted) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val text1 = row.findViewById<TextView>(android.R.id.text1)
            val text2 = row.findViewById<TextView>(android.R.id.text2)

            val mandatoryTag = if (t.isMandatory) " ★" else ""
            text1.text = "${t.trainingName}$mandatoryTag"
            text1.textSize = 12f
            text1.setTextColor(resources.getColor(R.color.on_surface, null))

            val statusText = trainingStatusText(t.status)
            val dateInfo = if (!t.expiryDate.isNullOrBlank()) " → ${t.expiryDate.take(10)}" else ""
            text2.text = "$statusText$dateInfo"
            text2.textSize = 10f
            text2.setTextColor(statusColor(t.status))

            container.addView(row)
        }
    }

    private fun renderDocuments(
        docs: List<DocumentComplianceDto>,
        person: PersonEntity? = null,
        entities: List<com.example.hassiwrapper.data.db.entities.DocumentComplianceEntity> = emptyList()
    ) {
        val view = this.view ?: return
        val container = view.findViewById<LinearLayout>(R.id.layoutDocItems)
        val countView = view.findViewById<TextView>(R.id.txtDocsCount)
        val progressView = view.findViewById<ProgressBar>(R.id.progressDocs)
        val emptyView = view.findViewById<TextView>(R.id.txtNoDocs)

        container.removeAllViews()

        if (docs.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }

        val mandatory = docs.filter { it.isMandatory }
        val valid = mandatory.count {
            it.status.equals("VALIDATED", true) || it.status.equals("VALID", true) || it.status.equals("OK", true)
        }
        val total = mandatory.size
        val pct = if (total > 0) (valid * 100) / total else 0

        countView.text = "$valid ${getString(R.string.passport_of)} $total"
        progressView.progress = pct

        val docEntities = entities.associateBy { it.document_type_id }

        val sorted = docs.sortedWith(compareByDescending<DocumentComplianceDto> { it.isMandatory }
            .thenBy { docStatusOrder(it.status) })

        for (d in sorted) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val text1 = row.findViewById<TextView>(android.R.id.text1)
            val text2 = row.findViewById<TextView>(android.R.id.text2)

            val mandatoryTag = if (d.isMandatory) " ★" else ""
            text1.text = "${d.typeName}$mandatoryTag"
            text1.textSize = 12f
            text1.setTextColor(resources.getColor(R.color.on_surface, null))

            text2.text = docStatusText(d.status)
            text2.textSize = 10f
            text2.setTextColor(docStatusColor(d.status))

            val docEntity = docEntities[d.documentTypeId]
            if (docEntity?.person_document_id != null && person != null) {
                row.setOnClickListener {
                    downloadAndOpenDocument(person, docEntity.person_document_id)
                }
            }

            container.addView(row)
        }
    }

    private fun statusOrder(status: String): Int = when (status.uppercase()) {
        "MISSING" -> 0; "EXPIRED" -> 1; "PENDING" -> 2; "COMPLETED" -> 3; else -> 4
    }

    private fun docStatusOrder(status: String): Int = when (status.uppercase()) {
        "MISSING" -> 0; "REJECTED" -> 1; "PENDING_REVIEW", "DRAFT" -> 2
        "VALIDATED", "VALID", "OK" -> 3; else -> 4
    }

    private fun trainingStatusText(status: String): String = when (status.uppercase()) {
        "COMPLETED" -> getString(R.string.passport_training_completed)
        "PENDING"   -> getString(R.string.passport_training_pending)
        "EXPIRED"   -> getString(R.string.passport_training_expired)
        "MISSING"   -> getString(R.string.passport_training_missing)
        else        -> status
    }

    private fun docStatusText(status: String): String = when (status.uppercase()) {
        "VALIDATED", "VALID", "OK" -> getString(R.string.passport_doc_valid)
        "PENDING_REVIEW", "DRAFT" -> getString(R.string.passport_doc_expiring)
        "MISSING"                 -> getString(R.string.passport_doc_missing)
        "EXPIRED"                 -> getString(R.string.passport_doc_expired)
        else                      -> status
    }

    private fun statusColor(status: String): Int {
        val colorRes = when (status.uppercase()) {
            "COMPLETED" -> R.color.granted
            "PENDING"   -> R.color.warning
            "EXPIRED"   -> R.color.denied
            "MISSING"   -> R.color.on_surface_variant
            else        -> R.color.on_surface_variant
        }
        return resources.getColor(colorRes, null)
    }

    private fun docStatusColor(status: String): Int {
        val colorRes = when (status.uppercase()) {
            "VALIDATED", "VALID", "OK" -> R.color.granted
            "PENDING_REVIEW", "DRAFT" -> R.color.warning
            "MISSING"                 -> R.color.on_surface_variant
            "EXPIRED", "REJECTED"     -> R.color.denied
            else                      -> R.color.on_surface_variant
        }
        return resources.getColor(colorRes, null)
    }

    // ── Document download on click ────────────────────────────────────────

    private fun downloadAndOpenDocument(person: PersonEntity, documentId: Long) {
        val projectId = person.project_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withTimeoutOrNull(10_000L) {
                    withContext(Dispatchers.IO) {
                        val api = ServiceLocator.apiClient.getService()
                        val resp = api.downloadDocument(projectId, person.unique_id_value, documentId)
                        if (resp.isSuccessful) resp else null
                    }
                }
                if (result == null) {
                    if (isAdded) Toast.makeText(requireContext(), getString(R.string.passport_doc_no_connection), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val body = result.body() ?: return@launch
                val contentDisposition = result.headers()["Content-Disposition"] ?: ""
                val fileName = Regex("filename=\"?(.+?)\"?$").find(contentDisposition)?.groupValues?.get(1)
                    ?: "document_$documentId"
                val contentType = result.headers()["Content-Type"] ?: "application/octet-stream"

                val file = withContext(Dispatchers.IO) {
                    val dir = File(requireContext().cacheDir, "documents")
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, fileName)
                    f.outputStream().use { out -> body.byteStream().copyTo(out) }
                    f
                }

                if (isAdded) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), "${requireContext().packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, contentType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(requireContext(), getString(R.string.passport_doc_no_viewer), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Document download failed: ${e.message}")
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.passport_doc_no_connection), Toast.LENGTH_SHORT).show()
            }
        }
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
        val ctx = requireContext()
        val photoDir = File(ctx.cacheDir, "photos")
        if (!photoDir.exists()) photoDir.mkdirs()
        val file = File(photoDir, "capture_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = file
        pendingPhotoUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        takePhotoLauncher.launch(pendingPhotoUri)
    }

    /**
     * Called when TakePicture() returns a full-resolution photo saved to [file].
     * Mirrors the front-end flow: send the raw file as multipart "file" field.
     */
    private fun onPhotoCapturedFile(file: File) {
        val view = this.view ?: return
        val person = currentPerson ?: return

        // Show preview immediately from the file
        val imgView = view.findViewById<ImageView>(R.id.imgWorkerPhoto)
        val placeholder = view.findViewById<View>(R.id.layoutPhotoPlaceholder)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            imgView.setImageBitmap(bitmap)
            imgView.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
        }

        val projectId = person.project_id
        if (projectId == null) {
            Toast.makeText(requireContext(), getString(R.string.passport_photo_error, "No project ID"), Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Copy to persistent cache so it survives if the temp file is deleted
            val cachedFile = withContext(Dispatchers.IO) {
                val dest = getCachedPhotoFile(person.unique_id_value)
                file.copyTo(dest, overwrite = true)
                dest
            }

            // Try upload with 15s timeout (photos can be large)
            val uploaded = tryUploadPhotoFile(projectId, person.unique_id_value, cachedFile)

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

            // Clean up temp capture file
            file.delete()
        }
    }

    /**
     * Uploads the photo file directly to the API — mirrors front-end logic:
     * FormData.append('file', rawFile) → POST multipart/form-data.
     * Returns true if successful.
     */
    private suspend fun tryUploadPhotoFile(projectId: Int, personUuid: String, photoFile: File): Boolean {
        return try {
            val result = withTimeoutOrNull(15_000L) {
                withContext(Dispatchers.IO) {
                    val mediaType = "image/jpeg".toMediaType()
                    val requestBody = photoFile.asRequestBody(mediaType)
                    val filePart = MultipartBody.Part.createFormData("file", photoFile.name, requestBody)

                    val api = ServiceLocator.apiClient.getService()
                    val response = api.uploadWorkerPhoto(projectId, personUuid, filePart)

                    if (response.isSuccessful) {
                        val photoUrl = response.body()?.photoUrl
                        if (photoUrl != null) {
                            ServiceLocator.personDao.updatePhotoUrl(personUuid, photoUrl)
                            currentPerson = currentPerson?.copy(photo_url = photoUrl)
                        }
                        Log.i(TAG, "Photo uploaded OK for $personUuid → $photoUrl")
                        true
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "(no body)"
                        Log.e(TAG, "Photo upload failed: HTTP ${response.code()} — $errorBody")
                        false
                    }
                }
            }
            result == true
        } catch (e: Exception) {
            Log.e(TAG, "Photo upload exception: ${e.javaClass.simpleName}: ${e.message}", e)
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

    private fun openObservation() {
        val person = currentPerson ?: return
        val bundle = Bundle().apply {
            putString("preloaded_worker_uuid", person.unique_id_value)
        }
        findNavController().navigate(R.id.observationsGeneralFragment, bundle)
    }

    private fun showWaiting() {
        val view = this.view ?: return
        currentPerson = null
        view.findViewById<View>(R.id.layoutLoaded).visibility = View.GONE
        view.findViewById<View>(R.id.layoutWaiting).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.layoutTrainingItems).removeAllViews()
        view.findViewById<LinearLayout>(R.id.layoutDocItems).removeAllViews()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        soundPool?.release()
        soundPool = null
        loadedSounds.clear()
    }
}
