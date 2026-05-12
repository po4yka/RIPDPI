package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.dpich.PtEndpoint
import com.poyka.ripdpi.diagnostics.dpich.loadDohProviderFilters
import com.poyka.ripdpi.diagnostics.dpich.loadObfs4BridgeEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory

class DpiAssetLoaderTest {
    @Test
    fun bundledAssetsLoadCurrentReferenceCounts() {
        val loader = DpiAssetLoader(fileProvider = RepoDpiAssetFileProvider())

        assertEquals(108, loader.loadTcp16Targets().size)
        assertEquals(40, loader.loadDomains().size)
        assertEquals(188, loader.loadWhitelistSni().size)
        assertEquals(6, loader.loadDohProviderFilters().size)
        assertEquals(10, loader.loadObfs4Bridges().size)
        assertEquals(3, loader.loadMeekFronts().size)
        assertEquals(5, loader.loadCidrWhitelistedUrls().size)
        assertEquals(5, loader.loadCidrRegularUrls().size)
        assertEquals(21, loader.loadIpv4WhitelistAsns().size)
        assertEquals(
            listOf("cloudflare.com", "fastly.com", "mozilla.org", "cloudflare-ech.com"),
            loader.loadEchTargets(),
        )
    }

    @Test
    fun tcp16TargetsParsedCorrectly() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets =
                            mapOf(
                                "dpi/tcp16.json" to
                                    """
                                    [
                                      {"id":"CF-01","asn":"13335","provider":"Cloudflare","ip":"1.1.1.1","port":443},
                                      {"id":"HE-01","asn":"24940","provider":"Hetzner","ip":"2.2.2.2",",port":80,"sni":"example.org"}
                                    ]
                                    """.trimIndent(),
                            ),
                    ),
            )

        assertEquals(
            listOf(
                Tcp16Target(
                    id = "CF-01",
                    asn = "13335",
                    provider = "Cloudflare",
                    ip = "1.1.1.1",
                    port = 443,
                    sni = null,
                ),
                Tcp16Target(
                    id = "HE-01",
                    asn = "24940",
                    provider = "Hetzner",
                    ip = "2.2.2.2",
                    port = 80,
                    sni = "example.org",
                ),
            ),
            loader.loadTcp16Targets(),
        )
    }

    @Test
    fun domainsCommentLinesSkipped() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets = mapOf("dpi/domains.txt" to "# comment\nexample.org\n\n  example.net  \n"),
                    ),
            )

        assertEquals(listOf("example.org", "example.net"), loader.loadDomains())
    }

    @Test
    fun whitelistSniLoadsBundledEntries() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets =
                            mapOf(
                                "dpi/whitelist_sni.txt" to
                                    List(188) { index -> "site$index.ru" }.joinToString(separator = "\n"),
                            ),
                    ),
            )

        assertEquals(188, loader.loadWhitelistSni().size)
    }

    @Test
    fun userOverrideTakesPrecedenceOverBundledAsset() {
        val filesDir = createTempDirectory().toFile()
        File(filesDir, "dpi/domains.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("override.example\n")
        }
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        filesDir = filesDir,
                        assets = mapOf("dpi/domains.txt" to "bundled.example\n"),
                    ),
            )

        assertEquals(listOf("override.example"), loader.loadDomains())
    }

    @Test
    fun echTargetsUserOverrideTakesPrecedenceOverBundledAsset() {
        val filesDir = createTempDirectory().toFile()
        File(filesDir, "dpi/ech_targets.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("override-ech.example\n")
        }
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        filesDir = filesDir,
                        assets = mapOf("dpi/ech_targets.txt" to "cloudflare.com\n"),
                    ),
            )

        assertEquals(listOf("override-ech.example"), loader.loadEchTargets())
    }

    @Test
    fun ptAssetUserOverrideTakesPrecedenceOverBundledAsset() {
        val filesDir = createTempDirectory().toFile()
        File(filesDir, "dpich/obfs4_bridges.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("203.0.113.1:443\n")
        }
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        filesDir = filesDir,
                        assets = mapOf("dpich/obfs4_bridges.txt" to "192.0.2.1:443\n"),
                    ),
            )

        assertEquals(listOf("203.0.113.1:443"), loader.loadObfs4Bridges())
    }

    @Test
    fun cidrUrlGroupUserOverrideTakesPrecedenceOverBundledAsset() {
        val filesDir = createTempDirectory().toFile()
        File(filesDir, "dpich/cidr_regular_urls.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("https://override.example/\n")
        }
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        filesDir = filesDir,
                        assets = mapOf("dpich/cidr_regular_urls.txt" to "https://bundled.example/\n"),
                    ),
            )

        assertEquals(listOf("https://override.example/"), loader.loadCidrRegularUrls())
    }

    @Test
    fun ipv4WhitelistAsnAssetParsesProviderGroups() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets =
                            mapOf(
                                "dpich/ipv4_whitelist_asns.json" to
                                    """
                                    [
                                      {"provider":"Yandex","asn":13238},
                                      {"provider":"VK","asn":28709}
                                    ]
                                    """.trimIndent(),
                            ),
                    ),
            )

        assertEquals("Yandex", loader.loadIpv4WhitelistAsns().first().provider)
        assertEquals(28709, loader.loadIpv4WhitelistAsns().last().asn)
    }

    @Test
    fun obfs4BridgeAssetsMapToEndpoints() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets = mapOf("dpich/obfs4_bridges.txt" to "203.0.113.1:443\ninvalid\n"),
                    ),
            )

        assertEquals(listOf(PtEndpoint("203.0.113.1", 443)), loader.loadObfs4BridgeEndpoints())
    }

    @Test
    fun dohProviderFilterAssetParsesYamlEntries() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets =
                            mapOf(
                                "dpich/doh_provider_filters.yaml" to
                                    """
                                    - name: Google
                                      dohHostname: dns.google
                                      expectedFilter: as(15169) || org("google")
                                    """.trimIndent(),
                            ),
                    ),
            )

        assertEquals("Google", loader.loadDohProviderFilters().single().providerName)
        assertEquals("dns.google", loader.loadDohProviderFilters().single().dohHostname)
    }

    @Test
    fun cachedAfterFirstLoad() {
        val provider = FakeDpiAssetFileProvider(assets = mapOf("dpi/domains.txt" to "first.example\n"))
        val loader = DpiAssetLoader(fileProvider = provider)

        val first = loader.loadDomains()
        provider.assets = mapOf("dpi/domains.txt" to "second.example\n")

        assertSame(first, loader.loadDomains())
        assertEquals(listOf("first.example"), loader.loadDomains())
    }

    private class FakeDpiAssetFileProvider(
        private val filesDir: File = createTempDirectory().toFile(),
        var assets: Map<String, String>,
    ) : DpiAssetFileProvider {
        override fun overrideFile(relativePath: String): File = File(filesDir, relativePath)

        override fun openAsset(relativePath: String): InputStream =
            ByteArrayInputStream(requireNotNull(assets[relativePath]).toByteArray())
    }

    private class RepoDpiAssetFileProvider : DpiAssetFileProvider {
        private val filesDir = createTempDirectory().toFile()

        override fun overrideFile(relativePath: String): File = File(filesDir, relativePath)

        override fun openAsset(relativePath: String): InputStream =
            repoFixture("core/diagnostics/src/main/assets/$relativePath").inputStream()
    }
}

private fun repoFixture(path: String): File {
    var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        val base = current ?: return@repeat
        val candidate = File(base, path)
        if (candidate.exists()) return candidate
        current = base.parentFile
    }
    error("Fixture not found: $path")
}
