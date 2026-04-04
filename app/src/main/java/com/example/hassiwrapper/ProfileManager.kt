package com.example.hassiwrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app profiles: USER, ADMIN, DEV.
 *
 * - USER:  minimal UI — scanner + settings only.
 * - ADMIN: full menu access. Requires access code.
 * - DEV:   full menu + switches API to web-atlas-dev. Requires access code.
 *          Switching to DEV resets the local database.
 *
 * Access code (hardcoded): ATLAS2026
 */
object ProfileManager {

    enum class Profile { USER, ADMIN, DEV }

    // Hardcoded access code for ADMIN and DEV profiles
    const val ACCESS_CODE = "ATLAS2026"

    private const val PREFS_NAME = "atlas_profile_prefs"
    private const val KEY_PROFILE = "current_profile"

    const val API_URL_PRE = "https://web-atlas-api-pre.azurewebsites.net"
    const val API_URL_DEV = "https://web-atlas-api-dev.azurewebsites.net"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun currentProfile(): Profile {
        val name = prefs.getString(KEY_PROFILE, Profile.USER.name) ?: Profile.USER.name
        return try { Profile.valueOf(name) } catch (_: Exception) { Profile.USER }
    }

    fun setProfile(profile: Profile) {
        prefs.edit().putString(KEY_PROFILE, profile.name).apply()
    }

    fun validateAccessCode(code: String): Boolean = code == ACCESS_CODE

    fun getApiUrl(): String {
        return if (currentProfile() == Profile.DEV) API_URL_DEV else API_URL_PRE
    }
}
