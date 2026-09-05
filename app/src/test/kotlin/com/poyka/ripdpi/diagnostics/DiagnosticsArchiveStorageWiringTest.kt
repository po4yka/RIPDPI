package com.poyka.ripdpi.diagnostics

import android.app.Application
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveClock
import com.poyka.ripdpi.pcap.PcapCaptureRuntimeController
import com.poyka.ripdpi.pcap.PcapController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class DiagnosticsArchiveStorageWiringTest {
    @Test
    fun `production retention binding cleans the native capture directory`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val pcapController = PcapController(context)
            val capture =
                pcapController.captureDirectory.resolve("0000000000000001-1-00.pcap").apply {
                    parentFile.mkdirs()
                    writeText("expired capture")
                    setLastModified(0L)
                }
            val fileStore =
                HomeDiagnosticsAugmentationModule.provideDiagnosticsArchiveFileStore(
                    context = context,
                    clock = DiagnosticsArchiveClock { 1_700_000_000_000L },
                    pcapController = pcapController,
                    captureRuntime = PcapCaptureRuntimeController(pcapController),
                )

            fileStore.cleanupPcapFiles()

            assertFalse(capture.exists())
        }
}
