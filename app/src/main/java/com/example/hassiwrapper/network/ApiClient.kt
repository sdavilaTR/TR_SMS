package com.example.hassiwrapper.network

import com.example.hassiwrapper.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

/**
 * API client with primary/fallback URL resolution, JWT auth interceptor,
 * and device-id header — mirrors api.js behaviour.
 */
class ApiClient(
    private val configRepo: ConfigRepository,
    private val authRepo: AuthRepository
) {
    companion object {
        const val DEFAULT_PRIMARY = "https://web-atlas-api-dev.azurewebsites.net"
        const val DEFAULT_FALLBACK = "http://localhost:5000"
        private const val TIMEOUT_MS = 10_000L
        private const val PING_TIMEOUT_MS = 3_000L
    }

    @Volatile
    private var resolvedBase: String? = null
    private var cachedService: AtlasApiService? = null

    /** Force re-resolution on next request (e.g. after settings change). */
    fun resetResolvedBase() {
        resolvedBase = null
        cachedService = null
    }

    /** Seed default URLs into config if not already set. */
    suspend fun seedDefaults() {
        if (configRepo.get("api_base_url") == null) {
            configRepo.set("api_base_url", DEFAULT_PRIMARY)
        }
        if (configRepo.get("api_base_url_fallback") == null) {
            configRepo.set("api_base_url_fallback", DEFAULT_FALLBACK)
        }
    }

    /** Get or create the Retrofit service, resolving the base URL first. */
    suspend fun getService(): AtlasApiService {
        val base = getApiBase()
        // Return cached if same base
        cachedService?.let { if (resolvedBase == base) return it }
        val service = buildService(base)
        cachedService = service
        return service
    }

    /** Resolve which base URL to use: ping primary, fall back if it fails. */
    private suspend fun getApiBase(): String {
        resolvedBase?.let { return it }

        val primary = configRepo.get("api_base_url") ?: DEFAULT_PRIMARY
        val fallback = configRepo.get("api_base_url_fallback") ?: DEFAULT_FALLBACK

        val reachable = ping(primary)
        val chosen = if (reachable) primary else fallback
        resolvedBase = chosen
        return chosen
    }

    /** Quick ping — returns true if the host responds within PING_TIMEOUT_MS. */
    private suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url("$baseUrl/health").get().build()
            val response = client.newCall(request).execute()
            response.isSuccessful || response.code < 500
        } catch (e: Exception) {
            false
        }
    }

    private fun buildService(baseUrl: String): AtlasApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = Interceptor { chain ->
            val token = authRepo.getTokenSync()
            val deviceId = authRepo.getDeviceIdSync()

            val builder = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")

            if (!token.isNullOrEmpty()) {
                builder.addHeader("Authorization", "Bearer $token")
            }
            if (!deviceId.isNullOrEmpty() && deviceId != "unknown") {
                builder.addHeader("X-Device-Id", deviceId)
            }

            chain.proceed(builder.build())
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        // Ensure baseUrl ends with /
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        return Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AtlasApiService::class.java)
    }
}
