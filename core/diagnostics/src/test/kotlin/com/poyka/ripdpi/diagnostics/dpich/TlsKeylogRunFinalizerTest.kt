package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TlsKeylogRunFinalizerTest {
    @Test
    fun finishRunRotatesConfiguredPathAndPurgesExpiredRotations() =
        runTest {
            val path = tempKeylogFile().apply { writeText("CLIENT_RANDOM abc def\n") }
            val stale = File("${path.absolutePath}.stale").apply { writeText("old") }
            val fresh = File("${path.absolutePath}.fresh").apply { writeText("fresh") }
            val nowMs = 1_700_000_000_000L
            stale.setLastModified(nowMs - 48.hoursMs)
            fresh.setLastModified(nowMs - 12.hoursMs)

            val result =
                TlsKeylogRunFinalizer(
                    nowSeconds = { 1_700_000_000L },
                    nowMs = { nowMs },
                ).finishRun(path.absolutePath)

            assertEquals(File("${path.absolutePath}.1700000000").absolutePath, result?.rotatedFile?.absolutePath)
            assertTrue(path.exists())
            assertEquals("", path.readText())
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
        }

    @Test
    fun finishRunDoesNothingForBlankPath() =
        runTest {
            val result = TlsKeylogRunFinalizer().finishRun(" ")

            assertEquals(null, result)
        }

    private fun tempKeylogFile(): File = createTempDirectory().toFile().resolve("tls.keys")

    private val Int.hoursMs: Long get() = this * 60L * 60L * 1_000L
}
