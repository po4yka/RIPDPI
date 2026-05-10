package com.poyka.ripdpi.diagnostics.dpi

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
