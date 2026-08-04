package com.poyka.ripdpi.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.pcap.PcapController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PcapBridgeFreshProcessInstrumentedTest {
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
