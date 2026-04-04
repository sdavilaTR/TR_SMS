package com.example.hassiwrapper

import android.app.Application
import android.content.Context
import com.example.hassiwrapper.data.db.AtlasDatabase

class AtlasApp : Application() {

    lateinit var database: AtlasDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AtlasDatabase.getInstance(this)
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
