package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Severity
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class FileLogWriterTest {
    @Test
    fun `snapshot result does not swallow VM errors`() {
        val failure = AssertionError("fatal VM failure")

        try {
            resultCatchingExceptions<Unit> { throw failure }
            fail("Expected VM error to propagate")
        } catch (error: AssertionError) {
            assertSame(failure, error)
        }
    }

    @Test
    fun `writer and archive snapshot remain within utf8 byte budget`() {
        val filesDir = Files.createTempDirectory("bounded-file-log").toFile()
        val maxBytes = 96L
        val writer = FileLogWriter(filesDir, maxFileSize = maxBytes)

        writer.log(
            severity = Severity.Warn,
            message = "ж".repeat(200),
            tag = "Diagnostics",
            throwable = null,
        )

        val snapshot = requireNotNull(writer.readLogSnapshot())
        assertTrue(filesDir.resolve("logs/app_log.txt").length() <= maxBytes)
        assertTrue(snapshot.byteCount <= maxBytes)
        assertTrue(snapshot.content.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `snapshot tails rotated files within one aggregate budget`() {
        val filesDir = Files.createTempDirectory("bounded-file-log-tail").toFile()
        val maxBytes = 128L
        val writer = FileLogWriter(filesDir, maxFileSize = maxBytes)

        repeat(6) { index ->
            writer.log(Severity.Warn, "entry-$index-${"x".repeat(40)}", "Diagnostics", null)
        }

        val snapshot = requireNotNull(writer.readLogSnapshot())
        assertTrue(snapshot.byteCount <= maxBytes)
        assertTrue(snapshot.content.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `truncation marker survives writer restart and rotation`() {
        val filesDir = Files.createTempDirectory("bounded-file-log-restart").toFile()
        val maxBytes = 96L
        FileLogWriter(filesDir, maxFileSize = maxBytes).apply {
            log(Severity.Warn, "ж".repeat(200), "Diagnostics", null)
            log(Severity.Warn, "new-current-entry", "Diagnostics", null)
        }

        val restartedSnapshot = requireNotNull(FileLogWriter(filesDir, maxFileSize = maxBytes).readLogSnapshot())

        assertTrue(restartedSnapshot.byteCount <= maxBytes)
        assertTrue(restartedSnapshot.truncated)
        assertTrue(filesDir.resolve("logs/app_log.prev.truncated").exists())
    }
}
