package com.poyka.ripdpi.backup

import java.io.File

/** Owns the cache file backing an in-flight redacted backup share. */
internal class BackupShareTempFileOwner : AutoCloseable {
    private var pendingFile: File? = null

    fun replace(file: File) {
        clear()
        pendingFile = file
    }

    fun current(): File? = pendingFile

    fun clear() {
        val file = pendingFile
        pendingFile = null
        if (file != null) {
            runCatching { file.delete() }
        }
    }

    override fun close() = clear()
}
