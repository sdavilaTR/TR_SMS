package com.example.hassiwrapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
import com.example.hassiwrapper.services.GpsHelper
import com.example.hassiwrapper.services.PositionHelper
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class MainActivity : AppCompatActivity() {

    lateinit var dataWedgeManager: DataWedgeManager
        private set

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var etGlobalWedge: EditText
    private lateinit var syncProgressIndicator: android.widget.ProgressBar
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
    private val smsSyncMutex = Mutex()
    private val _isSmsSyncInProgress = MutableStateFlow(false)

    /** True while a full/delta SMS sync (auto-loop or manual) is in flight — a spool scan
     *  resolving to "not registered" during this window may just not be downloaded yet. */
    internal val isSmsSyncInProgress: Boolean get() = _isSmsSyncInProgress.value

    /** True while either SyncService.fullSync() or the SMS-specific sync is in flight — the
     *  two run sequentially, not under a shared lock, so both must be checked: a spool scan
     *  during fullSync() alone (before syncSmsData() starts) is still mid-sync. Single source
     *  of truth for every "spool may not be downloaded yet" message; the toolbar spinner
     *  collects the two backing StateFlows directly instead of polling this property. */
    internal val isAnySyncInProgress: Boolean get() = ServiceLocator.syncService.isSyncing.value || isSmsSyncInProgress

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var soundPool: SoundPool? = null
    private var soundSuccess = 0
    private var soundError = 0

    // Global spool scans (handleGlobalScan) capture a best-effort GPS fix from any screen;
    // request location permission once up front so it's not silently unavailable for
    // devices that never visit a screen that already asks (QR Scanner, Send/Receive PL).
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* best-effort — GpsHelper silently skips capture if denied */ }

    private val requestQrCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchQrScanner() }

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
        syncProgressIndicator = findViewById(R.id.syncProgressIndicator)

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

        val navHeader = navView.getHeaderView(0)
        navHeader.findViewById<android.widget.TextView>(R.id.txtNavVersion)
            .text = "ATLAS Native ${BuildConfig.BUILD_TAG}"
        val isDevOrAdmin = ProfileManager.currentUserRole().let {
            it == ProfileManager.UserRole.ADMIN || it == ProfileManager.UserRole.DEV
        }
        navHeader.findViewById<android.view.View>(R.id.navTechOrb)
            ?.visibility = if (isDevOrAdmin) android.view.View.VISIBLE else android.view.View.GONE
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
                        dest != R.id.newPackingListFragment &&
                        dest != R.id.newIncidentFragment) {
                        handleGlobalScan(code)
                    }
                }
            }
        }

        // Observe both sync locks (SyncService.fullSync + this activity's SMS sync) so the
        // toolbar spinner reflects any in-flight sync regardless of what triggered it
        // (auto-loop, initial sync, or a manual sync from SyncFragment/CreateSpoolFragment).
        // combine() only emits on an actual state change, so this is push-driven, not polled.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(ServiceLocator.syncService.isSyncing, _isSmsSyncInProgress) { full, sms -> full || sms }
                    .collect { syncing ->
                        syncProgressIndicator.visibility =
                            if (syncing) android.view.View.VISIBLE else android.view.View.GONE
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

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        findViewById<FloatingActionButton>(R.id.fabQrScan).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) launchQrScanner()
            else requestQrCameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        val intent = Intent(this, CustomScannerActivity::class.java)
            .putExtra(CustomScannerActivity.EXTRA_FRONT_CAMERA, false)
        qrScanLauncher.launch(intent)
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

    // Debug-only hook so test scripts can write config values through the live process —
    // the Room DB can't be edited externally while the app holds it open (device-admin
    // keeps the process alive, so a pushed DB file is shadowed by the open connection).
    // Usage: adb shell am broadcast -a com.tr.atlas.trac.DEBUG_SET_CONFIG --es key <k> --es value <v>
    // Omitting --es value removes the key.
    private val debugConfigReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getStringExtra("key") ?: return
            val value = intent.getStringExtra("value")
            lifecycleScope.launch {
                if (value == null) ServiceLocator.configRepo.remove(key)
                else ServiceLocator.configRepo.set(key, value)
                Log.i(TAG, "DEBUG_SET_CONFIG: $key = $value")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startAutoSync()
        registerNetworkCallback()
        dataWedgeManager.register()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this, debugConfigReceiver,
                IntentFilter("com.tr.atlas.trac.DEBUG_SET_CONFIG"),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
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
        if (BuildConfig.DEBUG) {
            try { unregisterReceiver(debugConfigReceiver) } catch (_: Exception) {}
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsData: section '$name' failed", e)
        }
    }

    /**
     * Skips the DB delete+insert if the raw JSON is identical to the last successful write
     * (CRC32 comparison). The HTTP fetch still happens — server-side ETag/304 support would
     * be needed to skip that too. Saves ~8 unnecessary Room write transactions per sync cycle
     * for lookup tables that rarely change (bore-sizes, iso-types, positions, statuses, etc.).
     */
    private suspend fun applyLookupIfChanged(cacheKey: String, raw: String, apply: suspend () -> Unit) {
        if (raw.isBlank()) return
        val crc = java.util.zip.CRC32().apply { update(raw.toByteArray()) }.value.toString()
        val storeKey = "lookup_crc_$cacheKey"
        if (ServiceLocator.configRepo.get(storeKey) == crc) {
            Log.d(TAG, "syncSMS lookup $cacheKey: unchanged (crc=$crc), skip DB write")
            return
        }
        apply()
        ServiceLocator.configRepo.set(storeKey, crc)
    }

    internal suspend fun syncSmsData(onProgress: ((String) -> Unit)? = null) {
        if (!smsSyncMutex.tryLock()) {
            Log.i(TAG, "syncSmsData: already in progress, skipping concurrent call")
            return
        }
        _isSmsSyncInProgress.value = true
        try {
        doSyncSmsData(onProgress)
        } finally {
            _isSmsSyncInProgress.value = false
            smsSyncMutex.unlock()
        }
    }

    private suspend fun doSyncSmsData(onProgress: ((String) -> Unit)? = null) {
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
        Log.d(TAG, "syncSmsData: fetching SMS data for '$projectCode'")

        val service = try {
            ServiceLocator.apiClient.getService()
        } catch (e: Exception) {
            Log.e(TAG, "syncSmsData: could not obtain API service", e)
            return
        }
        val largeService = ServiceLocator.apiClient.getServiceForLargeSync()

        // All sections write to independent tables — launch them all in parallel.
        // coroutineScope suspends until every child completes (or the first uncaught
        // exception cancels all siblings; syncSection swallows its own exceptions so
        // that case can only arise from a programming error, not a network failure).
        coroutineScope {
            fun logLookup(name: String, resp: retrofit2.Response<okhttp3.ResponseBody>, count: Int) {
                Log.d(TAG, "syncSMS lookup $name: HTTP ${resp.code()} → parsed $count")
                if (!resp.isSuccessful) Log.w(TAG, "syncSMS lookup $name error body: ${resp.errorBody()?.string()?.take(200)}")
            }

            launch(Dispatchers.Default) {
                syncSection("spools") {
                    val pageSize = 5000
                    // Delta sync: send the timestamp of the last successful spool sync so the
                    // server can return only rows changed since then. Gated on a per-project
                    // config flag so it stays off until the backend confirms support for
                    // ?updatedSince= on GET /spools. Full sync behaviour is the safe fallback.
                    val lastSyncKey  = "sms_spools_last_sync_$projectId"
                    val deltaEnabled = ServiceLocator.configRepo.get("sms_delta_sync_enabled") == "true"
                    val updatedSince = if (deltaEnabled) ServiceLocator.configRepo.get(lastSyncKey) else null
                    val isFullSync   = updatedSince == null
                    val syncStarted  = Instant.now().minusSeconds(30).toString()

                    // Each page gets a few retries with backoff before we give up on it — large
                    // projects (MERAM, JAFURAH) need dozens of sequential page requests, and a
                    // single flaky page used to abort the whole sync (see fetchOk below).
                    suspend fun fetchPageWithRetry(page: Int): retrofit2.Response<okhttp3.ResponseBody>? {
                        repeat(3) { attempt ->
                            try {
                                // Full/delta sync feeds the local sms_spool mirror that scan-lookup, search,
                                // and packing-list pickers all depend on — it must stay unfiltered (scannedOnly
                                // omitted) so any physical tag scan resolves. Every row already carries its own
                                // `scanned` flag (SpoolDto.scanned), so HomeFragment's KPI tiles and
                                // CreateSpoolFragment's Inventario picker filter scanned=1 locally in SQL
                                // (see SPOOL_LIST_FILTER/countScannedByProject) — no separate filtered fetch exists.
                                val resp = largeService.getSpools(projectCode, page, pageSize, updatedSince)
                                if (resp.isSuccessful) return resp
                                Log.w(TAG, "syncSmsData spools page=$page HTTP ${resp.code()} (attempt ${attempt + 1}/3)")
                            } catch (e: Exception) {
                                Log.w(TAG, "syncSmsData spools page=$page exception (attempt ${attempt + 1}/3): ${e.message}")
                            }
                            if (attempt < 2) delay(1000L * (attempt + 1))
                        }
                        return null
                    }

                    val allEntities = mutableListOf<SmsSpoolEntity>()
                    var page = 1
                    var fetchOk = false
                    while (true) {
                        val spoolResp = fetchPageWithRetry(page) ?: break
                        val spoolRaw = spoolResp.body()?.string().orEmpty()
                        if (page == 1) Log.d(TAG, "syncSmsData spools raw(500): ${spoolRaw.take(500)}")
                        val pageEntities = parseSpoolEntities(spoolRaw, projectId)
                        allEntities += pageEntities
                        Log.d(TAG, "syncSmsData spools page=$page got=${pageEntities.size} total=${allEntities.size} fullSync=$isFullSync")
                        if (page > 1) {
                            val msg = getString(R.string.sync_sms_spools_page, page, allEntities.size)
                            withContext(Dispatchers.Main) { onProgress?.invoke(msg) }
                        }
                        if (pageEntities.size < pageSize) { fetchOk = true; break }
                        page++
                    }

                    // Persist whatever pages we did get, even on partial failure: upserting the
                    // fetched rows still advances the KPI/local data, and skipping the wipe below
                    // when incomplete means we never delete spools we haven't re-fetched yet
                    // (a prior delta-purge bug wiped 139k MERAM spools this way — see git history).
                    if (allEntities.isNotEmpty() || fetchOk) {
                        val deleted = com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet.locallyDeletedSpoolIds +
                            ServiceLocator.outboxService.pendingDeleteIds(com.example.hassiwrapper.services.OutboxService.Entity.SPOOL).toSet()
                        val toInsert = allEntities.filter { it.is_active && it.spool_id !in deleted }
                        // Delta responses include rows deactivated server-side (is_active=0) so
                        // offline clients can purge them; a full sync handles this via the
                        // wipe-and-reinsert below instead.
                        if (!isFullSync) {
                            val deactivatedIds = allEntities.filter { !it.is_active }.map { it.spool_id }
                            if (deactivatedIds.isNotEmpty()) {
                                val localCount = ServiceLocator.smsSpoolDao.countByProject(projectId)
                                val ratio = if (localCount > 0) deactivatedIds.size.toDouble() / localCount else 0.0
                                // A delta batch legitimately deactivating a large slice of a project's
                                // spools is implausible — a prior backend bug returned the ENTIRE
                                // project as is_active=0 in one delta call and this client purged all
                                // 139k MERAM spools before anyone noticed. Skip the purge and clear the
                                // delta cursor so the next cycle falls back to a full sync (safe,
                                // wipe-and-reinsert path) instead of trusting a suspicious payload.
                                if (localCount > 200 && ratio > 0.2) {
                                    Log.e(TAG, "syncSmsData: delta anomaly — ${deactivatedIds.size}/$localCount spools marked inactive (${(ratio * 100).toInt()}%), skipping purge, forcing full sync next cycle")
                                    ServiceLocator.configRepo.remove(lastSyncKey)
                                } else {
                                    deactivatedIds.chunked(800).forEach { ServiceLocator.smsSpoolDao.deleteByIds(it) }
                                }
                            }
                        }
                        // Preserve locally-set fields that the /spools endpoint doesn't return
                        val localSpools = ServiceLocator.smsSpoolDao.getByProjectIgnoreActive(projectId)
                            .associateBy { it.spool_id }
                        // in_transit is purely local (set by Send, cleared by Receive) — the
                        // backend never returns it, so it's never taken from the server response.
                        // Spools that are SENT but not yet RECEIVED locally stay in_transit=true
                        // regardless of upload status, until a local RECEIVE transfer confirms arrival.
                        val sentNotReceivedIds = ServiceLocator.smsTransferDao.getSpoolIdsInSentNotReceived().toSet()
                        val merged = toInsert.map { s ->
                            val local = localSpools[s.spool_id] ?: return@map s
                            val keepLocal = !local.synced
                            s.copy(
                                in_transit = if (s.spool_id in sentNotReceivedIds) true else local.in_transit,
                                zone            = if (keepLocal) local.zone else (s.zone ?: local.zone),
                                // QR-scan is still the only source on projects where the backend
                                // hasn't backfilled ISO_rev_number yet — never let a null/blank
                                // server value overwrite a revision captured locally by a scan.
                                revision        = if (keepLocal) local.revision else (s.revision ?: local.revision),
                                packing_list_id = if (keepLocal) local.packing_list_id else (s.packing_list_id ?: local.packing_list_id),
                                position_id     = if (keepLocal) local.position_id else (s.position_id ?: local.position_id),
                                sub_position_id = if (keepLocal) local.sub_position_id else (s.sub_position_id ?: local.sub_position_id),
                                // Carry synced=false so the local override persists across multiple sync
                                // cycles until the server returns the updated value (e.g. after status-flags
                                // upload is processed server-side and reflected in GET /spools).
                                synced          = if (keepLocal) false else s.synced
                            )
                        }

                        val newCount = merged.count { it.spool_id !in localSpools }
                        val updatedCount = merged.count { it.spool_id in localSpools }
                        if (isFullSync && fetchOk) {
                            // Full sync completed all pages: safe to wipe stale server-side rows.
                            // Never wipe on a partial fetch — we'd delete spools beyond the last
                            // page we reached, with no fresh data to replace them.
                            ServiceLocator.smsSpoolDao.deleteSyncedByProject(projectId)
                        }
                        if (merged.isNotEmpty()) {
                            ServiceLocator.smsSpoolDao.insertAll(merged)
                        }
                        ServiceLocator.smsSpoolDao.deleteInactive()
                        if (fetchOk) {
                            // Only advance the delta cursor once we've actually caught up —
                            // a partial fetch must retry from page 1 next time, not skip ahead.
                            ServiceLocator.configRepo.set(lastSyncKey, syncStarted)
                        }
                        val status = if (!fetchOk) "PARTIAL" else if (isFullSync) "full" else "delta"
                        Log.d(TAG, "syncSmsData: $status sync — ${merged.size} spools written (page=$page)")
                        val spoolMsg = if (fetchOk) getString(R.string.sync_sms_spools_ok, newCount, updatedCount, merged.size)
                                       else getString(R.string.sync_sms_spools_partial, page, merged.size)
                        withContext(Dispatchers.Main) { onProgress?.invoke(spoolMsg) }
                    } else {
                        Log.w(TAG, "syncSmsData: spools page 1 fetch failed after retries, skipping DB write")
                    }
                }
            }

            launch(Dispatchers.Default) {
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
                            val keepLocal = !local.synced
                            pl.copy(
                                ready_to_send = local.ready_to_send,
                                vehicle_id    = pl.vehicle_id ?: local.vehicle_id,
                                vehicle_plate = pl.vehicle_plate ?: local.vehicle_plate,
                                position_id   = if (keepLocal) local.position_id else (pl.position_id ?: local.position_id),
                                position      = if (keepLocal) local.position else (pl.position ?: local.position),
                                synced        = if (keepLocal) false else pl.synced
                            )
                        }
                        val newPLCount = mergedPLs.count { it.packing_list_id !in localPLs }
                        val updatedPLCount = mergedPLs.count { it.packing_list_id in localPLs }
                        ServiceLocator.smsPackingListDao.deleteSyncedByProject(projectId)
                        if (mergedPLs.isNotEmpty()) ServiceLocator.smsPackingListDao.insertAll(mergedPLs)
                        Log.d(TAG, "syncSmsData: inserted ${mergedPLs.size} packing lists (${entities.size - mergedPLs.size} inactive/deleted skipped)")
                        val plMsg = getString(R.string.sync_sms_pl_ok, newPLCount, updatedPLCount, mergedPLs.size)
                        withContext(Dispatchers.Main) { onProgress?.invoke(plMsg) }
                    }
                    ServiceLocator.smsPackingListDao.deleteInactive()
                }
            }

            launch(Dispatchers.Default) {
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
                            val vehMsg = getString(R.string.sync_sms_vehicles_ok, mergedVehicles.size)
                            withContext(Dispatchers.Main) { onProgress?.invoke(vehMsg) }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("areas") {
                    service.getAreas(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseAreaEntities(raw, projectId).also { list ->
                            logLookup("areas", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("areas_$projectId", raw) {
                                ServiceLocator.smsAreaDao.deleteByProject(projectId); ServiceLocator.smsAreaDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("specs") {
                    service.getSpecs(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseSpecEntities(raw, projectId).also { list ->
                            logLookup("specs", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("specs_$projectId", raw) {
                                ServiceLocator.smsSpecDao.deleteByProject(projectId); ServiceLocator.smsSpecDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("subcontractors") {
                    service.getSubcontractors(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseSubcontractorEntities(raw, projectId).also { list ->
                            logLookup("subcontractors", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("subcontractors_$projectId", raw) {
                                ServiceLocator.smsSubcontractorDao.deleteByProject(projectId); ServiceLocator.smsSubcontractorDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("bore-sizes") {
                    service.getBoreSizes(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseBoreSizes(raw).also { list ->
                            logLookup("bore-sizes", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("bore-sizes", raw) {
                                ServiceLocator.smsBoreSizeDao.deleteAll(); ServiceLocator.smsBoreSizeDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("iso-types") {
                    service.getIsoTypes(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseIsoTypes(raw).also { list ->
                            logLookup("iso-types", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("iso-types", raw) {
                                ServiceLocator.smsIsoTypeDao.deleteAll(); ServiceLocator.smsIsoTypeDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("positions") {
                    service.getPositions(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parsePositions(raw).also { list ->
                            logLookup("positions", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("positions", raw) {
                                ServiceLocator.smsPositionDao.deleteAll(); ServiceLocator.smsPositionDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("sub-positions") {
                    service.getSubPositions(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseSubPositions(raw, projectId).also { list ->
                            logLookup("sub-positions", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("sub-positions_$projectId", raw) {
                                ServiceLocator.smsSubPositionDao.deleteByProject(projectId); ServiceLocator.smsSubPositionDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("spool-statuses") {
                    service.getSpoolStatuses(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseSpoolStatuses(raw).also { list ->
                            logLookup("spool-statuses", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("spool-statuses", raw) {
                                ServiceLocator.smsSpoolStatusDao.deleteAll(); ServiceLocator.smsSpoolStatusDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("units") {
                    service.getUnits(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseUnits(raw).also { list ->
                            logLookup("units", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("units", raw) {
                                ServiceLocator.smsUnitDao.deleteAll(); ServiceLocator.smsUnitDao.insertAll(list)
                            }
                        }
                    }
                }
            }

            launch(Dispatchers.Default) {
                syncSection("incomplete-statuses") {
                    service.getIncompleteStatuses(projectCode).let { r ->
                        val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                        parseIncompleteStatuses(raw).also { list ->
                            logLookup("incomplete-statuses", r, list.size)
                            if (list.isNotEmpty()) applyLookupIfChanged("incomplete-statuses", raw) {
                                ServiceLocator.smsIncompleteStatusDao.deleteAll(); ServiceLocator.smsIncompleteStatusDao.insertAll(list)
                            }
                        }
                    }
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
        val code = raw.trim().trimStart('\uFEFF')
        Log.d(TAG, "handleGlobalScan: ${code.take(80)}")
        when (val result = parseQr(code)) {
            is QrResult.Spool -> {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val spools = ServiceLocator.smsSpoolDao.getByCode(projectId, result.spoolCode)
                val spool = if (result.spoolSuffix != null)
                    spools.find { it.spool_suffix == result.spoolSuffix } ?: spools.firstOrNull()
                else
                    spools.firstOrNull()
                if (spool != null) {
                    showScanRegisteredDialog(getString(R.string.scan_result_spool_registered, spool.displayCode))
                    GpsHelper.captureAndSaveSpoolLocation(this@MainActivity, spool.spool_id)
                    PositionHelper.applyTerminalPosition(spool.spool_id)
                    ServiceLocator.smsSpoolDao.backfillSitAndRevision(spool.spool_id, result.sitNumber, result.revision)
                } else {
                    val notFoundMsg = if (isAnySyncInProgress)
                        getString(R.string.scan_result_spool_not_registered_syncing)
                    else
                        getString(R.string.scan_result_spool_not_registered)
                    showScanNotRegisteredDialog(notFoundMsg) {
                        navController.navigate(R.id.action_global_newSpoolFragment,
                            Bundle().apply {
                                putString("prefillSpoolCode", result.spoolCode)
                                putString("prefillUnitCode", result.unitCode)
                                putString("prefillService", result.service)
                                putString("prefillLineCode", result.lineCode)
                                putString("prefillSitNumber", result.sitNumber)
                                putString("prefillRevision", result.revision)
                            })
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
