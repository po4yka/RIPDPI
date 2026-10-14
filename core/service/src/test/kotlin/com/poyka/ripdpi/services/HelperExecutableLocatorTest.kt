package com.poyka.ripdpi.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HelperExecutableLocatorTest {
    private val root: File = Files.createTempDirectory("ripdpi-helper-locator").toFile()
    private val nativeLibraryDir: File = File(root, "app/lib/arm64").apply { mkdirs() }
    private val filesDir: File = File(root, "data/files").apply { mkdirs() }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `direct helper resolves to its packaged library in nativeLibraryDir`() {
        val packaged = packagedLibrary("libripdpi-naiveproxy.so")

        val resolved = locator(manifest = null).locateHelper("ripdpi-naiveproxy")

        assertEquals(packaged, resolved)
        assertNotUnderFilesDir(resolved)
    }

    @Test
    fun `missing direct helper fails closed without extracting a copy`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                locator(manifest = null).locateHelper("ripdpi-cloudflare-origin")
            }

        assertTrue(error.message.orEmpty().contains("libripdpi-cloudflare-origin.so"))
        assertEquals(emptyList<String>(), filesDir.list().orEmpty().toList())
    }

    @Test
    fun `Snowflake and obfs4 resolve the shared Lyrebird upstream`() {
        val packaged = packagedLibrary("liblyrebird.upstream.so")
        val manifest =
            """
            {
              "artifacts": [
                {"abi":"arm64-v8a","outputName":"ripdpi-snowflake","upstreamBinary":"lyrebird.upstream"},
                {"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"lyrebird.upstream"}
              ]
            }
            """.trimIndent()

        for (outputName in listOf("ripdpi-snowflake", "ripdpi-obfs4")) {
            assertEquals(packaged, locator(manifest).locatePluggableTransport(outputName))
        }
    }

    @Test
    fun `single-output transport keeps its manifest upstream name`() {
        val packaged = packagedLibrary("libripdpi-webtunnel.upstream.so")
        val manifest =
            """
            {"artifacts":[
              {"abi":"arm64-v8a","outputName":"ripdpi-webtunnel","upstreamBinary":"ripdpi-webtunnel.upstream"}
            ]}
            """.trimIndent()

        assertEquals(packaged, locator(manifest).locatePluggableTransport("ripdpi-webtunnel"))
    }

    @Test
    fun `transport resolves the record for the installed ABI only`() {
        packagedLibrary("libripdpi-cloudflared.upstream.so")
        val manifest =
            """
            {"artifacts":[
              {"abi":"x86_64","outputName":"ripdpi-cloudflared","upstreamBinary":"other.upstream"},
              {"abi":"arm64-v8a","outputName":"ripdpi-cloudflared","upstreamBinary":"ripdpi-cloudflared.upstream"}
            ]}
            """.trimIndent()

        assertEquals(
            "libripdpi-cloudflared.upstream.so",
            locator(manifest).locatePluggableTransport("ripdpi-cloudflared").name,
        )
    }

    @Test
    fun `stub transport fails closed with an unavailable message`() {
        packagedLibrary("libripdpi-obfs4.upstream.so")
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":null}]}"""

        val error =
            assertThrows(IllegalStateException::class.java) {
                locator(manifest).locatePluggableTransport("ripdpi-obfs4")
            }

        assertTrue(error.message.orEmpty().contains("unavailable"))
    }

    @Test
    fun `unsafe manifest upstream fails closed without traversal`() {
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"../escape"}]}"""

        val error =
            assertThrows(IllegalStateException::class.java) {
                locator(manifest).locatePluggableTransport("ripdpi-obfs4")
            }

        assertTrue(error.cause is IllegalArgumentException)
    }

    @Test
    fun `missing or malformed manifest does not fall back to a launcher name`() {
        packagedLibrary("libripdpi-obfs4.so")
        for (manifest in listOf(null, "{not-json", """{"artifacts":[]}""")) {
            assertThrows(IllegalStateException::class.java) {
                locator(manifest).locatePluggableTransport("ripdpi-obfs4")
            }
        }
    }

    @Test
    fun `manifest with upstream that is not packaged fails closed`() {
        val manifest =
            """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"lyrebird.upstream"}]}"""

        assertThrows(IllegalStateException::class.java) {
            locator(manifest).locatePluggableTransport("ripdpi-obfs4")
        }
    }

    @Test
    fun `stub manifest record resolves to no upstream`() {
        assertNull(
            resolvePluggableTransportUpstreamBinary(
                """{"artifacts":[{"abi":"arm64-v8a","outputName":"ripdpi-obfs4"}]}""",
                "arm64-v8a",
                "ripdpi-obfs4",
            ),
        )
    }

    @Test
    fun `library file name rejects path segments`() {
        assertEquals("libripdpi-naiveproxy.so", helperExecutableLibraryFileName("ripdpi-naiveproxy"))
        for (unsafe in listOf("", "../ripdpi", "bin/ripdpi")) {
            assertThrows(IllegalArgumentException::class.java) { helperExecutableLibraryFileName(unsafe) }
        }
    }

    @Test
    fun `installed ABI comes from the native library directory`() {
        val supported = listOf("arm64-v8a", "armeabi-v7a")
        assertEquals("arm64-v8a", installedNativeLibraryAbi("/data/app/x/lib/arm64", supported))
        assertEquals("armeabi-v7a", installedNativeLibraryAbi("/data/app/x/lib/arm", supported))
        assertEquals("x86", installedNativeLibraryAbi("/data/app/x/lib/x86", supported))
        assertEquals("x86_64", installedNativeLibraryAbi("/data/app/x/lib/x86_64", supported))
        assertEquals("armeabi-v7a", installedNativeLibraryAbi("/data/app/x/lib/unknown", listOf("armeabi-v7a")))
    }

    @Test
    fun `launch plan executes the helper from nativeLibraryDir`() {
        val packaged = packagedLibrary("libripdpi-naiveproxy.so")
        val binary = locator(manifest = null).locateHelper("ripdpi-naiveproxy")

        val command =
            SubprocessRelayLaunchPlanner()
                .buildMainProcess(
                    binary = binary,
                    spec =
                        SubprocessSocksRelayLaunchSpec(
                            binaryName = "ripdpi-naiveproxy",
                            commandArguments = listOf("--listen", "127.0.0.1:1080"),
                            runtimeKind = "naiveproxy",
                        ),
                ).command()

        assertEquals(listOf(packaged.absolutePath, "--listen", "127.0.0.1:1080"), command)
        assertNotUnderFilesDir(File(command.first()))
    }

    @Test
    fun `legacy extracted helpers are purged and transport state is kept`() {
        val legacyRelay = File(filesDir, "subprocess-relays/arm64-v8a/ripdpi-obfs4").apply { createWithParents() }
        val legacyCloudflare =
            File(
                filesDir,
                "cloudflare-runtime/arm64-v8a/ripdpi-cloudflared",
            ).apply { createWithParents() }
        val transportState = File(filesDir, "pluggable-transports/profile-obfs4/state").apply { createWithParents() }

        purgeLegacyExtractedHelpers(filesDir)

        assertFalse(legacyRelay.parentFile!!.parentFile!!.exists())
        assertFalse(legacyCloudflare.parentFile!!.parentFile!!.exists())
        assertTrue(transportState.exists())
    }

    private fun locator(manifest: String?): HelperExecutableLocator =
        HelperExecutableLocator(
            nativeLibraryDir = nativeLibraryDir,
            abi = "arm64-v8a",
            manifestPayload = { manifest },
        )

    private fun packagedLibrary(name: String): File = File(nativeLibraryDir, name).apply { writeText("elf") }

    private fun assertNotUnderFilesDir(file: File) {
        assertFalse(
            "${file.absolutePath} must not be under the app data directory",
            file.canonicalPath.startsWith(filesDir.canonicalPath),
        )
    }

    private fun File.createWithParents() {
        parentFile?.mkdirs()
        writeText("stale")
    }
}
