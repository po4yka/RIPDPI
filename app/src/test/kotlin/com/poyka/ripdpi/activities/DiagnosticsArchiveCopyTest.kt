package com.poyka.ripdpi.activities

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class DiagnosticsArchiveCopyTest {
    @Test
    fun `copyDiagnosticsArchive copies source bytes into destination stream`() {
        val source = Files.createTempFile("diagnostics-archive", ".zip").toFile().apply { writeText("archive-data") }
        val destination = ByteArrayOutputStream()

        copyDiagnosticsArchive(source = source, destination = destination)

        assertArrayEquals(source.readBytes(), destination.toByteArray())
    }
}
