package com.example.hassiwrapper

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.hassiwrapper.ui.scanner.CustomScannerActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.example.hassiwrapper.scanner.DataWedgeManager
import com.example.hassiwrapper.ui.login.LoginActivity
import com.example.hassiwrapper.update.UpdateChecker
import com.example.hassiwrapper.update.UpdateInfo
import com.example.hassiwrapper.update.UpdateInstaller
import com.example.hassiwrapper.data.db.entities.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.example.hassiwrapper.network.dto.SmsPackingListDto
import com.example.hassiwrapper.network.dto.SmsVehicleDto
import com.example.hassiwrapper.network.dto.SpoolDto
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet
import com.example.hassiwrapper.ui.qrscanner.QrResult
import com.example.hassiwrapper.ui.qrscanner.parseQr
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var dataWedgeManager: DataWedgeManager
        private set

    private lateinit var navController: NavController
    private lateinit var etGlobalWedge: EditText

    private val globalWedgeHandler = Handler(Looper.getMainLooper())
    private val globalWedgeTrigger = Runnable {
        val text = etGlobalWedge.text?.toString()?.trimEnd('\n', '\r', ' ').orEmpty()
        etGlobalWedge.setText("")
        if (text.isBlank()) return@Runnable
        val dest = navController.currentDestination?.id
        when {
            dest == R.id.qrScannerFragment -> { /* handled by QrScannerFragment */ }
            dest == R.id.loadSpoolsFragment ||
            dest == R.id.sendPackingListFragment ||
            dest == R.id.receivePackingListFragment -> dataWedgeManager.emitScan(text)
            else -> {
                Log.d(TAG, "Global keyboard-wedge scan (${text.length} chars): ${text.take(80)}")
                lifecycleScope.launch { handleGlobalScan(text) }
            }
        }
    }

    private var pendingUpdate: UpdateInfo? = null
    private var autoSyncJob: Job? = null

    private var soundPool: SoundPool? = null
    private var soundSuccess = 0
    private var soundError = 0

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra(CustomScannerActivity.EXTRA_RESULT)
            code?.let { scanned ->
                val dest = navController.currentDestination?.id
                if (dest == R.id.loadSpoolsFragment ||
                    dest == R.id.sendPackingListFragment ||
                    dest == R.id.receivePackingListFragment) {
                    dataWedgeManager.emitScan(scanned)
                } else {
                    lifecycleScope.launch { handleGlobalScan(scanned) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_SYNC_INTERVAL_MS = 60_000L // 1 minute
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(audioAttrs).build()
        soundSuccess = soundPool!!.load(this, R.raw.beep_allowed, 1)
        soundError   = soundPool!!.load(this, R.raw.beep_denied, 1)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val navView = findViewById<NavigationView>(R.id.navView)

        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.app_name, R.string.app_name
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        navView.setupWithNavController(navController)

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.homeFragment,
                R.id.qrScannerFragment,
                R.id.inventarioFragment,
                R.id.syncFragment,
                R.id.transfersFragment,
                R.id.settingsFragment -> {
                    navController.navigate(item.itemId)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        applyProfileMenuVisibility(navView)

        dataWedgeManager = ServiceLocator.dataWedgeManager()

        etGlobalWedge = findViewById(R.id.etGlobalWedge)
        etGlobalWedge.showSoftInputOnFocus = false
        etGlobalWedge.inputType = InputType.TYPE_NULL

        navController.addOnDestinationChangedListener { _, dest, _ ->
            toolbar.title = dest.label
            if (dest.id != R.id.qrScannerFragment) {
                etGlobalWedge.post {
                    etGlobalWedge.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(etGlobalWedge.windowToken, 0)
                }
            }
        }

        navView.getHeaderView(0)
            .findViewById<android.widget.TextView>(R.id.txtNavVersion)
            .text = "ATLAS Native ${BuildConfig.BUILD_TAG}"
        etGlobalWedge.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if ((s?.length ?: 0) > 0) {
                    globalWedgeHandler.removeCallbacks(globalWedgeTrigger)
                    globalWedgeHandler.postDelayed(globalWedgeTrigger, 300)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataWedgeManager.scanFlow.collect { code ->
                    val dest = navController.currentDestination?.id
                    if (dest != R.id.qrScannerFragment &&
                        dest != R.id.loadSpoolsFragment &&
                        dest != R.id.sendPackingListFragment &&
                        dest != R.id.receivePackingListFragment) {
                        handleGlobalScan(code)
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                ServiceLocator.apiClient.seedDefaults()
                if (!ServiceLocator.authRepo.isAuthenticated()) {
                    Log.d(TAG, "Initial sync: not authenticated, attempting auto-re-login")
                    ServiceLocator.authRepo.reLoginWithStoredCode(ServiceLocator.apiClient.getService())
                }
                if (ServiceLocator.authRepo.isAuthenticated()) {
                    ServiceLocator.syncService.fullSync()
                    syncSmsData()
                } else {
                    Log.w(TAG, "Initial sync: not authenticated after re-login attempt, skipping SMS sync")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync/SMS error", e)
            }
        }

        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate(BuildConfig.BUILD_TAG)
            if (update != null) {
                withContext(Dispatchers.Main) { showUpdateDialog(update) }
            }
        }

        startAutoSync()

        findViewById<FloatingActionButton>(R.id.fabQrScan).setOnClickListener {
            val intent = Intent(this, CustomScannerActivity::class.java)
                .putExtra(CustomScannerActivity.EXTRA_FRONT_CAMERA, false)
            qrScanLauncher.launch(intent)
        }
    }

    private fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = lifecycleScope.launch {
            while (true) {
                delay(AUTO_SYNC_INTERVAL_MS)
                try {
                    if (!ServiceLocator.authRepo.isAuthenticated()) {
                        Log.d(TAG, "Auto-sync: session expired, attempting auto-re-login")
                        val relogged = ServiceLocator.authRepo.reLoginWithStoredCode(
                            ServiceLocator.apiClient.getService()
                        )
                        if (!relogged) {
                            Log.d(TAG, "Auto-sync: re-login failed, skipping cycle")
                            continue
                        }
                        Log.d(TAG, "Auto-sync: re-login succeeded")
                    }
                    val connectivity = ServiceLocator.apiClient.checkConnectivity()
                    if (!connectivity.apiReachable) continue
                    Log.d(TAG, "Auto-sync: starting")
                    ServiceLocator.syncService.fullSync()
                    syncSmsData()
                    Log.d(TAG, "Auto-sync: completed")
                    Toast.makeText(this@MainActivity, R.string.auto_sync_completed, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-sync: failed silently", e)
                }
            }
        }
    }

    private fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    override fun onResume() {
        super.onResume()
        startAutoSync()
        dataWedgeManager.register()
        if (navController.currentDestination?.id != R.id.qrScannerFragment) {
            etGlobalWedge.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etGlobalWedge.windowToken, 0)
        }

        // If the user just came back from granting the install-unknown-apps permission, proceed
        val pending = pendingUpdate
        if (pending != null && canInstallPackages()) {
            pendingUpdate = null
            UpdateInstaller.downloadAndInstall(this, pending)
        }

        // Safety net for Xiaomi HyperOS / MIUI: if the static receiver was suppressed
        // and a downloaded APK is sitting on disk, install it now.
        if (canInstallPackages()) {
            UpdateInstaller.installPendingApkIfExists(this)
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoSync()
        dataWedgeManager.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }

    fun playSuccess() { soundPool?.play(soundSuccess, 1f, 1f, 0, 0, 1f) }
    fun playError()   { soundPool?.play(soundError,   1f, 1f, 0, 0, 1f) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // DataWedge "Activity" delivery mode: barcode sent via startActivity to the foreground activity
        val data = intent.getStringExtra("data")
            ?: intent.getStringExtra("barcode_data")
            ?: intent.getStringExtra("com.symbol.datawedge.data_string")
            ?: run {
                // Try any String extra >= 3 chars as fallback
                intent.extras?.keySet()?.firstNotNullOfOrNull { k ->
                    intent.getStringExtra(k)?.takeIf { it.length >= 3 }
                }
            }
            ?: return
        Log.d(TAG, "onNewIntent: DataWedge activity-mode data='$data' action=${intent.action}")
        dataWedgeManager.emitScan(data)
    }

    override fun onBackPressed() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            if (navController.currentDestination?.id != R.id.homeFragment) {
                navController.navigate(R.id.homeFragment)
            } else {
                super.onBackPressed()
            }
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(getString(R.string.update_message, update.version))
            .setPositiveButton(R.string.update_btn_update) { _, _ -> startUpdate(update) }
            .setNegativeButton(R.string.update_btn_later, null)
            .setCancelable(false)
            .show()
    }

    private fun startUpdate(update: UpdateInfo) {
        if (!canInstallPackages()) {
            // Ask the user to grant permission; resume is handled in onResume
            pendingUpdate = update
            Toast.makeText(this, R.string.update_permission_needed, Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            return
        }
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        UpdateInstaller.downloadAndInstall(this, update)
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
    }

    /**
     * Called from [SettingsFragment] via the "Buscar actualizaciones" button.
     * [onComplete] is invoked on the main thread with true if already up-to-date.
     */
    fun checkForUpdatesManually(onComplete: (alreadyUpToDate: Boolean) -> Unit) {
        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate(BuildConfig.BUILD_TAG)
            withContext(Dispatchers.Main) {
                if (update != null) {
                    onComplete(false)
                    showUpdateDialog(update)
                } else {
                    onComplete(true)
                }
            }
        }
    }

    fun logout() {
        lifecycleScope.launch {
            ServiceLocator.authRepo.logout()
            Toast.makeText(this@MainActivity, getString(R.string.sync_auth_none), Toast.LENGTH_SHORT).show()
        }
    }

    fun launchLogin() {
        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
        finish()
    }

    /**
     * Show/hide navigation menu items based on the current profile.
     * USER:  Home + Scanner + Sync + Settings.
     * HSE:   USER items + Passport + Observations General + Inspections.
     * ADMIN / PRE / DEV: all items visible.
     */
    private fun applyProfileMenuVisibility(navView: NavigationView) {
        val menu = navView.menu
        val profile = ProfileManager.currentProfile()
        val isUser = profile == ProfileManager.Profile.USER
        val isFull = profile == ProfileManager.Profile.ADMIN ||
                     profile == ProfileManager.Profile.PRE   ||
                     profile == ProfileManager.Profile.DEV

        // Inventario (Spools + PLs + Vehículos): HSE, ADMIN, PRE, DEV
        menu.findItem(R.id.inventarioFragment)?.isVisible = !isUser

        // Transfers: only full profiles (ADMIN, PRE, DEV)
        menu.findItem(R.id.transfersFragment)?.isVisible = isFull

        // Home + QR Scanner + Sync + Settings always visible
    }

    internal suspend fun syncSmsData() {
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "syncSmsData: no project code for id=$projectId")
            return
        }
        Log.d(TAG, "syncSmsData: fetching SMS data for $projectCode")
        try {
            val service = ServiceLocator.apiClient.getService()

            // ── Upload: vehicle loadings ──────────────────────────────────────
            try {
                val unsyncedLoadings = ServiceLocator.smsVehicleLoadingDao.getUnsynced()
                if (unsyncedLoadings.isNotEmpty()) {
                    val uploaded = mutableListOf<Long>()
                    val plIdsToMarkReady = mutableSetOf<Long>()
                    for (loading in unsyncedLoadings) {
                        val loadingSpools = ServiceLocator.smsVehicleLoadingDao.getSpoolsByLoading(loading.loading_id)
                        val dto = com.example.hassiwrapper.network.dto.VehicleLoadingUploadDto(
                            vehicleLoadingId = loading.loading_id,
                            vehicleId        = loading.vehicle_id,
                            vehiclePlate     = loading.vehicle_plate,
                            projectId        = loading.project_id,
                            createdAt        = loading.created_at,
                            createdBy        = null,
                            spools           = loadingSpools.map { s ->
                                com.example.hassiwrapper.network.dto.VehicleLoadingSpoolUploadDto(
                                    spoolId       = s.spool_id,
                                    packingListId = s.packing_list_id
                                )
                            }
                        )
                        val resp = service.uploadVehicleLoading(projectCode, dto)
                        if (resp.isSuccessful) {
                            uploaded += loading.loading_id
                            loadingSpools.mapNotNull { it.packing_list_id }.forEach { plIdsToMarkReady += it }
                        } else {
                            Log.w(TAG, "uploadVehicleLoading ${loading.loading_id}: HTTP ${resp.code()}")
                        }
                    }
                    if (uploaded.isNotEmpty()) ServiceLocator.smsVehicleLoadingDao.markSynced(uploaded)
                    plIdsToMarkReady.forEach { plId ->
                        try { service.setPackingListReadyToSend(projectCode, plId, true) } catch (_: Exception) { }
                    }
                    Log.d(TAG, "syncSmsData: uploaded ${uploaded.size}/${unsyncedLoadings.size} vehicle loadings")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsData: vehicle loading upload failed", e)
            }

            // ── Upload: transfers ─────────────────────────────────────────────
            try {
                val unsyncedTransfers = ServiceLocator.smsTransferDao.getUnsynced()
                if (unsyncedTransfers.isNotEmpty()) {
                    val uploaded = mutableListOf<Long>()
                    for (transfer in unsyncedTransfers) {
                        val transferSpools = ServiceLocator.smsTransferDao.getSpoolsByTransfer(transfer.transfer_id)
                        val dto = com.example.hassiwrapper.network.dto.TransferUploadDto(
                            transferId      = transfer.transfer_id,
                            type            = transfer.transfer_type,
                            projectId       = transfer.project_id,
                            signatureBase64 = transfer.signature_data,
                            createdAt       = transfer.created_at,
                            createdBy       = null,
                            spools          = transferSpools.map { s ->
                                com.example.hassiwrapper.network.dto.TransferSpoolUploadDto(
                                    spoolId       = s.spool_id,
                                    packingListId = null
                                )
                            }
                        )
                        val resp = service.uploadTransfer(projectCode, dto)
                        if (resp.isSuccessful) {
                            uploaded += transfer.transfer_id
                        } else {
                            Log.w(TAG, "uploadTransfer ${transfer.transfer_id}: HTTP ${resp.code()}")
                        }
                    }
                    if (uploaded.isNotEmpty()) ServiceLocator.smsTransferDao.markSynced(uploaded)
                    Log.d(TAG, "syncSmsData: uploaded ${uploaded.size}/${unsyncedTransfers.size} transfers")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsData: transfer upload failed", e)
            }

            // ── Upload: vehicle on/off-route state ───────────────────────────────────
            try {
                val unsyncedRoute = ServiceLocator.smsVehicleDao.getUnsyncedRouteState()
                if (unsyncedRoute.isNotEmpty()) {
                    val uploaded = mutableListOf<Long>()
                    for (vehicle in unsyncedRoute) {
                        val resp = if (vehicle.on_route) {
                            service.setVehicleOnRoute(projectCode, vehicle.vehicle_id, vehicle.destination)
                        } else {
                            service.setVehicleOffRoute(projectCode, vehicle.vehicle_id)
                        }
                        if (resp.isSuccessful) {
                            uploaded += vehicle.vehicle_id
                        } else {
                            Log.w(TAG, "syncSmsData: vehicleRoute ${vehicle.vehicle_id}: HTTP ${resp.code()}")
                        }
                    }
                    if (uploaded.isNotEmpty()) ServiceLocator.smsVehicleDao.markRouteStateSynced(uploaded)
                    Log.d(TAG, "syncSmsData: synced ${uploaded.size}/${unsyncedRoute.size} vehicle route states")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSmsData: vehicle route state upload failed", e)
            }

            val spoolResp = service.getSpools(projectCode)
            if (spoolResp.isSuccessful) {
                val spoolRaw = spoolResp.body()?.string().orEmpty()
                Log.d(TAG, "syncSmsData spools raw(500): ${spoolRaw.take(500)}")
                val entities = parseSpoolEntities(spoolRaw, projectId)
                if (entities.isNotEmpty()) {
                    entities.take(2).forEach { e ->
                        Log.d(TAG, "spool entity: id=${e.spool_id} code=${e.spool_code} suf=${e.spool_suffix} line=${e.line_code} service=${e.service} train=${e.train} module=${e.module} area=${e.area_id} spec=${e.spec_id} unit=${e.unit_id} iso=${e.iso_type_id} sub=${e.subcontractor_id}")
                    }
                    val deleted = com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet.locallyDeletedSpoolIds
                    val toInsert = entities.filter { it.is_active && it.spool_id !in deleted }
                    // Preserve locally-set fields that the /spools endpoint doesn't return
                    val localSpools = ServiceLocator.smsSpoolDao.getByProjectIgnoreActive(projectId)
                        .associateBy { it.spool_id }
                    // Spools that are SENT but not yet RECEIVED locally — these stay in_transit=true
                    // regardless of upload status, until a local RECEIVE transfer confirms arrival.
                    val sentNotReceivedIds = ServiceLocator.smsTransferDao.getSpoolIdsInSentNotReceived().toSet()
                    val merged = toInsert.map { s ->
                        val local = localSpools[s.spool_id] ?: return@map s
                        s.copy(
                            in_transit = if (s.spool_id in sentNotReceivedIds) true else s.in_transit,
                            packing_list_id = s.packing_list_id ?: local.packing_list_id
                        )
                    }
                    ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                    ServiceLocator.smsSpoolDao.insertAll(merged)
                    Log.d(TAG, "syncSmsData: inserted ${merged.size} spools (${entities.size - merged.size} locally deleted filtered)")
                }
            }
            ServiceLocator.smsSpoolDao.deleteInactive()

            val plResp = service.getPackingLists(projectCode)
            if (plResp.isSuccessful) {
                val raw = plResp.body()?.string().orEmpty()
                val entities = parsePackingListEntities(raw, projectId)
                val deletedPLs = com.example.hassiwrapper.ui.packinglists.PackingListDetailFragment.locallyDeletedPLIds
                val activePLs = entities.filter { it.is_active && it.packing_list_id !in deletedPLs }
                // Preserve locally-set ready_to_send and vehicle assignment so API sync doesn't wipe them
                val localPLs = ServiceLocator.smsPackingListDao.getByProject(projectId)
                    .associateBy { it.packing_list_id }
                val mergedPLs = activePLs.map { pl ->
                    val local = localPLs[pl.packing_list_id] ?: return@map pl
                    pl.copy(
                        ready_to_send = local.ready_to_send,
                        vehicle_id    = pl.vehicle_id ?: local.vehicle_id,
                        vehicle_plate = pl.vehicle_plate ?: local.vehicle_plate
                    )
                }
                ServiceLocator.smsPackingListDao.deleteSyncedByProject(projectId)
                if (mergedPLs.isNotEmpty()) ServiceLocator.smsPackingListDao.insertAll(mergedPLs)
                Log.d(TAG, "syncSmsData: inserted ${mergedPLs.size} packing lists (${entities.size - mergedPLs.size} inactive/deleted skipped)")
            }
            ServiceLocator.smsPackingListDao.deleteInactive()

            val vehicleResp = service.getVehicles(projectCode)
            if (vehicleResp.isSuccessful) {
                val entities = parseVehicleEntities(vehicleResp.body()?.string().orEmpty(), projectId)
                if (entities.isNotEmpty()) {
                    // Preserve local route state for vehicles with pending upload (route_synced=false)
                    val localById = ServiceLocator.smsVehicleDao.getByProject(projectId).associateBy { it.vehicle_id }
                    val mergedVehicles = entities.map { v ->
                        val local = localById[v.vehicle_id]
                        if (local != null && !local.route_synced) {
                            v.copy(on_route = local.on_route, destination = local.destination, route_synced = false)
                        } else {
                            v.copy(route_synced = true)
                        }
                    }
                    ServiceLocator.smsVehicleDao.deleteByProject(projectId)
                    ServiceLocator.smsVehicleDao.insertAll(mergedVehicles)
                    Log.d(TAG, "syncSmsData: inserted ${mergedVehicles.size} vehicles")
                }
            }

            // Project-specific lookups
            fun logLookup(name: String, resp: retrofit2.Response<okhttp3.ResponseBody>, count: Int) {
                Log.d(TAG, "syncSMS lookup $name: HTTP ${resp.code()} → parsed $count")
                if (!resp.isSuccessful) Log.w(TAG, "syncSMS lookup $name error body: ${resp.errorBody()?.string()?.take(200)}")
            }
            service.getAreas(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS areas raw(200): ${raw.take(200)}")
                parseAreaEntities(raw, projectId).also { list ->
                    logLookup("areas", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsAreaDao.deleteByProject(projectId); ServiceLocator.smsAreaDao.insertAll(list) }
                }
            }
            service.getSpecs(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS specs raw(200): ${raw.take(200)}")
                parseSpecEntities(raw, projectId).also { list ->
                    logLookup("specs", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSpecDao.deleteByProject(projectId); ServiceLocator.smsSpecDao.insertAll(list) }
                }
            }
            service.getSubcontractors(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS subcontractors raw(200): ${raw.take(200)}")
                parseSubcontractorEntities(raw, projectId).also { list ->
                    logLookup("subcontractors", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSubcontractorDao.deleteByProject(projectId); ServiceLocator.smsSubcontractorDao.insertAll(list) }
                }
            }

            service.getBoreSizes().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS bore-sizes raw(300): ${raw.take(300)}")
                parseBoreSizes(raw).also { list ->
                    logLookup("bore-sizes", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsBoreSizeDao.deleteAll(); ServiceLocator.smsBoreSizeDao.insertAll(list) }
                }
            }
            service.getIsoTypes().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS iso-types raw(300): ${raw.take(300)}")
                parseIsoTypes(raw).also { list ->
                    logLookup("iso-types", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsIsoTypeDao.deleteAll(); ServiceLocator.smsIsoTypeDao.insertAll(list) }
                }
            }
            service.getPositions().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parsePositions(raw).also { list ->
                    logLookup("positions", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsPositionDao.deleteAll(); ServiceLocator.smsPositionDao.insertAll(list) }
                }
            }
            service.getSpoolStatuses().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS spool-statuses raw(300): ${raw.take(300)}")
                parseSpoolStatuses(raw).also { list ->
                    logLookup("spool-statuses", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSpoolStatusDao.deleteAll(); ServiceLocator.smsSpoolStatusDao.insertAll(list) }
                }
            }
            service.getUnits().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseUnits(raw).also { list ->
                    logLookup("units", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsUnitDao.deleteAll(); ServiceLocator.smsUnitDao.insertAll(list) }
                }
            }
            service.getIncompleteStatuses().let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseIncompleteStatuses(raw).also { list ->
                    logLookup("incomplete-statuses", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsIncompleteStatusDao.deleteAll(); ServiceLocator.smsIncompleteStatusDao.insertAll(list) }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "syncSmsData failed", e)
        }
    }

    private fun parseSpoolEntities(raw: String, projectId: Int): List<SmsSpoolEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> listOf("data","items","results","spools").asSequence()
                    .mapNotNull { el.asJsonObject.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
            array.mapIndexedNotNull { idx, element ->
                if (!element.isJsonObject) return@mapIndexedNotNull null
                try {
                    val dto = gson.fromJson(element, SpoolDto::class.java)
                    val entity = dto.toEntity()
                    if (entity.spool_id == 0L) return@mapIndexedNotNull null
                    val finalId = if (dto.spoolId?.toDoubleOrNull() == null && !dto.spoolId.isNullOrEmpty()) {
                        val key = "${dto.spoolId}-${dto.spoolSuffix.orEmpty()}-$idx"
                        val crc = CRC32(); crc.update(key.toByteArray())
                        crc.value.toLong().takeIf { it != 0L } ?: (idx + 1L)
                    } else entity.spool_id
                    entity.copy(spool_id = finalId, project_id = projectId)
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parsePackingListEntities(raw: String, projectId: Int): List<SmsPackingListEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> listOf("data","items","results","packingLists","packing_lists").asSequence()
                    .mapNotNull { el.asJsonObject.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsPackingListDto::class.java)
                    val entity = dto.toEntity(projectId)
                    if (entity.packing_list_id == 0L) null else entity
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parseVehicleEntities(raw: String, projectId: Int): List<SmsVehicleEntity> {
        val gson = Gson()
        return try {
            val el = JsonParser.parseString(raw)
            val array = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> listOf("data","items","results","vehicles").asSequence()
                    .mapNotNull { el.asJsonObject.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                try {
                    val dto = gson.fromJson(element, SmsVehicleDto::class.java)
                    val entity = dto.toEntity(projectId)
                    if (entity.vehicle_id == 0L) null else entity
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parseAreaEntities(raw: String, projectId: Int): List<SmsAreaEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jLong("areaId", "area_id", "id") ?: return@mapNotNull null
                SmsAreaEntity(id, projectId, o.jLong("parentAreaId", "parent_area_id"),
                    o.jStr("name").orEmpty(), o.jStr("fullPath", "full_path").orEmpty(),
                    o.jInt("level") ?: 0, o.jBool("isActive", "is_active") ?: true,
                    o.jStr("createdAt", "created_at").orEmpty(), o.jStr("createdBy", "created_by").orEmpty(),
                    o.jStr("updatedAt", "updated_at"), o.jStr("updatedBy", "updated_by"))
            }
        }

    private fun parseSpecEntities(raw: String, projectId: Int): List<SmsSpecEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jLong("specId", "spec_id", "id") ?: return@mapNotNull null
                SmsSpecEntity(id, projectId, o.jStr("code").orEmpty(), o.jStr("description"),
                    o.jStr("materialType", "material_type"), o.jBool("isActive", "is_active") ?: true,
                    o.jStr("createdAt", "created_at").orEmpty(), o.jStr("createdBy", "created_by").orEmpty(),
                    o.jStr("updatedAt", "updated_at"), o.jStr("updatedBy", "updated_by"))
            }
        }

    private fun parseSubcontractorEntities(raw: String, projectId: Int): List<SmsSubcontractorEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jLong("subcontractorId", "subcontractor_id", "id") ?: return@mapNotNull null
                SmsSubcontractorEntity(id, projectId, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jBool("isActive", "is_active") ?: true, o.jStr("createdAt", "created_at").orEmpty(),
                    o.jStr("createdBy", "created_by").orEmpty(), o.jStr("updatedAt", "updated_at"),
                    o.jStr("updatedBy", "updated_by"))
            }
        }

    private fun parseBoreSizes(raw: String): List<SmsBoreSizeEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("boreSizeId", "bore_size_id", "id") ?: return@mapNotNull null
                SmsBoreSizeEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private fun parseIsoTypes(raw: String): List<SmsIsoTypeEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("isoTypeId", "iso_type_id", "id") ?: return@mapNotNull null
                SmsIsoTypeEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private fun parsePositions(raw: String): List<SmsPositionEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("positionId", "position_id", "id") ?: return@mapNotNull null
                SmsPositionEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private fun parseSpoolStatuses(raw: String): List<SmsSpoolStatusEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("statusId", "status_id", "id") ?: return@mapNotNull null
                SmsSpoolStatusEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private fun parseUnits(raw: String): List<SmsUnitEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("unitId", "unit_id", "id") ?: return@mapNotNull null
                SmsUnitEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private fun parseIncompleteStatuses(raw: String): List<SmsIncompleteStatusEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jInt("incompleteStatusId", "incomplete_status_id", "id") ?: return@mapNotNull null
                SmsIncompleteStatusEntity(id, o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jInt("sortOrder", "sort_order"), o.jBool("isActive", "is_active") ?: true)
            }
        }

    private suspend fun handleGlobalScan(raw: String) {
        val code = raw.trim().trimStart('﻿')
        Log.d(TAG, "handleGlobalScan: ${code.take(80)}")
        when (val result = parseQr(code)) {
            is QrResult.Spool -> {
                val spools = ServiceLocator.smsSpoolDao.getByCode(result.spoolCode)
                val spool = if (result.spoolSuffix != null)
                    spools.find { it.spool_suffix == result.spoolSuffix } ?: spools.firstOrNull()
                else
                    spools.firstOrNull()
                if (spool != null) {
                    showScanRegisteredDialog(getString(R.string.scan_result_spool_registered, spool.displayCode))
                } else {
                    showScanNotRegisteredDialog(getString(R.string.scan_result_spool_not_registered)) {
                        navController.navigate(R.id.action_global_newSpoolFragment,
                            Bundle().apply { putString("prefillSpoolCode", result.spoolCode) })
                    }
                }
            }
            is QrResult.VehicleId -> {
                val vehicle = ServiceLocator.smsVehicleDao.getById(result.id)
                if (vehicle != null) {
                    showScanRegisteredDialog(getString(R.string.scan_result_vehicle_registered, vehicle.license_plate))
                } else {
                    showScanNotRegisteredDialog(getString(R.string.scan_result_vehicle_not_registered)) {
                        navController.navigate(R.id.action_global_newVehicleFragment, Bundle())
                    }
                }
            }
            is QrResult.VehiclePlate -> {
                val vehicle = ServiceLocator.smsVehicleDao.getByLicensePlate(result.plate)
                if (vehicle != null) {
                    showScanRegisteredDialog(getString(R.string.scan_result_vehicle_registered, vehicle.license_plate))
                } else {
                    showScanNotRegisteredDialog(getString(R.string.scan_result_vehicle_not_registered)) {
                        navController.navigate(R.id.action_global_newVehicleFragment,
                            Bundle().apply { putString("prefillPlate", result.plate) })
                    }
                }
            }
            is QrResult.VehicleBadge, is QrResult.Unknown -> { /* ignore on non-scanner screens */ }
        }
    }

    private fun showScanRegisteredDialog(message: String) {
        com.google.android.material.snackbar.Snackbar
            .make(findViewById(android.R.id.content), message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    private fun showScanNotRegisteredDialog(message: String, onCreate: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(getString(R.string.scan_action_create)) { _, _ -> onCreate() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Called from SettingsFragment after a profile change. */
    fun refreshProfileMenu() {
        val navView = findViewById<NavigationView>(R.id.navView)
        applyProfileMenuVisibility(navView)

        val profile = ProfileManager.currentProfile()
        val currentDest = navController.currentDestination?.id

        // If USER and currently on a hidden fragment, navigate to home
        if (profile == ProfileManager.Profile.USER) {
            val allowedInUser = setOf(R.id.homeFragment, R.id.qrScannerFragment, R.id.syncFragment, R.id.settingsFragment)
            if (currentDest != null && currentDest !in allowedInUser) {
                navController.navigate(R.id.homeFragment)
            }
        }
        // If HSE and currently on an ADMIN-only fragment, navigate to home
        if (profile == ProfileManager.Profile.HSE) {
            val allowedInHse = setOf(
                R.id.homeFragment, R.id.qrScannerFragment, R.id.syncFragment, R.id.settingsFragment,
                R.id.inventarioFragment, R.id.packingListsFragment, R.id.vehiclesFragment,
                R.id.scannerFragment, R.id.newPackingListFragment, R.id.newSpoolFragment,
                R.id.newVehicleFragment, R.id.packingListDetailFragment, R.id.vehicleDetailFragment,
                R.id.observationFragment
            )
            if (currentDest != null && currentDest !in allowedInHse) {
                navController.navigate(R.id.homeFragment)
            }
        }
    }
}

// ── SMS JSON parse helpers ────────────────────────────────────────────────────

internal fun smsJsonArray(raw: String): List<JsonElement> = try {
    val el = JsonParser.parseString(raw)
    when {
        el.isJsonArray -> el.asJsonArray.toList()
        el.isJsonObject -> listOf("data", "items", "results").asSequence()
            .mapNotNull { el.asJsonObject.get(it) }
            .firstOrNull { it.isJsonArray }?.asJsonArray?.toList()
        else -> null
    } ?: emptyList()
} catch (_: Exception) { emptyList() }

internal fun JsonObject.jStr(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k ->
        if (has(k) && !get(k).isJsonNull) try { get(k).asString } catch (_: Exception) { null } else null
    }

internal fun JsonObject.jInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k ->
        if (has(k) && !get(k).isJsonNull) try { get(k).asInt } catch (_: Exception) { null } else null
    }

internal fun JsonObject.jLong(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { k ->
        if (has(k) && !get(k).isJsonNull) try { get(k).asLong } catch (_: Exception) { null } else null
    }

internal fun JsonObject.jDbl(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { k ->
        if (has(k) && !get(k).isJsonNull) try { get(k).asDouble } catch (_: Exception) { null } else null
    }

internal fun JsonObject.jBool(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { k ->
        if (has(k) && !get(k).isJsonNull) try { get(k).asBoolean } catch (_: Exception) { null } else null
    }
