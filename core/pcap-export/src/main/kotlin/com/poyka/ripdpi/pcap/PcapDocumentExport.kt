package com.poyka.ripdpi.pcap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Owns the private copy until the picker is cancelled or the document is published. */
class PreparedPcapExport internal constructor(
    internal val file: File,
) : Closeable {
    override fun close() {
        file.delete()
    }
}

class PcapDocumentWriteException internal constructor(
    val documentRemoved: Boolean,
    cause: Exception,
) : IOException("Could not publish the PCAP document", cause)

fun resolvePcapCapture(
    directory: File,
    fileName: String,
): File {
    if (fileName != File(fileName).name || !fileName.endsWith(".pcap")) {
        throw FileNotFoundException("Invalid capture name")
    }
    val file = File(directory, fileName).canonicalFile
    if (file.parentFile != directory.canonicalFile || file.name != fileName || !file.isFile) {
        throw FileNotFoundException("Capture unavailable")
    }
    return file
}

internal suspend fun publishPcapDocument(
    writeDocument: suspend () -> Unit,
    deleteDocument: () -> Boolean,
) {
    try {
        writeDocument()
    } catch (failure: Exception) {
        val removed =
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { deleteDocument() }.getOrDefault(false)
            }
        if (failure is CancellationException) throw failure
        throw PcapDocumentWriteException(removed, failure)
    }
}

internal suspend fun <T : Closeable> openPcapDocument(
    cancelOpen: () -> Unit,
    open: () -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching(cancelOpen) }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val document =
            try {
                open()
            } catch (error: Exception) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
        continuation.resume(document) { _, resource, _ ->
            runCatching { resource.close() }
        }
    }
