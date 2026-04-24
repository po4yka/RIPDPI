package com.poyka.ripdpi.storage

import android.util.AtomicFile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

fun interface AtomicTextFileWriter {
    @Throws(IOException::class)
    fun write(
        file: File,
        payload: String,
    )
}

@Singleton
class DefaultAtomicTextFileWriter
    @Inject
    constructor() : AtomicTextFileWriter {
        override fun write(
            file: File,
            payload: String,
        ) {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val output =
                try {
                    atomicFile.startWrite()
                } catch (error: IOException) {
                    throw error
                }
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AtomicTextFileWriterBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAtomicTextFileWriter(writer: DefaultAtomicTextFileWriter): AtomicTextFileWriter
}
