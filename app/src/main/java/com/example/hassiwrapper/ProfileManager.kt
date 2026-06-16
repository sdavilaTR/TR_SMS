package com.example.hassiwrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages two independent axes of configuration:
 *
 * - ApiEnvironment: which backend to connect to (PRO / PRE / DEV)
 * - UserRole: what permissions / menu items the local user sees (GUEST / ADMIN / DEV)
 *
 * Switching ApiEnvironment to a different URL resets the local database.
 * Switching UserRole never resets the database.
 *
 * Access code to unlock non-GUEST role or non-PRO environment: ATLAS2026
 */
object ProfileManager {

    enum class ApiEnvironment { PRO, PRE, DEV }
    enum class UserRole { GUEST, ADMIN, DEV }

    /** Kept for backward compatibility — callers that haven't been migrated still compile. */
    enum class Profile { USER, HSE, ADMIN, PRE, DEV }

    const val ACCESS_CODE = "ATLAS2026"

    private const val PREFS_NAME    = "atlas_profile_prefs"
    private const val KEY_PROFILE   = "current_profile"   // legacy
    private const val KEY_API_ENV   = "api_environment"
    private const val KEY_USER_ROLE = "user_role"

    const val API_URL_PRE = "https://web-atlas-api-pre.azurewebsites.net"
    const val API_URL_DEV = "https://web-atlas-api-dev.azurewebsites.net"
    const val API_URL_PRO = "https://atlas.tecnicasreunidas.es"
    const val PRO_PATH_PREFIX = "/api"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyProfile()
    }

    private fun migrateLegacyProfile() {
        if (prefs.contains(KEY_API_ENV)) return
        val legacyName = prefs.getString(KEY_PROFILE, null) ?: return
        val (env, role) = when (try { Profile.valueOf(legacyName) } catch (_: Exception) { return }) {
            Profile.USER  -> ApiEnvironment.PRO to UserRole.GUEST
            Profile.HSE   -> ApiEnvironment.PRO to UserRole.ADMIN
            Profile.ADMIN -> ApiEnvironment.PRO to UserRole.ADMIN
            Profile.PRE   -> ApiEnvironment.PRE to UserRole.ADMIN
            Profile.DEV   -> ApiEnvironment.DEV to UserRole.DEV
        }
        prefs.edit().putString(KEY_API_ENV, env.name).putString(KEY_USER_ROLE, role.name).apply()
    }

    // ── ApiEnvironment ────────────────────────────────────────────────────

    fun currentApiEnvironment(): ApiEnvironment {
        val name = prefs.getString(KEY_API_ENV, ApiEnvironment.PRO.name) ?: ApiEnvironment.PRO.name
        return try { ApiEnvironment.valueOf(name) } catch (_: Exception) { ApiEnvironment.PRO }
    }

    fun setApiEnvironment(env: ApiEnvironment) {
        prefs.edit().putString(KEY_API_ENV, env.name).apply()
    }

    fun apiUrlForEnv(env: ApiEnvironment): String = when (env) {
        ApiEnvironment.PRO -> API_URL_PRO
        ApiEnvironment.PRE -> API_URL_PRE
        ApiEnvironment.DEV -> API_URL_DEV
    }

    // ── UserRole ──────────────────────────────────────────────────────────

    fun currentUserRole(): UserRole {
        val name = prefs.getString(KEY_USER_ROLE, UserRole.GUEST.name) ?: UserRole.GUEST.name
        return try { UserRole.valueOf(name) } catch (_: Exception) { UserRole.GUEST }
    }

    fun setUserRole(role: UserRole) {
        prefs.edit().putString(KEY_USER_ROLE, role.name).apply()
    }

    // ── Shared ────────────────────────────────────────────────────────────

    fun validateAccessCode(code: String): Boolean = code == ACCESS_CODE

    fun getApiUrl(): String = apiUrlForEnv(currentApiEnvironment())

    fun usesPublicProxy(): Boolean = currentApiEnvironment() == ApiEnvironment.PRO

    // ── Legacy compatibility ──────────────────────────────────────────────

    fun currentProfile(): Profile = when (currentApiEnvironment()) {
        ApiEnvironment.DEV -> Profile.DEV
        ApiEnvironment.PRE -> Profile.PRE
        ApiEnvironment.PRO -> when (currentUserRole()) {
            UserRole.GUEST -> Profile.USER
            UserRole.ADMIN, UserRole.DEV -> Profile.ADMIN
        }
    }

    fun setProfile(profile: Profile) {
        val (env, role) = when (profile) {
            Profile.USER  -> ApiEnvironment.PRO to UserRole.GUEST
            Profile.HSE   -> ApiEnvironment.PRO to UserRole.ADMIN
            Profile.ADMIN -> ApiEnvironment.PRO to UserRole.ADMIN
            Profile.PRE   -> ApiEnvironment.PRE to UserRole.ADMIN
            Profile.DEV   -> ApiEnvironment.DEV to UserRole.DEV
        }
        setApiEnvironment(env)
        setUserRole(role)
    }

    fun apiUrlFor(profile: Profile): String = when (profile) {
        Profile.DEV -> API_URL_DEV
        Profile.PRE -> API_URL_PRE
        Profile.USER, Profile.HSE, Profile.ADMIN -> API_URL_PRO
    }
}
