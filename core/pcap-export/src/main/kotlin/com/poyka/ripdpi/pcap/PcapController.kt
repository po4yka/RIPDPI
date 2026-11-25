package com.poyka.ripdpi.pcap

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.poyka.ripdpi.jni.PcapBridge
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PcapController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val contentResolver: ContentResolver get() = context.contentResolver

        val captureDirectory: File
            get() = File(context.filesDir, "pcap")

        suspend fun start(
            sessionHandle: Long,
            captureDir: File,
            maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
            maxFiles: Int = DEFAULT_MAX_FILES,
        ): Long =
            withContext(Dispatchers.IO) {
                if (!captureDir.exists()) captureDir.mkdirs()
                PcapBridge.jniPcapStart(
                    sessionHandle = sessionHandle,
                    captureDir = captureDir.absolutePath,
                    maxFileBytes = maxFileBytes,
                    maxFiles = maxFiles,
                )
            }

        suspend fun stop(sessionHandle: Long): PcapStopResult =
            withContext(Dispatchers.IO) {
                decodeStopResult(PcapBridge.jniPcapStop(sessionHandle, finishedOnly = false))
            }

        suspend fun pollCompletion(sessionHandle: Long): PcapStopResult? =
            withContext(Dispatchers.IO) {
                decodeStopResult(PcapBridge.jniPcapStop(sessionHandle, finishedOnly = true))
                    .takeUnless { it.pending }
            }

        suspend fun listCaptures(captureDir: File): List<PcapCaptureMetadata> =
            withContext(Dispatchers.IO) {
                val json = PcapBridge.jniPcapListCaptures(captureDir.absolutePath, cleanupOnly = false)
                decodeMetadataList(json)
            }

        suspend fun cleanupCaptures(captureDir: File = captureDirectory): Unit =
            withContext(Dispatchers.IO) {
                if (PcapBridge.jniPcapListCaptures(captureDir.absolutePath, cleanupOnly = true) == null) {
                    throw IOException("Native capture storage cleanup failed")
                }
            }

        suspend fun prepareRedactedExport(fileName: String): PreparedPcapExport {
            var draft: PreparedPcapExport? = null
            try {
                return withContext(Dispatchers.IO) {
                    val source = resolvePcapCapture(captureDirectory, fileName)
                    val staged = PcapExportStaging.create(context.cacheDir)
                    draft = staged
                    val bytes = redactToPrivateFile(source, staged.file)
                    if (bytes == IncompleteCapture) throw EOFException("Capture ends in an incomplete record")
                    if (bytes <= 0 || bytes != staged.file.length()) throw IOException("Could not redact capture")
                    staged
                }.also { draft = null }
            } finally {
                draft?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
            }
        }

        suspend fun publishRedactedExport(
            staged: PreparedPcapExport?,
            destination: Uri,
        ) {
            try {
                publishPcapDocument(
                    writeDocument = {
                        val source = staged?.file ?: throw FileNotFoundException("Prepared capture unavailable")
                        writeRedactedDocument(source, destination)
                    },
                    deleteDocument = {
                        DocumentsContract.isDocumentUri(context, destination) &&
                            DocumentsContract.deleteDocument(contentResolver, destination)
                    },
                )
            } finally {
                staged?.close()
            }
        }

        private suspend fun writeRedactedDocument(
            source: File,
            destination: Uri,
        ) = withContext(Dispatchers.IO) {
            if (!DocumentsContract.isDocumentUri(context, destination)) throw IOException("Invalid document")
            source.inputStream().use { input ->
                val signal = CancellationSignal()
                openPcapDocument(signal::cancel) {
                    contentResolver.openFileDescriptor(destination, "wt", signal)
                        ?: throw IOException("Could not open PCAP document")
                }.use { descriptor ->
                    runInterruptible { copyToProvider(input, descriptor) }
                }
            }
        }

        private fun copyToProvider(
            input: InputStream,
            descriptor: ParcelFileDescriptor,
        ) {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                try {
                    input.copyTo(Channels.newOutputStream(output.channel))
                    if (descriptor.canDetectErrors()) descriptor.checkError()
                } catch (error: IOException) {
                    if (descriptor.canDetectErrors()) {
                        runCatching { descriptor.closeWithError("Could not write PCAP") }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                    }
                    throw error
                }
            }
        }

        private fun redactToPrivateFile(
            source: File,
            destination: File,
        ): Long {
            val bridge =
                try {
                    PcapBridge
                } catch (error: LinkageError) {
                    throw IOException("Could not load native PCAP redaction", error)
                }
            val sourcePath = source.absolutePath
            return ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_WRITE_ONLY).use { descriptor ->
                val fd = descriptor.detachFd()
                try {
                    bridge.jniPcapRedactToFile(sourcePath, fd)
                } catch (error: UnsatisfiedLinkError) {
                    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                    throw IOException("Could not call native PCAP redaction", error)
                }
            }
        }

        companion object {
            const val DEFAULT_MAX_FILE_BYTES: Long = 16L * 1024L * 1024L
            const val DEFAULT_MAX_FILES: Int = 3

            private const val IncompleteCapture = -1L
            private val JSON = RipDpiJson

            internal fun decodeMetadataList(json: String?): List<PcapCaptureMetadata> {
                if (json == null) throw IOException("Native capture listing failed")
                return try {
                    JSON.decodeFromString(json)
                } catch (error: SerializationException) {
                    throw IOException("Invalid native capture listing", error)
                }
            }

            private fun decodeStopResult(json: String?): PcapStopResult {
                if (json == null) throw IOException("Native capture stop failed")
                return try {
                    JSON.decodeFromString(json)
                } catch (error: SerializationException) {
                    throw IOException("Invalid capture stop response", error)
                }
            }
        }
    }
