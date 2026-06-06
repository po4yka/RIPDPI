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
    val artifacts: List<GithubUpdateArtifact> = emptyList(),
)

@Serializable
internal data class GithubUpdateArtifact(
    val abi: String,
    val apkName: String,
    val versionCode: Long,
    val sha256: String,
    val sizeBytes: Long? = null,
)

internal data class GithubUpdatePlan(
    val update: AvailableAppUpdate,
    val metadata: GithubUpdateMetadata,
    val artifact: GithubUpdateArtifact,
    val apkAsset: GithubReleaseAsset,
)

internal fun GithubUpdateMetadata.toAvailableUpdate(artifact: GithubUpdateArtifact): AvailableAppUpdate =
    AvailableAppUpdate(
        versionCode = artifact.versionCode,
        versionName = versionName,
        changelog = changelog,
        sizeBytes = artifact.sizeBytes,
        artifactName = artifact.apkName,
    )

internal fun GithubUpdateMetadata.isStructurallyValid(): Boolean =
    packageName.isNotBlank() &&
        versionCode > 0 &&
        versionName.isNotBlank() &&
        apkName.isSafeApkName() &&
        sha256.matches(sha256Pattern) &&
        artifactsAreValid()

internal fun GithubUpdateMetadata.selectArtifact(supportedAbis: Array<String>): GithubUpdateArtifact? {
    if (artifacts.isEmpty()) {
        return fallbackArtifact()
    }
    supportedAbis.forEach { supportedAbi ->
        artifacts.firstOrNull { artifact -> artifact.abi == supportedAbi }?.let { return it }
    }
    return artifacts.firstOrNull { artifact -> artifact.abi == UniversalAbi }
}

private fun GithubUpdateMetadata.artifactsAreValid(): Boolean {
    if (schemaVersion >= 2 && artifacts.isEmpty()) {
        return false
    }
    return artifacts.all { artifact ->
        artifact.abi in SupportedArtifactAbis &&
            artifact.versionCode > 0 &&
            artifact.apkName.isSafeApkName() &&
            artifact.sha256.matches(sha256Pattern)
    }
}

private fun GithubUpdateMetadata.fallbackArtifact(): GithubUpdateArtifact =
    GithubUpdateArtifact(
        abi = UniversalAbi,
        apkName = apkName,
        versionCode = versionCode,
        sha256 = sha256,
        sizeBytes = sizeBytes,
    )

private fun String.isSafeApkName(): Boolean =
    endsWith(".apk", ignoreCase = true) &&
        this == substringAfterLast('/') &&
        this == substringAfterLast('\\') &&
        !contains("..") &&
        matches(apkNamePattern)

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

private const val UniversalAbi = "universal"
private val SupportedArtifactAbis =
    setOf(
        "armeabi-v7a",
        "arm64-v8a",
        "x86",
        "x86_64",
        UniversalAbi,
    )
private val apkNamePattern = Regex("[A-Za-z0-9._-]+\\.apk")
private val sha256Pattern = Regex("[A-Fa-f0-9]{64}")
