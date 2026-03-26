package com.example.hassiwrapper.update

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
    @SerializedName("browser_download_url") val downloadUrl: String
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
     * Checks GitHub for a newer release. Returns [UpdateInfo] if an APK asset is available
     * and the release tag is lexicographically newer than [currentBuildTag].
     * Returns null on network errors or when already up-to-date.
     *
     * Release tags follow the date format: v{YYYY}-{MM}-{DD}-{HH}-{MM}, e.g. v2026-03-26-06-55.
     * This format is naturally lexicographically sortable.
     */
    suspend fun checkForUpdate(currentBuildTag: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Skip check on local/dev builds where no tag was injected at build time
        if (currentBuildTag == "dev") return@withContext null

        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val release = gson.fromJson(bodyStr, GithubRelease::class.java)

                if (release.tagName > currentBuildTag) {
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    apkAsset?.let {
                        UpdateInfo(
                            version = release.tagName,
                            downloadUrl = it.downloadUrl,
                            releaseNotes = release.body
                        )
                    }
                } else null
            }
        } catch (_: Exception) {
            null // Silently ignore — network unavailable or API error
        }
    }
}
