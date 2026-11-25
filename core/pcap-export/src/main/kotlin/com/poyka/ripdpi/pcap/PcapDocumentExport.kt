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
    val failure = runCatching { writeDocument() }.exceptionOrNull() ?: return
    if (failure !is Exception) throw failure
    val removed =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { deleteDocument() }.getOrDefault(false)
        }
    throw when (failure) {
        is CancellationException -> failure
        else -> PcapDocumentWriteException(removed, failure)
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
            runCatching(open).getOrElse { error ->
                if (error !is Exception) throw error
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
        continuation.resume(document) { _, resource, _ ->
            runCatching { resource.close() }
        }
    }
