package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies that Cloudflare helpers are resolved in place from nativeLibraryDir and that no copy
 * is written to the app data directory, where Android 10+ blocks exec().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudflarePublishBinaryLocatorTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private lateinit var nativeLibraryDir: File
    private var originalNativeLibraryDir: String? = null

    @Before
    fun setUp() {
        originalNativeLibraryDir = context.applicationInfo.nativeLibraryDir
        nativeLibraryDir = File(context.cacheDir, "native-lib-test/lib/arm64").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeLibraryDir.absolutePath
    }

    @After
    fun tearDown() {
        context.applicationInfo.nativeLibraryDir = originalNativeLibraryDir
        File(context.cacheDir, "native-lib-test").deleteRecursively()
    }

    @Test
    fun `origin helper resolves to its packaged library without extraction`() {
        val packaged = File(nativeLibraryDir, "libripdpi-cloudflare-origin.so").apply { writeText("elf") }

        val resolved =
            CloudflarePublishBinaryLocator(context)
                .locate(CloudflareOriginBinaryName, RelayCloudflareTunnelModePublishLocalOrigin)

        assertEquals(packaged, resolved)
        assertFalse(File(context.filesDir, "cloudflare-runtime").exists())
    }

    @Test
    fun `resolution is gated to publish mode`() {
        File(nativeLibraryDir, "libripdpi-cloudflare-origin.so").writeText("elf")

        assertThrows(IllegalStateException::class.java) {
            CloudflarePublishBinaryLocator(context)
                .locate(CloudflareOriginBinaryName, RelayCloudflareTunnelModeConsumeExisting)
        }
        assertFalse(File(context.filesDir, "cloudflare-runtime").exists())
    }
}
