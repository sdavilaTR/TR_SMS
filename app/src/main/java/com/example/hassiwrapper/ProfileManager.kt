package com.example.hassiwrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app profiles: USER, ADMIN, PRE, DEV.
 *
 * - USER:  minimal UI — home, scanner, sync + settings. Uses production API
 *          (atlas.tecnicasreunidas.es) through the public reverse proxy.
 * - ADMIN: full menu access, also against production. Requires access code.
 * - PRE:   full menu against the PRE Azure environment. Requires access code.
 *          Switching environment resets the local database.
 * - DEV:   full menu against the DEV Azure environment. Requires access code.
 *          Switching environment resets the local database.
 *
 * Access code (hardcoded): ATLAS2026
 */
object ProfileManager {

    enum class Profile { USER, ADMIN, PRE, DEV }

    // Hardcoded access code for ADMIN, PRE and DEV profiles
    const val ACCESS_CODE = "ATLAS2026"

    private const val PREFS_NAME = "atlas_profile_prefs"
    private const val KEY_PROFILE = "current_profile"

    const val API_URL_PRE = "https://web-atlas-api-pre.azurewebsites.net"
    const val API_URL_DEV = "https://web-atlas-api-dev.azurewebsites.net"
    const val API_URL_PRO = "https://atlas.tecnicasreunidas.es"
    /** Path prefix injected by the public reverse proxy in front of PRO. */
    const val PRO_PATH_PREFIX = "/api"

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

    fun getApiUrl(): String = apiUrlFor(currentProfile())

    fun apiUrlFor(profile: Profile): String = when (profile) {
        Profile.DEV -> API_URL_DEV
        Profile.PRE -> API_URL_PRE
        Profile.USER, Profile.ADMIN -> API_URL_PRO
    }

    /** True when the active profile talks to the public reverse proxy that prepends /api. */
    fun usesPublicProxy(): Boolean = getApiUrl() == API_URL_PRO
}
