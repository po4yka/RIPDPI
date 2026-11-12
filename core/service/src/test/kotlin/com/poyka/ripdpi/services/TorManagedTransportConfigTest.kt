package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.core.RipDpiRelayConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TorManagedTransportConfigTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private var originalNativeLibraryDir: String? = null

    @Before
    fun setUp() {
        root = File(context.cacheDir, "tor-managed-transport-test").apply { mkdirs() }
        val nativeDir = File(root, "lib/arm64").apply { mkdirs() }
        originalNativeLibraryDir = context.applicationInfo.nativeLibraryDir
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        // The fixture exercises configuration and lookup only; no helper process is started.
        listOf("ripdpi-snowflake", "ripdpi-obfs4").forEach { name ->
            File(nativeDir, "lib$name.upstream.so").createNewFile()
        }
        File(root, "assets/$PluggableTransportManifestAsset").apply {
            parentFile!!.mkdirs()
            writeText(
                """
                {"artifacts":[
                  {"abi":"arm64-v8a","outputName":"ripdpi-snowflake","upstreamBinary":"ripdpi-snowflake.upstream"},
                  {"abi":"arm64-v8a","outputName":"ripdpi-obfs4","upstreamBinary":"ripdpi-obfs4.upstream"}
                ]}
                """.trimIndent(),
            )
        }
        val assetArchive = File(root, "assets.apk")
        ZipOutputStream(assetArchive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/$PluggableTransportManifestAsset"))
            zip.write(File(root, "assets/$PluggableTransportManifestAsset").readBytes())
            zip.closeEntry()
        }
        ReflectionHelpers.callInstanceMethod<Int>(
            context.assets,
            "addAssetPath",
            ReflectionHelpers.ClassParameter.from(String::class.java, assetArchive.absolutePath),
        )
    }

    @After
    fun tearDown() {
        context.applicationInfo.nativeLibraryDir = originalNativeLibraryDir
        root.deleteRecursively()
    }

    @Test
    fun `Tor launches Snowflake with DTLS mimic enabled and leaves bridge options intact`() {
        val bridge =
            "snowflake 192.0.2.1:1 url=https://broker.example/ covertdtls-config=disable utls-imitate=hellofirefox_auto"
        val config = RipDpiRelayConfig(ptBridgeLine = bridge)
        val transport = manager().torManagedTransports(config).single()

        assertEquals(listOf("snowflake"), transport.protocols)
        assertEquals(listOf("-covertdtls-config", "mimic"), transport.arguments)
        assertEquals("libripdpi-snowflake.upstream.so", File(transport.binaryPath).name)
        assertEquals(false, transport.runOnStartup)
        assertEquals(bridge, config.ptBridgeLine)
    }

    @Test
    fun `Tor keeps obfs4 independent from Snowflake defaults`() {
        val transport =
            manager().torManagedTransports(RipDpiRelayConfig(ptBridgeLine = "obfs4 192.0.2.2:443 cert=abcd")).single()

        assertEquals(listOf("obfs4"), transport.protocols)
        assertEquals(emptyList<String>(), transport.arguments)
        assertEquals("libripdpi-obfs4.upstream.so", File(transport.binaryPath).name)
    }

    private fun manager() =
        PluggableTransportManager(context, SubprocessSocksRelayManager(context, ActiveProtectSocketPathProvider()))
}
