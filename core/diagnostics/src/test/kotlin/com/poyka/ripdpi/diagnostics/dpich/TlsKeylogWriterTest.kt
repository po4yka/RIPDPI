package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TlsKeylogWriterTest {
    @Test
    fun appendWritesSslKeylogFormatLine() =
        runBlocking {
            val file = tempFile()
            val writer = TlsKeylogWriter(file)

            writer.append("CLIENT_RANDOM", "abc123", "def456")

            assertEquals("CLIENT_RANDOM abc123 def456\n", file.readText())
        }

    @Test
    fun concurrentAppendsAreSerialized() =
        runBlocking {
            val file = tempFile()
            val writer = TlsKeylogWriter(file)

            (1..100)
                .map { index ->
                    async(Dispatchers.Default) {
                        writer.append("CLIENT_RANDOM", "random$index", "secret$index")
                    }
                }.awaitAll()

            val lines = file.readLines()
            assertEquals(100, lines.size)
            assertEquals(100, lines.toSet().size)
            assertTrue(lines.all { line -> line.split(" ").size == 3 })
        }

    @Test
    fun rotateRenamesCurrentFileAndCreatesFreshFile() =
        runBlocking {
            val file = tempFile()
            file.writeText("CLIENT_RANDOM abc def\n")
            val writer = TlsKeylogWriter(file)

            val rotated = writer.rotate(timestampSeconds = 1_700_000_000)

            assertEquals("${file.absolutePath}.1700000000", rotated.absolutePath)
            assertEquals("CLIENT_RANDOM abc def\n", rotated.readText())
            assertTrue(file.exists())
            assertEquals("", file.readText())
        }

    @Test
    fun purgeOldDeletesRotatedFilesOlderThanRetention() {
        val file = tempFile()
        val old = File("${file.absolutePath}.old").apply { writeText("old") }
        val fresh = File("${file.absolutePath}.fresh").apply { writeText("fresh") }
        val now = 1_700_000_000_000L
        old.setLastModified(now - 48.hoursMs)
        fresh.setLastModified(now - 12.hoursMs)

        val deleted = TlsKeylogWriter(file).purgeOld(nowMs = now, retention = KeylogRetentionPolicy(hoursToKeep = 24))

        assertEquals(listOf(old.absolutePath), deleted.map(File::getAbsolutePath))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    private fun tempFile(): File = createTempDirectory().toFile().resolve("tls.keys")

    private val Int.hoursMs: Long get() = this * 60L * 60L * 1_000L
}
