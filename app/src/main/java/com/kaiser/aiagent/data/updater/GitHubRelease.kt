package com.kaiser.aiagent.data.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the GitHub Releases API response that we actually consume.
 *
 * See https://docs.github.com/en/rest/releases/releases#get-the-latest-release
 * The fields below are the only ones we read; unknown fields are ignored.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
    @SerialName("html_url") val htmlUrl: String? = null
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null
)
