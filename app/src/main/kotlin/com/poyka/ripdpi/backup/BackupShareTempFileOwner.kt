package com.poyka.ripdpi.backup

import java.io.File
import java.util.UUID

/** Owns an unlaunched backup share file; launched files remain in cache for delayed reads. */
internal class BackupShareTempFileOwner : AutoCloseable {
    private var pendingFile: File? = null

    fun createFile(
        cacheDir: File,
        filenamePrefix: String,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        pruneExpired(cacheDir, nowMs)
        val directory = File(cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        val file = File(directory, "$filenamePrefix-${UUID.randomUUID()}.json")
        replace(file)
        return file
    }

    fun pruneExpired(
        cacheDir: File,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        File(cacheDir, SHARE_CACHE_DIR).listFiles()?.forEach { file ->
            val ageMs = nowMs - file.lastModified()
            if (file.isFile && ageMs >= RETENTION_MS && ageMs >= 0L) {
                runCatching { file.delete() }
            }
        }
    }

    fun replace(file: File) {
        clear()
        pendingFile = file
    }

    fun current(): File? = pendingFile

    /** Leave a launched share in cache for a recipient that opens its URI later. */
    fun releaseForShare() {
        pendingFile = null
    }

    fun clear() {
        val file = pendingFile
        pendingFile = null
        if (file != null) {
            runCatching { file.delete() }
        }
    }

    override fun close() = clear()

    private companion object {
        const val SHARE_CACHE_DIR = "backup-share"
        const val RETENTION_MS = 24 * 60 * 60 * 1000L
    }
}
