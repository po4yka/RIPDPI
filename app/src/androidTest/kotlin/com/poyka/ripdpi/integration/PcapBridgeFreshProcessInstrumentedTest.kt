package com.poyka.ripdpi.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.pcap.PcapController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PcapBridgeFreshProcessInstrumentedTest {
    @Test
    fun cleanupRemovesAnExpiredCaptureThroughJni() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val captureDir = Files.createTempDirectory(context.cacheDir.toPath(), "pcap-retention").toFile()
        try {
            val stale = captureDir.resolve("0000000000000001-1-00.pcap")
            stale.writeBytes(ByteArray(24))
            assertTrue(stale.setLastModified(1_700_000_000_000L))
            val controller = PcapController(context)
            runBlocking {
                assertEquals(1, controller.listCaptures(captureDir).size)
                controller.cleanupCaptures(captureDir)
                assertTrue(controller.listCaptures(captureDir).isEmpty())
            }
            assertFalse(stale.exists())
        } finally {
            captureDir.deleteRecursively()
        }
    }

    @Test
    fun listsCapturesBeforeTunnelSessionLoadsNativeLibrary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val captureDirectory =
            File(context.cacheDir, "pcap-fresh-process").apply {
                deleteRecursively()
                assertTrue(mkdirs())
            }

        try {
            val captures =
                runBlocking {
                    PcapController(context).listCaptures(captureDirectory)
                }

            assertTrue(captures.isEmpty())
        } finally {
            captureDirectory.deleteRecursively()
        }
    }
}
