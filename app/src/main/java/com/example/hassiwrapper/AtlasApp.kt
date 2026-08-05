package com.example.hassiwrapper

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.AtlasDatabase
import com.example.hassiwrapper.services.DbIntegrityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AtlasApp : Application() {

    lateinit var database: AtlasDatabase
        private set

    /** Set when [AtlasDatabase.getInstance] throws (e.g. a broken Room migration) instead of
     *  crashing the whole process on every launch with no recovery path on a kiosk device. */
    var databaseInitError: Throwable? = null
        private set

    /** App-lifetime scope for fire-and-forget background work (e.g. the startup integrity
     *  check) that has no natural Activity/Fragment lifecycle to attach to. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            database = AtlasDatabase.getInstance(this)
        } catch (e: Throwable) {
            Log.e("AtlasApp", "Database init failed", e)
            databaseInitError = e
        }
        if (databaseInitError == null) {
            appScope.launch {
                DbIntegrityChecker.checkAndRecord(database, ConfigRepository(database.configDao()))
            }
        }
        ProfileManager.init(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    companion object {
        lateinit var instance: AtlasApp
            private set
    }
}
