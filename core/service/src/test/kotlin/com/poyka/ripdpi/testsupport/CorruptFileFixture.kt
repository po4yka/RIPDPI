package com.poyka.ripdpi.testsupport

import android.util.AtomicFile
import com.poyka.ripdpi.storage.AtomicTextFileWriter
import java.io.File
import java.io.IOException

internal class CorruptFileFixture(
    private val partialPayload: String = "torn",
) {
    fun failingWriter(): AtomicTextFileWriter =
        object : AtomicTextFileWriter {
            override fun write(
                file: File,
                payload: String,
            ) {
                file.parentFile?.mkdirs()
                val atomicFile = AtomicFile(file)
                val output = atomicFile.startWrite()
                try {
                    output.write(partialPayload.toByteArray(Charsets.UTF_8))
                    output.flush()
                } finally {
                    atomicFile.failWrite(output)
                }
                throw IOException("Simulated torn cache write")
            }
        }
}
