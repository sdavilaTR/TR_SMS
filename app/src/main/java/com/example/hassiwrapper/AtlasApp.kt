package com.example.hassiwrapper

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.hassiwrapper.data.db.AtlasDatabase

class AtlasApp : Application() {

    lateinit var database: AtlasDatabase
        private set

    /** Set when [AtlasDatabase.getInstance] throws (e.g. a broken Room migration) instead of
     *  crashing the whole process on every launch with no recovery path on a kiosk device. */
    var databaseInitError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            database = AtlasDatabase.getInstance(this)
        } catch (e: Throwable) {
            Log.e("AtlasApp", "Database init failed", e)
            databaseInitError = e
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
