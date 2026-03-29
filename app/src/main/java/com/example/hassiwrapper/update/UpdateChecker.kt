package com.example.hassiwrapper.update

import com.example.hassiwrapper.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<GithubAsset>
)

data class GithubAsset(
    @SerializedName("name") val name: String,
    // "url" is the GitHub API asset endpoint (returns 302 → S3 pre-signed URL).
    // browser_download_url requires browser OAuth and fails with API tokens on private repos.
    @SerializedName("url") val url: String
)

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String?
)

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/sdavilaTR/HassiSiteApp/releases/latest"

    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * Checks GitHub for a newer release.
     *
     * - If [currentBuildTag] is a date tag (e.g. "v2026-03-26-06-55"), returns [UpdateInfo] only
     *   when the latest GitHub release tag is lexicographically newer.
     * - If [currentBuildTag] is "dev" (APK built locally or before the tag was injected into
     *   the workflow), the app has no way to know its real version, so it always returns the
     *   latest release to ensure those devices receive updates.
     *
     * Returns null on network/API errors or when already on the latest release.
     */
    suspend fun checkForUpdate(currentBuildTag: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github.v3+json")
            if (BuildConfig.GH_RELEASE_TOKEN.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${BuildConfig.GH_RELEASE_TOKEN}")
            }
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val release = gson.fromJson(bodyStr, GithubRelease::class.java)

                // "dev" builds have no real version — treat them as always outdated so they
                // self-update to the first properly tagged release.
                val needsUpdate = currentBuildTag == "dev" || release.tagName > currentBuildTag

                if (needsUpdate) {
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    apkAsset?.let {
                        UpdateInfo(
                            version = release.tagName,
                            downloadUrl = it.url,
                            releaseNotes = release.body
                        )
                    }
                } else null
            }
        } catch (_: Exception) {
            null // Network unavailable or API error — fail silently
        }
    }
}
