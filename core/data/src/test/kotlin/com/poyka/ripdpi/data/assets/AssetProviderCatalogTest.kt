package com.poyka.ripdpi.data.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetProviderCatalogTest {
    @Test
    fun `catalog exposes the five built-in presets plus custom`() {
        val ids = BuiltInAssetProviders.map { it.id }
        assertEquals(
            listOf("sagernet", "soffchen", "chocolate4u", "l11r", "runetfreedom", CustomAssetProviderId),
            ids,
        )
    }

    @Test
    fun `every built-in preset except custom has both repos`() {
        BuiltInAssetProviders.forEach { provider ->
            if (provider.isCustom) {
                assertNull(provider.geoipRepo)
                assertNull(provider.geositeRepo)
            } else {
                assertTrue("${provider.id} geoip repo", provider.geoipRepo != null)
                assertTrue("${provider.id} geosite repo", provider.geositeRepo != null)
            }
        }
    }

    @Test
    fun `lookup falls back to default for unknown or blank id`() {
        assertEquals(DefaultAssetProviderId, assetProviderById(null).id)
        assertEquals(DefaultAssetProviderId, assetProviderById("").id)
        assertEquals(DefaultAssetProviderId, assetProviderById("nope").id)
        assertEquals("l11r", assetProviderById("l11r").id)
    }

    @Test
    fun `asset filename mapping is canonical`() {
        assertEquals("geoip.db", geoAssetFileName(GeoAssetKind.Geoip))
        assertEquals("geosite.db", geoAssetFileName(GeoAssetKind.Geosite))
    }

    @Test
    fun `latest release api url is well formed`() {
        val repo = GeoAssetRepo("SagerNet/sing-geoip", "geoip.db")
        assertEquals(
            "https://api.github.com/repos/SagerNet/sing-geoip/releases/latest",
            githubLatestReleaseApiUrl(repo),
        )
    }

    @Test
    fun `release asset download url embeds tag and asset name`() {
        val repo = GeoAssetRepo("L11R/antizapret-sing-box-geo", "geosite.db")
        assertEquals(
            "https://github.com/L11R/antizapret-sing-box-geo/releases/download/v1.2.3/geosite.db",
            githubReleaseAssetDownloadUrl(repo, "v1.2.3"),
        )
    }

    @Test
    fun `custom download url normalizes trailing slashes and appends file name`() {
        assertEquals(
            "https://github.com/me/repo/releases/latest/download/geoip.db",
            customAssetDownloadUrl("https://github.com/me/repo/releases/latest/download/", GeoAssetKind.Geoip),
        )
        assertEquals(
            "https://example.org/geosite.db",
            customAssetDownloadUrl("  https://example.org  ", GeoAssetKind.Geosite),
        )
    }
}
