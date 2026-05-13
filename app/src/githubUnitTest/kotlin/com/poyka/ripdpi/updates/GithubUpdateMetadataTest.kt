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
}
