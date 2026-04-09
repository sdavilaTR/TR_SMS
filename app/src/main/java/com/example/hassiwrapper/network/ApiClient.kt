package com.example.hassiwrapper.network

import com.example.hassiwrapper.ProfileManager
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
        const val DEFAULT_PRIMARY = "https://web-atlas-api-pre.azurewebsites.net"
        const val DEFAULT_FALLBACK = "https://web-atlas-api-pre.azurewebsites.net"
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
        // We no longer seed API URLs as they are hardcoded.
        // But we keep the method if other defaults are needed in the future.
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

    /** Resolve which base URL to use based on current profile. */
    private suspend fun getApiBase(): String {
        return ProfileManager.getApiUrl()
    }

    data class ConnectivityStatus(val apiReachable: Boolean, val resolvedUrl: String)

    /** Check API connectivity without affecting cached resolvedBase. */
    suspend fun checkConnectivity(): ConnectivityStatus {
        val url = ProfileManager.getApiUrl()
        val reachable = ping(url)
        return ConnectivityStatus(apiReachable = reachable, resolvedUrl = url)
    }

    /** Quick ping — returns true if the host responds within PING_TIMEOUT_MS. */
    private suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val healthPath = if (ProfileManager.usesPublicProxy())
                "${ProfileManager.PRO_PATH_PREFIX}/health" else "/health"
            val request = Request.Builder().url("$baseUrl$healthPath").get().build()
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

        // When the PRO profile is active, the public reverse proxy expects every
        // request path to be prefixed with /api. We rewrite the URL here so the
        // Retrofit service definitions can stay environment-agnostic.
        val proxyPrefixInterceptor = Interceptor { chain ->
            val original = chain.request()
            if (!ProfileManager.usesPublicProxy()) {
                return@Interceptor chain.proceed(original)
            }
            // The public reverse proxy unconditionally prepends /api to every
            // path, even if the original already starts with /api (so e.g.
            // /api/trac/sync/download becomes /api/api/trac/sync/download).
            val prefix = ProfileManager.PRO_PATH_PREFIX // "/api"
            val rebuilt = original.url.newBuilder()
                .encodedPath("$prefix${original.url.encodedPath}")
                .build()
            chain.proceed(original.newBuilder().url(rebuilt).build())
        }

        val authInterceptor = Interceptor { chain ->
            val token = authRepo.getTokenSync()
            val deviceId = authRepo.getDeviceIdSync()

            val original = chain.request()
            val builder = original.newBuilder()
            if (original.body?.contentType()?.type != "multipart") {
                builder.addHeader("Content-Type", "application/json")
            }

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
            .addInterceptor(proxyPrefixInterceptor)
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
