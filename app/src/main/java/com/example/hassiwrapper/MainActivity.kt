package com.example.hassiwrapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
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

    // Holds a pending update if the user needs to grant install-unknown-apps permission first
    private var pendingUpdate: UpdateInfo? = null
    private var autoSyncJob: Job? = null

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
                R.id.scannerFragment,
                R.id.passportFragment,
                R.id.packingListsFragment,
                R.id.attendanceFragment,
                R.id.syncFragment,
                R.id.workersFragment,
                R.id.vehiclesFragment,
                R.id.observationsGeneralFragment,
                R.id.inspectionsFragment,
                R.id.settingsFragment -> {
                    navController.navigate(item.itemId)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        applyProfileMenuVisibility(navView)

        navController.addOnDestinationChangedListener { _, dest, _ ->
            toolbar.title = dest.label
        }

        navView.getHeaderView(0)
            .findViewById<android.widget.TextView>(R.id.txtNavVersion)
            .text = "ATLAS Native ${BuildConfig.BUILD_TAG}"

        dataWedgeManager = ServiceLocator.dataWedgeManager()

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
        val isHse  = profile == ProfileManager.Profile.HSE
        val isFull = !isUser && !isHse  // ADMIN, PRE, DEV

        // Packing Lists (replaces old passport menu entry): visible for HSE, ADMIN, PRE, DEV
        menu.findItem(R.id.packingListsFragment)?.isVisible = !isUser

        // Attendance, Workers, Vehicles: only full profiles (ADMIN, PRE, DEV)
        menu.findItem(R.id.attendanceFragment)?.isVisible  = isFull
        menu.findItem(R.id.workersFragment)?.isVisible     = isFull
        menu.findItem(R.id.vehiclesFragment)?.isVisible    = isFull

        // Observations General + Inspections: HSE, ADMIN, PRE, DEV
        menu.findItem(R.id.observationsGeneralFragment)?.isVisible = !isUser
        menu.findItem(R.id.inspectionsFragment)?.isVisible         = !isUser

        // Home + Scanner + Sync + Settings always visible
    }

    private suspend fun syncSmsData() {
        val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
        val projectCode = ServiceLocator.projectDao.getById(projectId)?.project_code
        if (projectCode.isNullOrBlank()) {
            Log.w(TAG, "syncSmsData: no project code for id=$projectId")
            return
        }
        Log.d(TAG, "syncSmsData: fetching SMS data for $projectCode")
        try {
            val service = ServiceLocator.apiClient.getService()

            val spoolResp = service.getSpools(projectCode)
            if (spoolResp.isSuccessful) {
                val spoolRaw = spoolResp.body()?.string().orEmpty()
                Log.d(TAG, "syncSmsData spools raw(500): ${spoolRaw.take(500)}")
                val entities = parseSpoolEntities(spoolRaw, projectId)
                if (entities.isNotEmpty()) {
                    entities.take(2).forEach { e ->
                        Log.d(TAG, "spool entity: id=${e.spool_id} code=${e.spool_code} suf=${e.spool_suffix} line=${e.line_code} service=${e.service} train=${e.train} module=${e.module} area=${e.area_id} spec=${e.spec_id} unit=${e.unit_id} iso=${e.iso_type_id} sub=${e.subcontractor_id}")
                    }
                    ServiceLocator.smsSpoolDao.deleteByProject(projectId)
                    ServiceLocator.smsSpoolDao.insertAll(entities)
                    Log.d(TAG, "syncSmsData: inserted ${entities.size} spools")
                }
            }

            val plResp = service.getPackingLists(projectCode)
            if (plResp.isSuccessful) {
                val raw = plResp.body()?.string().orEmpty()
                val entities = parsePackingListEntities(raw, projectId)
                if (entities.isNotEmpty()) {
                    ServiceLocator.smsPackingListDao.deleteByProject(projectId)
                    ServiceLocator.smsPackingListDao.insertAll(entities)
                    Log.d(TAG, "syncSmsData: inserted ${entities.size} packing lists")
                }
            }

            val vehicleResp = service.getVehicles(projectCode)
            if (vehicleResp.isSuccessful) {
                val entities = parseVehicleEntities(vehicleResp.body()?.string().orEmpty(), projectId)
                if (entities.isNotEmpty()) {
                    ServiceLocator.smsVehicleDao.deleteByProject(projectId)
                    ServiceLocator.smsVehicleDao.insertAll(entities)
                    Log.d(TAG, "syncSmsData: inserted ${entities.size} vehicles")
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

            service.getBoreSizes(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS bore-sizes raw(300): ${raw.take(300)}")
                parseBoreSizes(raw).also { list ->
                    logLookup("bore-sizes", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsBoreSizeDao.deleteAll(); ServiceLocator.smsBoreSizeDao.insertAll(list) }
                }
            }
            service.getIsoTypes(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS iso-types raw(300): ${raw.take(300)}")
                parseIsoTypes(raw).also { list ->
                    logLookup("iso-types", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsIsoTypeDao.deleteAll(); ServiceLocator.smsIsoTypeDao.insertAll(list) }
                }
            }
            service.getPositions(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parsePositions(raw).also { list ->
                    logLookup("positions", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsPositionDao.deleteAll(); ServiceLocator.smsPositionDao.insertAll(list) }
                }
            }
            service.getSpoolStatuses(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                Log.d(TAG, "syncSMS spool-statuses raw(300): ${raw.take(300)}")
                parseSpoolStatuses(raw).also { list ->
                    logLookup("spool-statuses", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsSpoolStatusDao.deleteAll(); ServiceLocator.smsSpoolStatusDao.insertAll(list) }
                }
            }
            service.getUnits(projectCode).let { r ->
                val raw = if (r.isSuccessful) r.body()?.string().orEmpty() else ""
                parseUnits(raw).also { list ->
                    logLookup("units", r, list.size)
                    if (list.isNotEmpty()) { ServiceLocator.smsUnitDao.deleteAll(); ServiceLocator.smsUnitDao.insertAll(list) }
                }
            }
            service.getIncompleteStatuses(projectCode).let { r ->
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

    /** Called from SettingsFragment after a profile change. */
    fun refreshProfileMenu() {
        val navView = findViewById<NavigationView>(R.id.navView)
        applyProfileMenuVisibility(navView)

        val profile = ProfileManager.currentProfile()
        val currentDest = navController.currentDestination?.id

        // If USER and currently on a hidden fragment, navigate to scanner
        if (profile == ProfileManager.Profile.USER) {
            val allowedInUser = setOf(R.id.homeFragment, R.id.scannerFragment, R.id.syncFragment, R.id.settingsFragment)
            if (currentDest != null && currentDest !in allowedInUser) {
                navController.navigate(R.id.scannerFragment)
            }
        }
        // If HSE and currently on an ADMIN-only fragment, navigate to scanner
        if (profile == ProfileManager.Profile.HSE) {
            val allowedInHse = setOf(
                R.id.homeFragment, R.id.scannerFragment, R.id.syncFragment, R.id.settingsFragment,
                R.id.passportFragment, R.id.packingListsFragment, R.id.observationsGeneralFragment, R.id.inspectionsFragment,
                R.id.observationFragment
            )
            if (currentDest != null && currentDest !in allowedInHse) {
                navController.navigate(R.id.scannerFragment)
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
