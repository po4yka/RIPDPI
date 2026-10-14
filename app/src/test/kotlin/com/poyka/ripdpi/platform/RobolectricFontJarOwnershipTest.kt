package com.poyka.ripdpi.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.io.Resources
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import java.net.URI
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.util.zip.ZipFile

class RobolectricFontJarOwnershipTest {
    @Test
    fun `native graphics loads while another owner holds the font jar filesystem`() {
        val fontJarUri =
            checkNotNull(Thread.currentThread().contextClassLoader)
                .getResources("fonts/")
                .toList()
                .first { it.protocol == "jar" }
                .toURI()
        var ownsFileSystem = false
        val fileSystem =
            try {
                FileSystems.newFileSystem(fontJarUri, emptyMap<String, String>()).also { ownsFileSystem = true }
            } catch (_: FileSystemAlreadyExistsException) {
                FileSystems.getFileSystem(fontJarUri)
            }
        try {
            val assumptions = mutableListOf<Failure>()
            val runner = JUnitCore()
            runner.addListener(
                object : RunListener() {
                    override fun testAssumptionFailure(failure: Failure) {
                        assumptions.add(failure)
                    }
                },
            )
            val result = runner.run(NativeFontProbe::class.java)
            assertEquals("Both SDK sandboxes must execute", 2, result.runCount)
            assertEquals(0, result.ignoreCount)
            assertEquals(result.failures.joinToString("\n") { it.trace }, 0, result.failureCount)
            assertEquals(assumptions.joinToString("\n") { it.trace }, 0, assumptions.size)
            assertTrue("The caller must retain its filesystem", fileSystem.isOpen)
        } finally {
            if (ownsFileSystem) fileSystem.close()
        }
    }

    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [35, 36], shadows = [NativeSandboxShadow::class])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    class NativeFontProbe {
        @Test
        fun `original font and hyphen resources support native text rendering`() {
            for (resource in listOf("fonts/fonts.xml", "hyphen-data/hyph-af.hyb")) {
                val selected = Resources.getResource(resource)
                assertEquals("file", selected.protocol)
                val original =
                    checkNotNull(Thread.currentThread().contextClassLoader)
                        .getResources(resource)
                        .toList()
                        .first { it.protocol == "jar" }
                val jar = Paths.get(URI(original.toURI().schemeSpecificPart.substringBefore("!/"))).toFile()
                ZipFile(jar).use { archive ->
                    val expected = archive.getInputStream(archive.getEntry(resource)).use { it.readBytes() }
                    assertArrayEquals(expected, selected.openStream().use { it.readBytes() })
                }
            }
            val paint =
                Paint().apply {
                    color = Color.BLACK
                    textSize = 24f
                }
            assertTrue(paint.measureText("RIPDPI") > 0f)
            val bitmap = Bitmap.createBitmap(120, 48, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText("RIPDPI", 2f, 32f, paint)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Native text must draw pixels", pixels.any { it != Color.TRANSPARENT })
            bitmap.recycle()
        }
    }

    class NativeSandboxMarker

    // A unique public shadow configuration gives the probe fresh SDK sandboxes in the full suite.
    @Implements(NativeSandboxMarker::class, isInAndroidSdk = false)
    class NativeSandboxShadow
}
