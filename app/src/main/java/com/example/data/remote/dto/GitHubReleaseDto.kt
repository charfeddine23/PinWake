package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    val id: Long = 0L,
    val name: String = "",
    val size: Long = 0L,
    @Json(name = "content_type") val contentType: String = "",
    @Json(name = "browser_download_url") val browserDownloadUrl: String = "",
    val digest: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    val id: Long = 0L,
    @Json(name = "tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @Json(name = "html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList()
) {
    fun firstApkAsset(): GitHubAssetDto? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}
