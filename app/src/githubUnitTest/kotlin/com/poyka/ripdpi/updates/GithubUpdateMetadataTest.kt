package com.poyka.ripdpi.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdateMetadataTest {
    @Test
    fun releaseAssetAllowsOnlyConfiguredRepositoryReleaseDownloads() {
        val asset =
            GithubReleaseAsset(
                name = "app-github-release.apk",
                browserDownloadUrl = "https://github.com/po4yka/RIPDPI/releases/download/v1/app-github-release.apk",
            )

        assertTrue(asset.isAllowedReleaseAsset(owner = "po4yka", repo = "RIPDPI"))
    }

    @Test
    fun releaseAssetRejectsDifferentRepository() {
        val asset =
            GithubReleaseAsset(
                name = "app-github-release.apk",
                browserDownloadUrl = "https://github.com/other/RIPDPI/releases/download/v1/app-github-release.apk",
            )

        assertFalse(asset.isAllowedReleaseAsset(owner = "po4yka", repo = "RIPDPI"))
    }

    @Test
    fun releaseAssetRejectsNonGithubHost() {
        val asset =
            GithubReleaseAsset(
                name = "app-github-release.apk",
                browserDownloadUrl = "https://example.com/po4yka/RIPDPI/releases/download/v1/app-github-release.apk",
            )

        assertFalse(asset.isAllowedReleaseAsset(owner = "po4yka", repo = "RIPDPI"))
    }

    @Test
    fun metadataRequiresApkNameAndSha256() {
        val valid =
            GithubUpdateMetadata(
                packageName = "com.poyka.ripdpi",
                versionCode = 8,
                versionName = "0.0.8",
                apkName = "app-github-release.apk",
                sha256 = "a".repeat(64),
            )

        assertTrue(valid.isStructurallyValid())
        assertFalse(valid.copy(apkName = "app-github-release.aab").isStructurallyValid())
        assertFalse(valid.copy(apkName = "../app-github-release.apk").isStructurallyValid())
        assertFalse(valid.copy(apkName = "nested/app-github-release.apk").isStructurallyValid())
        assertFalse(valid.copy(sha256 = "not-a-sha").isStructurallyValid())
    }

    @Test
    fun v1MetadataSelectsTopLevelUniversalArtifact() {
        val metadata =
            GithubUpdateMetadata(
                packageName = "com.poyka.ripdpi",
                versionCode = 8,
                versionName = "0.0.8",
                apkName = "app-github-release.apk",
                sha256 = "a".repeat(64),
                sizeBytes = 123,
            )

        val artifact = metadata.selectArtifact(arrayOf("arm64-v8a"))

        assertTrue(metadata.isStructurallyValid())
        requireNotNull(artifact)
        assertTrue(artifact.abi == "universal")
        assertTrue(artifact.apkName == "app-github-release.apk")
        assertTrue(artifact.versionCode == 8L)
        assertTrue(artifact.sizeBytes == 123L)
    }

    @Test
    fun v2MetadataSelectsFirstSupportedAbiArtifact() {
        val metadata = splitMetadata()

        val artifact = metadata.selectArtifact(arrayOf("x86_64", "arm64-v8a"))

        requireNotNull(artifact)
        assertTrue(artifact.abi == "x86_64")
        assertTrue(artifact.apkName == "app-github-release-x86_64.apk")
        assertTrue(artifact.versionCode == 40_000_009L)
    }

    @Test
    fun v2MetadataFallsBackToUniversalWhenDeviceAbiIsMissing() {
        val metadata = splitMetadata()

        val artifact = metadata.selectArtifact(arrayOf("mips64"))

        requireNotNull(artifact)
        assertTrue(artifact.abi == "universal")
        assertTrue(artifact.apkName == "app-github-release-universal.apk")
    }

    @Test
    fun v2MetadataRejectsUnsafeArtifactNames() {
        val metadata =
            splitMetadata().copy(
                artifacts =
                    listOf(
                        GithubUpdateArtifact(
                            abi = "arm64-v8a",
                            apkName = "../app-github-release-arm64-v8a.apk",
                            versionCode = 20_000_009,
                            sha256 = "b".repeat(64),
                        ),
                    ),
            )

        assertFalse(metadata.isStructurallyValid())
    }

    private fun splitMetadata(): GithubUpdateMetadata =
        GithubUpdateMetadata(
            schemaVersion = 2,
            packageName = "com.poyka.ripdpi",
            versionCode = 9,
            versionName = "0.1.1",
            apkName = "app-github-release-universal.apk",
            sha256 = "a".repeat(64),
            artifacts =
                listOf(
                    GithubUpdateArtifact(
                        abi = "arm64-v8a",
                        apkName = "app-github-release-arm64-v8a.apk",
                        versionCode = 20_000_009,
                        sha256 = "b".repeat(64),
                    ),
                    GithubUpdateArtifact(
                        abi = "x86_64",
                        apkName = "app-github-release-x86_64.apk",
                        versionCode = 40_000_009,
                        sha256 = "c".repeat(64),
                    ),
                    GithubUpdateArtifact(
                        abi = "universal",
                        apkName = "app-github-release-universal.apk",
                        versionCode = 9,
                        sha256 = "a".repeat(64),
                    ),
                ),
        )
}
