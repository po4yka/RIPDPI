package com.poyka.ripdpi.diagnostics.rkn

import com.poyka.ripdpi.diagnostics.dpi.DpiAssetFileProvider
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory

class RknTargetListTest {
    @Test
    fun whitelistLoads21Entries() {
        assertEquals(21, DpiAssetLoader(RepoRknAssetFileProvider()).loadRknWhitelistControl().size)
    }

    @Test
    fun blacklistLoads15Entries() {
        assertEquals(15, DpiAssetLoader(RepoRknAssetFileProvider()).loadRknBlacklistTest().size)
    }

    @Test
    fun textFormatSupportsNameUrlPair() {
        val target = RknTargetListParser.parse("sample https://example.org").single()

        assertEquals(RknTarget("sample", "https://example.org", "example.org"), target)
    }

    @Test
    fun textFormatSupportsNameEqualsUrl() {
        val target = RknTargetListParser.parse("custom=https://example.net/path").single()

        assertEquals(RknTarget("custom", "https://example.net/path", "example.net"), target)
    }

    @Test
    fun textFormatSupportsBareUrlWithAutoname() {
        val target = RknTargetListParser.parse("https://www.example.com/foo").single()

        assertEquals(RknTarget("www-example-com", "https://www.example.com/foo", "www.example.com"), target)
    }

    @Test
    fun commentLinesSkipped() {
        val target = RknTargetListParser.parse("# comment\nexample.com\n").single()

        assertEquals(RknTarget("example-com", "https://example.com", "example.com"), target)
    }

    @Test
    fun duplicateNameLastWins() {
        val targets = RknTargetListParser.parse("sample https://first.example\nsample https://second.example\n")

        assertEquals(listOf(RknTarget("sample", "https://second.example", "second.example")), targets)
    }

    @Test
    fun userOverrideTakesPrecedence() {
        val filesDir = createTempDirectory().toFile()
        File(filesDir, "rkn/rkn_whitelist_control.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("override https://override.example\n")
        }
        val loader =
            DpiAssetLoader(
                FakeRknAssetFileProvider(
                    filesDir = filesDir,
                    assets = mapOf("rkn/rkn_whitelist_control.txt" to "bundled https://bundled.example\n"),
                ),
            )

        assertEquals(
            listOf(RknTarget("override", "https://override.example", "override.example")),
            loader.loadRknWhitelistControl(),
        )
    }

    @Test
    fun cachedAfterFirstLoad() {
        val provider =
            FakeRknAssetFileProvider(
                assets =
                    mapOf("rkn/rkn_blacklist_test.txt" to "first https://first.example\n"),
            )
        val loader = DpiAssetLoader(provider)

        val first = loader.loadRknBlacklistTest()
        provider.assets = mapOf("rkn/rkn_blacklist_test.txt" to "second https://second.example\n")

        assertSame(first, loader.loadRknBlacklistTest())
    }

    private class RepoRknAssetFileProvider : DpiAssetFileProvider {
        private val filesDir = createTempDirectory().toFile()

        override fun overrideFile(relativePath: String): File = File(filesDir, relativePath)

        override fun openAsset(relativePath: String): InputStream =
            repoFixture("core/diagnostics/src/main/assets/$relativePath").inputStream()
    }

    private class FakeRknAssetFileProvider(
        private val filesDir: File = createTempDirectory().toFile(),
        var assets: Map<String, String>,
    ) : DpiAssetFileProvider {
        override fun overrideFile(relativePath: String): File = File(filesDir, relativePath)

        override fun openAsset(relativePath: String): InputStream =
            ByteArrayInputStream(requireNotNull(assets[relativePath]).toByteArray())
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
