package com.poyka.ripdpi.activities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files

class DiagnosticsArchiveCopyTest {
    @Test
    fun `copyDiagnosticsArchive copies source bytes into destination stream`() {
        val source = Files.createTempFile("diagnostics-archive", ".zip").toFile().apply { writeText("archive-data") }
        val destination = ByteArrayOutputStream()

        copyDiagnosticsArchive(source = source) { destination }

        assertArrayEquals(source.readBytes(), destination.toByteArray())
    }

    @Test
    fun `copyDiagnosticsArchive fails when destination stream cannot be opened`() {
        val source = Files.createTempFile("diagnostics-archive", ".zip").toFile().apply { writeText("archive-data") }

        try {
            copyDiagnosticsArchive(source = source) { null }
            fail("Expected copyDiagnosticsArchive to fail when no destination stream is available")
        } catch (_: IOException) {
            Unit
        }
    }
}
