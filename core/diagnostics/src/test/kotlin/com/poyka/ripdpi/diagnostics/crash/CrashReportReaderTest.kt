package com.poyka.ripdpi.diagnostics.crash

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createReader(): CrashReportReader = CrashReportReader(tempFolder.root)

    private fun writeCrashFile(content: String) {
        val dir = File(tempFolder.root, CrashReportWriter.CRASH_DIR_NAME)
        dir.mkdirs()
        File(dir, CrashReportWriter.CRASH_FILE_NAME).writeText(content)
    }

    private val sampleReport =
        CrashReport(
            timestamp = "2026-03-26T14:30:00.000+0000",
            exceptionClass = "java.lang.NullPointerException",
            message = "test message",
            stacktrace = "java.lang.NullPointerException: test message\n\tat Foo.bar(Foo.kt:42)",
            threadName = "main",
            deviceModel = "Pixel 8",
            deviceManufacturer = "Google",
            androidVersion = "15",
            sdkInt = 35,
            appVersionName = "1.0.0",
            appVersionCode = 10,
        )

    @Test
    fun `read returns null when no crash file exists`() =
        runTest {
            val reader = createReader()

            assertNull(reader.read())
        }

    @Test
    fun `read parses valid crash report`() =
        runTest {
            writeCrashFile(Json.encodeToString(sampleReport))
            val reader = createReader()

            val report = reader.read()

            assertNotNull(report)
            assertEquals("java.lang.NullPointerException", report!!.exceptionClass)
            assertEquals("test message", report.message)
            assertEquals("main", report.threadName)
            assertEquals(35, report.sdkInt)
        }

    @Test
    fun `read returns null and deletes corrupt file`() =
        runTest {
            writeCrashFile("not valid json {{{")
            val reader = createReader()
            val crashFile =
                File(
                    tempFolder.root,
                    "${CrashReportWriter.CRASH_DIR_NAME}/${CrashReportWriter.CRASH_FILE_NAME}",
                )

            val report = reader.read()

            assertNull(report)
            assertFalse("Corrupt file should be deleted", crashFile.exists())
        }

    @Test
    fun `delete removes crash file`() {
        writeCrashFile(Json.encodeToString(sampleReport))
        val reader = createReader()
        val crashFile =
            File(
                tempFolder.root,
                "${CrashReportWriter.CRASH_DIR_NAME}/${CrashReportWriter.CRASH_FILE_NAME}",
            )

        assertTrue(crashFile.exists())
        reader.delete()
        assertFalse(crashFile.exists())
    }

    @Test
    fun `delete is safe when file does not exist`() {
        val reader = createReader()

        reader.delete() // should not throw
    }

    @Test
    fun `read returns null and deletes empty file`() =
        runTest {
            writeCrashFile("")
            val reader = createReader()
            val crashFile =
                File(
                    tempFolder.root,
                    "${CrashReportWriter.CRASH_DIR_NAME}/${CrashReportWriter.CRASH_FILE_NAME}",
                )

            val report = reader.read()

            assertNull(report)
            assertFalse("Empty file should be deleted", crashFile.exists())
        }

    @Test
    fun `read returns null and deletes file with non-object JSON`() =
        runTest {
            writeCrashFile("""[1, 2, 3]""")
            val reader = createReader()
            val crashFile =
                File(
                    tempFolder.root,
                    "${CrashReportWriter.CRASH_DIR_NAME}/${CrashReportWriter.CRASH_FILE_NAME}",
                )

            val report = reader.read()

            assertNull(report)
            assertFalse("Non-object JSON file should be deleted", crashFile.exists())
        }

    @Test
    fun `buildShareText formats report correctly`() {
        val reader = createReader()

        val (title, body) = reader.buildShareText(sampleReport)

        assertEquals("RIPDPI Crash Report", title)
        assertTrue(body.contains("Version: 1.0.0 (10)"))
        assertTrue(body.contains("Device: Google Pixel 8 (Android 15, SDK 35)"))
        assertTrue(body.contains("Thread: main"))
        assertTrue(body.contains("NullPointerException"))
    }
}
