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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var dataWedgeManager: DataWedgeManager
        private set

    private lateinit var navController: NavController

    // Holds a pending update if the user needs to grant install-unknown-apps permission first
    private var pendingUpdate: UpdateInfo? = null

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
                R.id.attendanceFragment,
                R.id.syncFragment,
                R.id.workersFragment,
                R.id.settingsFragment -> {
                    navController.navigate(item.itemId)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

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
                ServiceLocator.syncService.fullSync()
            } catch (_: Exception) { }
        }

        lifecycleScope.launch {
            val update = UpdateChecker.checkForUpdate(BuildConfig.BUILD_TAG)
            if (update != null) {
                withContext(Dispatchers.Main) { showUpdateDialog(update) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dataWedgeManager.register()

        // If the user just came back from granting the install-unknown-apps permission, proceed
        val pending = pendingUpdate
        if (pending != null && canInstallPackages()) {
            pendingUpdate = null
            UpdateInstaller.downloadAndInstall(this, pending)
        }
    }

    override fun onPause() {
        super.onPause()
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
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
        }
    }
}
