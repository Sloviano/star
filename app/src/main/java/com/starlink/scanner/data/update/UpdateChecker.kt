package com.starlink.scanner.data.update

import com.starlink.scanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of a check against the GitHub Releases API. */
sealed interface UpdateStatus {
    /** Latest release is not newer than the installed build (or the updater is not configured). */
    data object UpToDate : UpdateStatus

    /** A newer build is available. [apkDownloadUrl] is the public URL to hand to [ApkInstaller]. */
    data class Available(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val apkDownloadUrl: String,
    ) : UpdateStatus

    /** The check could not complete. [reason] is user-readable. */
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * Reads the latest GitHub release for the configured **public** releases repo and decides whether it
 * is newer than the installed build. No auth token is used — the repo is public, so nothing secret
 * ships in the APK. The release tag encodes the Android versionCode as `v<versionName>+<versionCode>`
 * (e.g. `v1.1+5`); see [parseVersionCode].
 *
 * Reuses the OkHttp + kotlinx.serialization patterns from
 * [com.starlink.scanner.data.upload.SheetsUploader].
 */
class UpdateChecker(
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val apiBaseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** True when a releases repo is configured; the launch check no-ops otherwise. */
    val isConfigured: Boolean get() = repo.isNotBlank()

    /**
     * @return [UpdateStatus.Available] only when the latest release's versionCode is strictly
     *   greater than [currentVersionCode]; [UpdateStatus.UpToDate] when it isn't (or unconfigured);
     *   [UpdateStatus.Failed] on transport / parsing / API errors.
     */
    suspend fun check(currentVersionCode: Long): UpdateStatus = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext UpdateStatus.UpToDate

        val request = Request.Builder()
            .url("$apiBaseUrl/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val body = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateStatus.Failed("GitHub HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return@withContext UpdateStatus.Failed(e.message ?: "Network error")
        }

        val release = try {
            json.decodeFromString(GithubRelease.serializer(), body)
        } catch (e: SerializationException) {
            return@withContext UpdateStatus.Failed("Unexpected response: ${body.take(120)}")
        }

        val releaseVersionCode = parseVersionCode(release.tagName)
            ?: return@withContext UpdateStatus.Failed("Bad release tag: ${release.tagName}")

        if (releaseVersionCode <= currentVersionCode) return@withContext UpdateStatus.UpToDate

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext UpdateStatus.Failed("Release has no APK asset")

        UpdateStatus.Available(
            versionCode = releaseVersionCode,
            versionName = release.name.ifBlank { release.tagName },
            notes = release.body,
            apkDownloadUrl = apk.browserDownloadUrl,
        )
    }

    companion object {
        /**
         * Extract the versionCode from a release tag shaped `v<versionName>+<versionCode>`
         * (e.g. `v1.1+5` -> 5). Falls back to treating the whole tag as an integer (`5` -> 5).
         * Returns null when no integer can be read.
         */
        fun parseVersionCode(tag: String): Long? {
            val candidate = tag.substringAfterLast('+', tag).trim().trimStart('v', 'V')
            return candidate.toLongOrNull()
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
