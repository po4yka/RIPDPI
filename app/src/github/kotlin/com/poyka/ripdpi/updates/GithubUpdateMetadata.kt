@file:Suppress("MagicNumber")

package com.poyka.ripdpi.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
internal data class GithubReleaseResponse(
    @SerialName("tag_name")
    val tagName: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList(),
)

@Serializable
internal data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long? = null,
)

@Serializable
internal data class GithubUpdateMetadata(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val sha256: String,
    val sizeBytes: Long? = null,
    val minSdk: Int? = null,
    val changelog: String = "",
)

internal data class GithubUpdatePlan(
    val update: AvailableAppUpdate,
    val metadata: GithubUpdateMetadata,
    val apkAsset: GithubReleaseAsset,
)

internal fun GithubUpdateMetadata.toAvailableUpdate(): AvailableAppUpdate =
    AvailableAppUpdate(
        versionCode = versionCode,
        versionName = versionName,
        changelog = changelog,
        sizeBytes = sizeBytes,
        artifactName = apkName,
    )

internal fun GithubUpdateMetadata.isStructurallyValid(): Boolean =
    packageName.isNotBlank() &&
        versionCode > 0 &&
        versionName.isNotBlank() &&
        apkName.endsWith(".apk", ignoreCase = true) &&
        apkName == apkName.substringAfterLast('/') &&
        apkName == apkName.substringAfterLast('\\') &&
        !apkName.contains("..") &&
        sha256.matches(sha256Pattern)

internal fun GithubReleaseAsset.isAllowedReleaseAsset(
    owner: String,
    repo: String,
): Boolean {
    val uri = runCatching { URI(browserDownloadUrl) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.host != "github.com") return false
    val normalizedOwner = owner.lowercase(Locale.US)
    val normalizedRepo = repo.lowercase(Locale.US)
    val path = uri.path.lowercase(Locale.US)
    val expectedPrefix = "/$normalizedOwner/$normalizedRepo/releases/download/"
    return path.startsWith(expectedPrefix) && !path.contains("..")
}

private val sha256Pattern = Regex("[A-Fa-f0-9]{64}")
