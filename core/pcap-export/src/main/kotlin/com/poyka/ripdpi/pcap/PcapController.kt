package com.poyka.ripdpi.pcap

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.poyka.ripdpi.jni.PcapBridge
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level Kotlin facade around the [PcapBridge] JNI surface.
 *
 * Lifetime model: one capture per tunnel session. Caller passes the
 * existing session handle (from Tun2SocksTunnel) to start / stop. The
 * native side enforces single-capture-per-session.
 *
 * SAF integration: [redactToUri] takes a Uri obtained from
 * `ActivityResultContracts.CreateDocument` and opens it via
 * ContentResolver, calls `ParcelFileDescriptor.detachFd()` to transfer
 * ownership to Rust, then invokes `jniPcapRedactToFile`. The contract
 * is enforced HERE - the only call site for `jniPcapRedactToFile` - so
 * the fd-ownership rule in the Rust SAFETY block holds.
 */
@Singleton
class PcapController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val contentResolver: ContentResolver get() = context.contentResolver

        val captureDirectory: File
            get() = File(context.filesDir, "pcap")

        /**
         * Returns capture-set id on success, 0 on failure. Caller stores
         * the id to call stop later, though the single-capture-per-session
         * rule means the session handle alone is sufficient.
         */
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
                val json = PcapBridge.jniPcapStop(sessionHandle)
                decodeStopResult(json)
            }

        suspend fun listCaptures(captureDir: File): List<PcapCaptureMetadata> =
            withContext(Dispatchers.IO) {
                if (!captureDir.exists()) return@withContext emptyList()
                val json = PcapBridge.jniPcapListCaptures(captureDir.absolutePath)
                decodeMetadataList(json)
            }

        /**
         * Redact the source .pcap file's endpoint IPs and stream the
         * result to the user-selected Uri. The Uri must come from a SAF
         * CreateDocument result. Returns bytes written.
         *
         * Closes the ParcelFileDescriptor's fd by transferring ownership
         * to native (detachFd). Native side closes the fd on completion.
         */
        suspend fun redactToUri(
            source: File,
            dest: Uri,
        ): Long =
            withContext(Dispatchers.IO) {
                val pfd = contentResolver.openFileDescriptor(dest, "w") ?: return@withContext 0L
                // detachFd transfers ownership to Rust. The PFD wrapper no longer
                // owns the fd after this call - per AOSP docs, the wrapper goes
                // into a "detached" state and close() on PFD is a no-op. Rust
                // closes the fd via OwnedFd::from_raw_fd drop. See SAFETY block
                // in native/rust/.../session/pcap.rs::pcap_redact_entry.
                val detachedFd = pfd.detachFd()
                PcapBridge.jniPcapRedactToFile(source.absolutePath, detachedFd)
            }

        companion object {
            /** 16 MiB per file, 4-file retention for bounded local captures. */
            const val DEFAULT_MAX_FILE_BYTES: Long = 16L * 1024L * 1024L
            const val DEFAULT_MAX_FILES: Int = 4

            private val JSON = RipDpiJson

            private fun decodeMetadataList(json: String?): List<PcapCaptureMetadata> {
                if (json == null) return emptyList()
                return try {
                    JSON.decodeFromString(json)
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            private fun decodeStopResult(json: String?): PcapStopResult {
                if (json == null) {
                    return PcapStopResult(
                        wasActive = true,
                        files = emptyList(),
                        failure = "native_call",
                    )
                }
                return try {
                    JSON.decodeFromString(json)
                } catch (_: Throwable) {
                    PcapStopResult(
                        wasActive = true,
                        files = emptyList(),
                        failure = "invalid_native_response",
                    )
                }
            }
        }
    }
