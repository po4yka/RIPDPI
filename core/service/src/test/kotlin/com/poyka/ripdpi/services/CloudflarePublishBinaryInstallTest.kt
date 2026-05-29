package com.poyka.ripdpi.services

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Verifies the install-once-per-(ABI, version hash) contract of
 * [CloudflarePublishBinaryExtractor.installIfChanged]:
 *
 * 1. First install copies the asset and writes the `.sha256` marker.
 * 2. A subsequent install with identical bytes validates the hash and skips the copy.
 * 3. A changed asset (new binary shipped by an app update) invalidates the cache and re-copies.
 * 4. The executable bit is restored on the skip path if it was cleared.
 */
@RunWith(RobolectricTestRunner::class)
class CloudflarePublishBinaryInstallTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private val installDir: File by lazy {
        File(context.cacheDir, "cf-binary-install-test").apply { mkdirs() }
    }

    private fun extractor() = CloudflarePublishBinaryExtractor(context = context)

    private fun source(bytes: ByteArray): () -> ByteArrayInputStream = { ByteArrayInputStream(bytes) }

    @After
    fun tearDown() {
        installDir.deleteRecursively()
    }

    @Test
    fun `first install copies asset and writes marker`() {
        val target = File(installDir, CloudflaredBinaryName)
        val bytes = "cloudflared-v1".toByteArray()

        val copied = extractor().installIfChanged(target, source(bytes))

        assertTrue("First install should copy", copied)
        assertArrayEqualsContent(bytes, target)
        assertTrue("Marker should exist", File(installDir, "${target.name}.sha256").exists())
    }

    @Test
    fun `second install with identical bytes skips copy`() {
        val target = File(installDir, CloudflaredBinaryName)
        val bytes = "cloudflared-v1".toByteArray()
        val extractor = extractor()

        assertTrue(extractor.installIfChanged(target, source(bytes)))
        val secondCopied = extractor.installIfChanged(target, source(bytes))

        assertFalse("Second install with identical bytes should skip the copy", secondCopied)
        assertArrayEqualsContent(bytes, target)
    }

    @Test
    fun `changed asset bytes invalidate cache and recopy`() {
        val target = File(installDir, CloudflaredBinaryName)
        val extractor = extractor()

        assertTrue(extractor.installIfChanged(target, source("cloudflared-v1".toByteArray())))
        val v2 = "cloudflared-v2-longer".toByteArray()
        val recopied = extractor.installIfChanged(target, source(v2))

        assertTrue("A changed asset should re-install", recopied)
        assertArrayEqualsContent(v2, target)
    }

    @Test
    fun `executable bit restored on skip path`() {
        val target = File(installDir, CloudflaredBinaryName)
        val bytes = "cloudflared-v1".toByteArray()
        val extractor = extractor()

        assertTrue(extractor.installIfChanged(target, source(bytes)))
        target.setExecutable(false, false)

        assertFalse(extractor.installIfChanged(target, source(bytes)))
        assertTrue("Executable bit should be restored on skip", target.canExecute())
    }

    private fun assertArrayEqualsContent(
        expected: ByteArray,
        target: File,
    ) = assertEquals(String(expected), target.readText())
}
