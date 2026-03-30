package com.poyka.ripdpi.diagnostics.crash

import com.poyka.ripdpi.diagnostics.BreadcrumbLogWriter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashReportWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun installWriter(
        crashDir: File = File(tempFolder.root, CrashReportWriter.CRASH_DIR_NAME),
        previousHandler: Thread.UncaughtExceptionHandler? = null,
    ): Pair<Thread.UncaughtExceptionHandler, File> {
        val field =
            CrashReportWriter::class.java.getDeclaredConstructor(
                File::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Thread.UncaughtExceptionHandler::class.java,
                Function0::class.java,
            )
        field.isAccessible = true
        val noBreadcrumbs: () -> List<BreadcrumbLogWriter.BreadcrumbEntry> = { emptyList() }
        val writer =
            field.newInstance(crashDir, "1.2.3", 42L, previousHandler, noBreadcrumbs)
                as Thread.UncaughtExceptionHandler
        return writer to File(crashDir, CrashReportWriter.CRASH_FILE_NAME)
    }

    @Test
    fun `writes crash report JSON on uncaught exception`() {
        val (writer, crashFile) = installWriter()

        val exception = RuntimeException("test boom")
        writer.uncaughtException(Thread.currentThread(), exception)

        assertTrue("Crash file should exist", crashFile.exists())
        val report = json.decodeFromString<CrashReport>(crashFile.readText())
        assertEquals("java.lang.RuntimeException", report.exceptionClass)
        assertEquals("test boom", report.message)
        assertTrue(report.stacktrace.contains("RuntimeException"))
        assertEquals("1.2.3", report.appVersionName)
        assertEquals(42L, report.appVersionCode)
        assertTrue(report.timestamp.isNotEmpty())
    }

    @Test
    fun `chains to previous handler after writing`() {
        var previousCalled = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> previousCalled = true }
        val (writer, _) = installWriter(previousHandler = previous)

        writer.uncaughtException(Thread.currentThread(), RuntimeException("test"))

        assertTrue("Previous handler must be called", previousCalled)
    }

    @Test
    fun `chains to previous handler even when write fails`() {
        var previousCalled = false
        val previous = Thread.UncaughtExceptionHandler { _, _ -> previousCalled = true }
        val readOnlyDir = File(tempFolder.root, "readonly")
        readOnlyDir.mkdirs()
        readOnlyDir.setReadOnly()

        val (writer, _) =
            installWriter(
                crashDir = File(readOnlyDir, CrashReportWriter.CRASH_DIR_NAME),
                previousHandler = previous,
            )

        writer.uncaughtException(Thread.currentThread(), RuntimeException("test"))

        assertTrue("Previous handler must be called even on write failure", previousCalled)
    }

    @Test
    fun `escapes special characters in exception message`() {
        val (writer, crashFile) = installWriter()

        val exception = RuntimeException("line1\nline2\t\"quoted\"\\backslash")
        writer.uncaughtException(Thread.currentThread(), exception)

        val report = json.decodeFromString<CrashReport>(crashFile.readText())
        assertNotNull("Should parse without error", report)
        assertEquals("line1\nline2\t\"quoted\"\\backslash", report.message)
    }

    @Test
    fun `overwrites previous crash report`() {
        val (writer, crashFile) = installWriter()

        writer.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        val firstReport = json.decodeFromString<CrashReport>(crashFile.readText())
        assertEquals("first", firstReport.message)

        writer.uncaughtException(Thread.currentThread(), IllegalStateException("second"))
        val secondReport = json.decodeFromString<CrashReport>(crashFile.readText())
        assertEquals("second", secondReport.message)
        assertEquals("java.lang.IllegalStateException", secondReport.exceptionClass)
    }

    @Test
    fun `captures thread name`() {
        val (writer, crashFile) = installWriter()
        val namedThread = Thread("test-thread-42")

        writer.uncaughtException(namedThread, RuntimeException("boom"))

        val report = json.decodeFromString<CrashReport>(crashFile.readText())
        assertEquals("test-thread-42", report.threadName)
    }
}
