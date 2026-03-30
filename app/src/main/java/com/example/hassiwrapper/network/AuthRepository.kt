package com.example.hassiwrapper.network

import android.util.Base64
import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.network.dto.LoginRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Handles JWT storage and login — mirrors auth.js.
 * Token is stored in Room config table under key "user_token".
 */
class AuthRepository(private val configRepo: ConfigRepository) {

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Login with email + password against /v1/Auth/login.
     * Returns the JWT on success, null on failure.
     */
    suspend fun loginWithCredentials(
        email: String,
        password: String,
        apiService: AtlasApiService
    ): Result<String> {
        return try {
            val response = apiService.login(LoginRequest(email, password))
            if (!response.isSuccessful) {
                val msg = if (response.code() == 400) "Credenciales incorrectas."
                else "Error ${response.code()}."
                return Result.failure(Exception(msg))
            }
            var token = response.body() ?: return Result.failure(Exception("Respuesta vacía del servidor."))
            // Trim quotes if server returned a JSON string
            token = token.trim('"')

            // The server may return a JSON object with a "token" field
            try {
                val json = JSONObject(token)
                if (json.has("token")) {
                    token = json.getString("token")
                }
            } catch (_: Exception) {
                // Already a plain JWT string
            }

            configRepo.set("user_token", token)
            cachedToken = token

            // Extract company claim from JWT and persist as device_location.
            // ASP.NET Identity exposes it as "company" or the full schema URI.
            val company = extractJwtClaim(token, "company")
                ?: extractJwtClaim(token, "http://schemas.xmlsoap.org/claims/Company")
            if (!company.isNullOrBlank()) {
                configRepo.set("device_location", company)
            }

            Result.success(token)
        } catch (e: Exception) {
            Result.failure(Exception("No se pudo conectar con el servidor: ${e.message}"))
        }
    }

    suspend fun getToken(): String? {
        cachedToken?.let { return it }
        val stored = configRepo.get("user_token")
        cachedToken = stored
        return stored
    }

    /** Synchronous version for OkHttp interceptor. */
    fun getTokenSync(): String? {
        cachedToken?.let { return it }
        return runBlocking { configRepo.get("user_token") }.also { cachedToken = it }
    }

    fun getDeviceIdSync(): String? {
        cachedDeviceId?.let { return it }
        return runBlocking { configRepo.get("device_id") }.also { cachedDeviceId = it }
    }

    suspend fun isAuthenticated(): Boolean {
        val token = getToken() ?: return false
        if (token.startsWith("mock.")) return true

        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = JSONObject(payload)
            if (json.has("exp")) {
                val exp = json.getLong("exp")
                val now = System.currentTimeMillis() / 1000
                if (now >= exp) {
                    logout()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun logout() {
        configRepo.remove("user_token")
        cachedToken = null
    }

    /**
     * Decodes the JWT payload (Base64url) and returns the value of [claimName], or null.
     * Does NOT verify the signature — only used to read identity claims after a successful login.
     */
    private fun extractJwtClaim(token: String, claimName: String): String? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val json = JSONObject(payload)
            if (json.has(claimName)) json.getString(claimName) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Refresh cached device ID from config. */
    suspend fun refreshDeviceId() {
        cachedDeviceId = configRepo.get("device_id")
    }
}
