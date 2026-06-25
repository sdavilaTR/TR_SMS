package com.example.hassiwrapper

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
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
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var etGlobalWedge: EditText
    private var kioskModeEnabled = false

    private val globalWedgeHandler = Handler(Looper.getMainLooper())
    private val globalWedgeTrigger = Runnable {
        val text = etGlobalWedge.text?.toString()?.trimEnd('\n', '\r', ' ').orEmpty()
        etGlobalWedge.setText("")
        if (text.isBlank()) return@Runnable
        val dest = navController.currentDestination?.id
        when {
            dest == R.id.qrScannerFragment -> { /* handled by QrScannerFragment */ }
            dest == R.id.sendPackingListFragment ||
            dest == R.id.receivePackingListFragment ||
            dest == R.id.packingListDetailFragment ||
            dest == R.id.newPackingListFragment ||
            dest == R.id.newIncidentFragment -> dataWedgeManager.emitScan(text)
            else -> {
                Log.d(TAG, "Global keyboard-wedge scan (${text.length} chars): ${text.take(80)}")
                lifecycleScope.launch { handleGlobalScan(text) }
            }
        }
    }

    private var pendingUpdate: UpdateInfo? = null
    private var autoSyncJob: Job? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                if (dest == R.id.sendPackingListFragment ||
                    dest == R.id.receivePackingListFragment ||
                    dest == R.id.packingListDetailFragment ||
                    dest == R.id.newPackingListFragment ||
                    dest == R.id.newIncidentFragment) {
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

        // Fix Device Owner policies that may have been applied before this code existed
        // (e.g. camera disabled, camera permission auto-denied).
        com.example.hassiwrapper.admin.TracDeviceAdmin.applyOwnerDefaults(this)

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

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController
        navView.setupWithNavController(navController)

        applyDrawerAccess()

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.homeFragment,
                R.id.qrScannerFragment,
                R.id.inventarioFragment,
                R.id.syncFragment,
                R.id.transfersFragment,
                R.id.incidentsFragment,
                R.id.eventHistoryFragment,
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
        // TYPE_NULL puts the field in single-line mode, so the keystroke-emulated
        // newline between QR lines (e.g. "RIYAS PACKING LIST\nID: ...") fires the IME
        // "Done" action instead of being appended — truncating multi-line scans at the
        // first line. Multi-line text type lets embedded \n land as plain content.
        etGlobalWedge.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        navController.addOnDestinationChangedListener { _, dest, _ ->
            toolbar.title = dest.label
            // newPackingListFragment manages its own wedge focus internally
            if (dest.id != R.id.qrScannerFragment && dest.id != R.id.newPackingListFragment) {
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
                        dest != R.id.sendPackingListFragment &&
                        dest != R.id.receivePackingListFragment &&
                        dest != R.id.packingListDetailFragment &&
                        dest != R.id.newPackingListFragment) {
                        handleGlobalScan(code)
                    }
                }
            }
        }

        // Sanitize a legacy/invalid terminal location (e.g. "Test") to the default so the
        // Send/Receive gates aren't permanently blocked by a value that's no longer accepted.
        lifecycleScope.launch {
            val current = ServiceLocator.configRepo.get("device_location")
            val normalized = normalizeDeviceLocation(current)
            if (normalized == null) {
                ServiceLocator.configRepo.set("device_location", DEFAULT_DEVICE_LOCATION)
                Log.i(TAG, "device_location '$current' invalid → reset to $DEFAULT_DEVICE_LOCATION")
            } else if (normalized != current) {
                ServiceLocator.configRepo.set("device_location", normalized)
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
                runSyncCycle("auto")
            }
        }
    }

    private fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    /**
     * One guarded sync pass: re-login if the session expired, skip if the API is
     * unreachable, then upload pending local data and refresh SMS data.
     * Shared by the 60 s polling loop and the connectivity-recovery callback.
     * [SyncService.fullSync] holds a mutex, so overlapping calls are de-duplicated.
     */
    private suspend fun runSyncCycle(reason: String) {
        try {
            if (!ServiceLocator.authRepo.isAuthenticated()) {
                Log.d(TAG, "Sync ($reason): session expired, attempting auto-re-login")
                val relogged = ServiceLocator.authRepo.reLoginWithStoredCode(
                    ServiceLocator.apiClient.getService()
                )
                if (!relogged) {
                    Log.d(TAG, "Sync ($reason): re-login failed, skipping cycle")
                    return
                }
                Log.d(TAG, "Sync ($reason): re-login succeeded")
            }
            val connectivity = ServiceLocator.apiClient.checkConnectivity()
            if (!connectivity.apiReachable) return
            Log.d(TAG, "Sync ($reason): starting")
            ServiceLocator.syncService.fullSync()
            syncSmsData()
            Log.d(TAG, "Sync ($reason): completed")
            Toast.makeText(this@MainActivity, R.string.auto_sync_completed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Sync ($reason): failed silently", e)
        }
    }

    /**
     * Registers a connectivity listener so pending local data is pushed to the
     * server the moment the terminal regains a network — rather than waiting up
     * to [AUTO_SYNC_INTERVAL_MS] for the next polling cycle.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available — triggering reconnect sync")
                lifecycleScope.launch { runSyncCycle("reconnect") }
            }
        }
        networkCallback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
            }
        }
        networkCallback = null
    }

    override fun onResume() {
        super.onResume()
        startAutoSync()
        registerNetworkCallback()
        dataWedgeManager.register()
        lifecycleScope.launch {
            val kioskEnabled = ServiceLocator.configRepo.get("kiosk_mode") == "true"
            kioskModeEnabled = kioskEnabled
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (kioskEnabled) {
                if (am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask()
                }
            } else {
                try { stopLockTask() } catch (_: Exception) {}
            }
        }
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
        unregisterNetworkCallback()
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

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    override fun onBackPressed() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            if (navController.currentDestination?.id != R.id.homeFragment) {
                navController.popBackStack(R.id.homeFragment, false)
            } else if (!kioskModeEnabled) {
                moveTaskToBack(true)
            }
            // Home with kiosk mode on: back does nothing (no exit)
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
    /**
     * GUEST profile gets no side drawer at all: lock it closed and drop the
     * hamburger icon (only Home is "top-level"; other screens get a back arrow).
     */
    private fun applyDrawerAccess() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val isGuest = ProfileManager.currentUserRole() == ProfileManager.UserRole.GUEST
        if (isGuest) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            appBarConfiguration = AppBarConfiguration(setOf(R.id.homeFragment))
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.homeFragment, R.id.qrScannerFragment, R.id.inventarioFragment,
                    R.id.syncFragment, R.id.transfersFragment, R.id.incidentsFragment,
                    R.id.eventHistoryFragment, R.id.settingsFragment
                ),
                drawerLayout
            )
        }
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    private fun applyProfileMenuVisibility(navView: NavigationView) {
        val menu = navView.menu
        val role = ProfileManager.currentUserRole()
        val isGuest = role == ProfileManager.UserRole.GUEST
        val isFull  = role == ProfileManager.UserRole.ADMIN || role == ProfileManager.UserRole.DEV

        menu.findItem(R.id.inventarioFragment)?.isVisible = !isGuest
        menu.findItem(R.id.transfersFragment)?.isVisible    = isFull
        menu.findItem(R.id.eventHistoryFragment)?.isVisible = isFull
        menu.findItem(R.id.incidentsFragment)?.isVisible  = !isGuest
        // Home + QR Scanner + Sync + Settings always visible
        findViewById<FloatingActionButton>(R.id.fabQrScan)?.visibility = if (isGuest) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * Runs [block] in isolation: a failure (network, parsing, DB) is logged and
     * swallowed instead of aborting the rest of [syncSmsData], so e.g. a vehicles
     * fetch failure can't prevent the dropdown lookups (units, positions, etc.)
     * further down from running.
     */
    private suspend fun syncSection(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsData: section '$name' failed", e)
        }
    }

    internal suspend fun syncSmsData() {
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        // Cache the resolved code under its own config key so a later, unrelated full-sync
        // failure (e.g. an unrelated 500 on /sync/download) can't wipe our ability to resolve
        // it — the projects table only gets refreshed by that same endpoint.
        val projectCodeCacheKey = "cached_project_code_$projectId"
        val liveProjectCode = ServiceLocator.projectDao.getById(projectId)?.project_code?.takeIf { it.isNotBlank() }
        val projectCode = if (liveProjectCode != null) {
            ServiceLocator.configRepo.set(projectCodeCacheKey, liveProjectCode)
            liveProjectCode
        } else {
            ServiceLocator.configRepo.get(projectCodeCacheKey)?.takeIf { it.isNotBlank() }?.also {
                Log.w(TAG, "syncSmsData: projects table has no row for id=$projectId, using cached code '$it'")
            }
        }
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "syncSmsData: no project code for id=$projectId")
            return
        }
        Log.d(TAG, "syncSmsData: fetching SMS data for '$projectCode' (len=${projectCode.length}, bytes=${projectCode.toByteArray().joinToString(",")})")

        val service = try {
            ServiceLocator.apiClient.getService()
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsData: could not obtain API service", e)
            return
        }

        syncSection("spools") {
            val spoolResp = service.getSpools(projectCode)
            if (spoolResp.isSuccessful) {
                val spoolRaw = spoolResp.body()?.string().orEmpty()
                Log.d(TAG, "syncSmsData spools raw(500): ${spoolRaw.take(500)}")
                val entities = parseSpoolEntities(spoolRaw, projectId)
                if (entities.isNotEmpty()) {
                    entities.take(2).forEach { e ->
                        Log.d(TAG, "spool entity: id=${e.spool_id} code=${e.spool_code} suf=${e.spool_suffix} line=${e.line_code} service=${e.service} train=${e.train} module=${e.module} area=${e.area_id} spec=${e.spec_id} unit=${e.unit_id} iso=${e.iso_type_id} sub=${e.subcontractor_id}")
                    }
                    val deleted = com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet.locallyDeletedSpoolIds +
                        ServiceLocator.outboxService.pendingDeleteIds(com.example.hassiwrapper.services.OutboxService.Entity.SPOOL).toSet()
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
                            in_transit = when {
                                s.spool_id in sentNotReceivedIds -> true
                                !local.synced -> local.in_transit
                                else -> s.in_transit
                            },
                            packing_list_id = if (!local.synced) local.packing_list_id else (s.packing_list_id ?: local.packing_list_id),
                            position_id = if (!local.synced) local.position_id else (s.position_id ?: local.position_id)
                        )
                    }
                    ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                    ServiceLocator.smsSpoolDao.insertAll(merged)
                    Log.d(TAG, "syncSmsData: inserted ${merged.size} spools (${entities.size - merged.size} locally deleted filtered)")
                }
            }
            ServiceLocator.smsSpoolDao.deleteInactive()
        }

        syncSection("packing-lists") {
            val plResp = service.getPackingLists(projectCode)
            if (plResp.isSuccessful) {
                val raw = plResp.body()?.string().orEmpty()
                val entities = parsePackingListEntities(raw, projectId)
                val deletedPLs = com.example.hassiwrapper.ui.packinglists.PackingListDetailFragment.locallyDeletedPLIds +
                    ServiceLocator.outboxService.pendingDeleteIds(com.example.hassiwrapper.services.OutboxService.Entity.PACKING_LIST).toSet()
                val activePLs = entities.filter { it.is_active && it.packing_list_id !in deletedPLs }
                // Preserve locally-set ready_to_send and vehicle assignment so API sync doesn't wipe them
                val localPLs = ServiceLocator.smsPackingListDao.getByProject(projectId)
                    .associateBy { it.packing_list_id }
                val mergedPLs = activePLs.map { pl ->
                    val local = localPLs[pl.packing_list_id] ?: return@map pl
                    pl.copy(
                        ready_to_send = local.ready_to_send,
                        vehicle_id    = pl.vehicle_id ?: local.vehicle_id,
                        vehicle_plate = pl.vehicle_plate ?: local.vehicle_plate,
                        position_id   = if (!local.synced) local.position_id else (pl.position_id ?: local.position_id)
                    )
                }
                ServiceLocator.smsPackingListDao.deleteSyncedByProject(projectId)
                if (mergedPLs.isNotEmpty()) ServiceLocator.smsPackingListDao.insertAll(mergedPLs)
                Log.d(TAG, "syncSmsData: inserted ${mergedPLs.size} packing lists (${entities.size - mergedPLs.size} inactive/deleted skipped)")
            }
            ServiceLocator.smsPackingListDao.deleteInactive()
        }

        syncSection("vehicles") {
            val vehicleResp = service.getVehicles(projectCode)
            if (vehicleResp.isSuccessful) {
                val rawVehicles = vehicleResp.body()?.string().orEmpty()
                Log.d(TAG, "syncSMS vehicles raw(500): ${rawVehicles.take(500)}")
                val parsed = parseVehicleEntities(rawVehicles, projectId)
                // Hide vehicles deleted offline (pending DELETE op) so the server copy doesn't resurrect them.
                val deletedVehicles = com.example.hassiwrapper.ui.vehicles.VehicleDetailFragment.locallyDeletedVehicleIds +
                    ServiceLocator.outboxService.pendingDeleteIds(com.example.hassiwrapper.services.OutboxService.Entity.VEHICLE).toSet()
                val entities = parsed.filter { it.vehicle_id !in deletedVehicles }
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
        }

        // Project-specific lookups
        fun logLookup(name: String, resp: retrofit2.Response<okhttp3.ResponseBody>, count: Int) {
            Log.d(TAG, "syncSMS lookup $name: HTTP ${resp.code()} → parsed $count")
            if (!resp.isSuccessful) Log.w(TAG, "syncSMS lookup $name error body: ${resp.errorBody()?.string()?.take(200)}")
        }

        syncSection("areas") {
            service.getAreas(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS areas raw(200): ${raw.take(200)}")
                parseAreaEntities(raw, projectId).also { list ->
                    logLookup("areas", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsAreaDao.deleteByProject(projectId); ServiceLocator.smsAreaDao.insertAll(list) }
                }
            }
        }
        syncSection("specs") {
            service.getSpecs(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS specs raw(200): ${raw.take(200)}")
                parseSpecEntities(raw, projectId).also { list ->
                    logLookup("specs", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSpecDao.deleteByProject(projectId); ServiceLocator.smsSpecDao.insertAll(list) }
                }
            }
        }
        syncSection("subcontractors") {
            service.getSubcontractors(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS subcontractors raw(200): ${raw.take(200)}")
                parseSubcontractorEntities(raw, projectId).also { list ->
                    logLookup("subcontractors", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSubcontractorDao.deleteByProject(projectId); ServiceLocator.smsSubcontractorDao.insertAll(list) }
                }
            }
        }

        syncSection("bore-sizes") {
            service.getBoreSizes(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS bore-sizes raw(300): ${raw.take(300)}")
                parseBoreSizes(raw).also { list ->
                    logLookup("bore-sizes", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsBoreSizeDao.deleteAll(); ServiceLocator.smsBoreSizeDao.insertAll(list) }
                }
            }
        }
        syncSection("iso-types") {
            service.getIsoTypes(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS iso-types raw(300): ${raw.take(300)}")
                parseIsoTypes(raw).also { list ->
                    logLookup("iso-types", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsIsoTypeDao.deleteAll(); ServiceLocator.smsIsoTypeDao.insertAll(list) }
                }
            }
        }
        syncSection("positions") {
            service.getPositions(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parsePositions(raw).also { list ->
                    logLookup("positions", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsPositionDao.deleteAll(); ServiceLocator.smsPositionDao.insertAll(list) }
                }
            }
        }
        syncSection("sub-positions") {
            service.getSubPositions(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseSubPositions(raw, projectId).also { list ->
                    logLookup("sub-positions", r, list.size)
                    ServiceLocator.smsSubPositionDao.deleteByProject(projectId)
                    if (list.isNotEmpty()) ServiceLocator.smsSubPositionDao.insertAll(list)
                }
            }
        }
        syncSection("spool-statuses") {
            service.getSpoolStatuses(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS spool-statuses raw(300): ${raw.take(300)}")
                parseSpoolStatuses(raw).also { list ->
                    logLookup("spool-statuses", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSpoolStatusDao.deleteAll(); ServiceLocator.smsSpoolStatusDao.insertAll(list) }
                }
            }
        }
        syncSection("units") {
            service.getUnits(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseUnits(raw).also { list ->
                    logLookup("units", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsUnitDao.deleteAll(); ServiceLocator.smsUnitDao.insertAll(list) }
                }
            }
        }
        syncSection("incomplete-statuses") {
            service.getIncompleteStatuses(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseIncompleteStatuses(raw).also { list ->
                    logLookup("incomplete-statuses", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsIncompleteStatusDao.deleteAll(); ServiceLocator.smsIncompleteStatusDao.insertAll(list) }
                }
            }
        }
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

    private fun parseSubPositions(raw: String, projectId: Int): List<SmsSubPositionEntity> =
        smsJsonArray(raw).mapNotNull { el ->
            if (!el.isJsonObject) null else el.asJsonObject.let { o ->
                val id = o.jLong("subPositionId", "sub_position_id", "id") ?: return@mapNotNull null
                val positionId = o.jInt("positionId", "position_id") ?: return@mapNotNull null
                SmsSubPositionEntity(id, projectId, positionId,
                    o.jLong("parentSubId", "parent_sub_id"),
                    o.jStr("code").orEmpty(), o.jStr("name").orEmpty(),
                    o.jStr("fullPath", "full_path").orEmpty(), o.jInt("level") ?: 0,
                    o.jBool("isActive", "is_active") ?: true,
                    o.jStr("createdAt", "created_at").orEmpty(), o.jStr("createdBy", "created_by").orEmpty(),
                    o.jStr("updatedAt", "updated_at"), o.jStr("updatedBy", "updated_by"))
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

    /** Called from SettingsFragment to activate/deactivate screen pinning (kiosk mode). */
    fun setKioskMode(enabled: Boolean) {
        kioskModeEnabled = enabled
        if (enabled) {
            try { startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask failed: ${e.message}") }
        } else {
            try { stopLockTask() } catch (e: Exception) { Log.w(TAG, "stopLockTask failed: ${e.message}") }
        }
    }

    /** Called from SettingsFragment after a profile change. */
    fun refreshProfileMenu() {
        val navView = findViewById<NavigationView>(R.id.navView)
        applyProfileMenuVisibility(navView)
        applyDrawerAccess()

        val role = ProfileManager.currentUserRole()
        val currentDest = navController.currentDestination?.id

        if (role == ProfileManager.UserRole.GUEST) {
            val allowedInGuest = setOf(R.id.homeFragment, R.id.qrScannerFragment, R.id.syncFragment, R.id.settingsFragment)
            if (currentDest != null && currentDest !in allowedInGuest) {
                navController.navigate(R.id.homeFragment)
            }
        }
    }
}

// ── SMS JSON parse helpers ────────────────────────────────────────────────────
// Canonical entity parsers — single copy shared by MainActivity.syncSmsData(),
// HomeFragment, CreateSpoolFragment, PackingListsFragment, PackingListDetailFragment
// and VehiclesFragment. Used to be reimplemented in each of those (see CLAUDE.md);
// a fix here now reaches every screen instead of just one.

internal fun parseSpoolEntities(raw: String, projectId: Int, packingListId: Long? = null): List<SmsSpoolEntity> {
    val gson = Gson()
    return try {
        val el = JsonParser.parseString(raw)
        val array = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> listOf("data", "items", "results", "spools").asSequence()
                .mapNotNull { el.asJsonObject.get(it) }.firstOrNull { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return emptyList()
        array.mapIndexedNotNull { idx, element ->
            if (!element.isJsonObject) return@mapIndexedNotNull null
            try {
                val dto = gson.fromJson(element, SpoolDto::class.java)
                val entity = dto.toEntity(defaultPackingListId = packingListId)
                if (entity.spool_id == 0L) return@mapIndexedNotNull null
                // When spoolId is a non-numeric string (no true PK from API), mix in the
                // array index so identical-looking records get distinct primary keys.
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

internal fun parsePackingListEntities(raw: String, projectId: Int): List<SmsPackingListEntity> {
    val gson = Gson()
    return try {
        val el = JsonParser.parseString(raw)
        val array = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> listOf("data", "items", "results", "packingLists", "packing_lists").asSequence()
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

internal fun parseVehicleEntities(raw: String, projectId: Int): List<SmsVehicleEntity> {
    val gson = Gson()
    return try {
        val el = JsonParser.parseString(raw)
        val array = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> listOf("data", "items", "results", "vehicles").asSequence()
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

/**
 * The only valid terminal locations. `device_location` must be one of these — it gates the
 * Send/Receive flows and resolves the terminal's position. WORKSHOP is the default; legacy
 * free-form values (e.g. "Test", or a JWT company claim like "TR") are rejected.
 */
val VALID_DEVICE_LOCATIONS = listOf("WORKSHOP", "LAYDOWN", "SITE")
const val DEFAULT_DEVICE_LOCATION = "WORKSHOP"

/** Upper-cases and validates a raw location; returns null if it isn't one of [VALID_DEVICE_LOCATIONS]. */
fun normalizeDeviceLocation(raw: String?): String? =
    raw?.trim()?.uppercase()?.takeIf { it in VALID_DEVICE_LOCATIONS }
