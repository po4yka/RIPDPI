package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.diagnostics.crash.CrashReportReader
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MainCrashReportCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `buildShareText delegates to crash report reader`() {
        val reader = CrashReportReader(tempFolder.root)
        val coordinator = MainCrashReportCoordinator(reader)
        val report =
            CrashReport(
                timestamp = "2026-04-12T12:00:00.000+0000",
                stacktrace = "java.lang.IllegalStateException: boom",
                threadName = "main",
                deviceManufacturer = "Google",
                deviceModel = "Pixel 9",
                androidVersion = "16",
                sdkInt = 36,
                appVersionName = "1.0.0",
                appVersionCode = 42,
            )

        val (title, body) = coordinator.buildShareText(report)

        assertEquals("RIPDPI Crash Report", title)
        assertTrue(body.contains("Version: 1.0.0 (42)"))
        assertTrue(body.contains("IllegalStateException"))
    }

    @Test
    fun `dismiss clears state callback and deletes crash file`() =
        runTest {
            val crashDir = tempFolder.newFolder("crash-reports")
            val crashFile = crashDir.resolve("crash_latest.json")
            crashFile.writeText("{}", Charsets.UTF_8)
            val reader = CrashReportReader(tempFolder.root)
            val coordinator = MainCrashReportCoordinator(reader)
            var dismissed = false

            coordinator.dismiss(this) {
                dismissed = true
            }
            advanceUntilIdle()

            assertTrue(dismissed)
            assertFalse(crashFile.exists())
        }
}
