package com.starlink.scanner.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Partial mapping of the GitHub Releases API payload (`/releases/latest`). Only the fields the
 * updater needs are declared; `Json { ignoreUnknownKeys = true }` drops the rest.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String = "",
    // Public direct-download URL. Works anonymously because the releases repo is public.
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
