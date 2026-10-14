package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.RelayCloudflareTunnelModePublishLocalOrigin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

internal const val CloudflaredBinaryName = "ripdpi-cloudflared"
internal const val CloudflareOriginBinaryName = "ripdpi-cloudflare-origin"
private const val CloudflareVersionProbeTimeoutMs = 2_000L
private const val CloudflareVersionProbeReapTimeoutMs = 1_500L
private const val CloudflareVersionProbeMaxChars = 4 * 1024

internal open class CloudflarePublishBinaryLocator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val locator by lazy { HelperExecutableLocator.forContext(context) }

        /**
         * Returns the packaged Cloudflare helper in nativeLibraryDir. Android 10+ blocks exec()
         * from app data directories, so the helpers are never copied out of the APK.
         */
        open fun locate(
            binaryName: String,
            tunnelMode: String,
        ): File {
            // Gated: the Cloudflare helpers (ripdpi-cloudflared + ripdpi-cloudflare-origin) are
            // only resolved when publish mode is the active relay. Outside publish_local_origin
            // this is a programming error (the publish supervisor is the sole caller and is only
            // reached in publish mode), so fail loudly.
            check(tunnelMode == RelayCloudflareTunnelModePublishLocalOrigin) {
                "Cloudflare publish binary resolution requested outside publish_local_origin mode (mode=$tunnelMode)"
            }
            return when (binaryName) {
                CloudflaredBinaryName -> locator.locatePluggableTransport(binaryName)
                else -> locator.locateHelper(binaryName)
            }
        }
    }

internal open class CloudflarePublishVersionProbe internal constructor(
    private val timeoutMillis: Long,
) {
    private companion object {
        const val ReadBufferSize = 512
    }

    @Inject
    constructor() : this(CloudflareVersionProbeTimeoutMs)

    open fun probe(
        binary: File,
        versionArguments: List<String>,
    ): String? {
        var process: Process? = null
        var outputCapture: VersionOutputCapture? = null
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return try {
            val processBuilder =
                ProcessBuilder(
                    buildList {
                        add(binary.absolutePath)
                        addAll(versionArguments)
                    },
                ).redirectErrorStream(true)
            processBuilder.environment().scrubCloudflareHelperEnvironment()
            process =
                startProcess(processBuilder)
            val active = requireNotNull(process)
            runCatching { active.outputStream.close() }
            outputCapture = startOutputCapture(active.inputStream)
            if (!active.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                closeProbeProcess(active)
                null
            } else {
                outputCapture.await(deadlineNanos, active.inputStream)
            }
        } catch (error: CloudflareProbeCleanupException) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            val cleanupError =
                process
                    ?.let { active -> runCatching { closeProbeProcess(active) }.exceptionOrNull() }
            if (cleanupError != null) {
                cleanupError.addSuppressed(error)
                throw cleanupError
            }
            null
        } finally {
            process?.let(::closeProbeStreams)
            outputCapture?.close()
        }
    }

    internal open fun startProcess(processBuilder: ProcessBuilder): Process = processBuilder.start()

    private fun readBoundedVersionOutput(input: InputStream): String =
        input.bufferedReader().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(ReadBufferSize)
            while (output.length < CloudflareVersionProbeMaxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, CloudflareVersionProbeMaxChars - output.length))
                if (read < 0) break
                output.append(buffer, 0, read)
            }
            output.toString().trim()
        }

    private fun startOutputCapture(input: InputStream): VersionOutputCapture {
        val output = AtomicReference<String?>()
        val thread =
            Thread(
                {
                    output.set(runCatching { readBoundedVersionOutput(input).ifBlank { null } }.getOrNull())
                },
                "ripdpi-cloudflare-version-output",
            ).apply {
                isDaemon = true
                start()
            }
        return VersionOutputCapture(thread, output)
    }

    private fun closeProbeProcess(process: Process) {
        if (isCloudflareProcessAlive(process)) {
            runCatching { process.destroyForcibly() }
            val terminated =
                runCatching {
                    process.waitFor(CloudflareVersionProbeReapTimeoutMs, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
            if (!terminated || isCloudflareProcessAlive(process)) {
                throw CloudflareProbeCleanupException("Cloudflare version probe could not be terminated and reaped")
            }
        }
    }

    private fun closeProbeStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS
            .toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))
            .coerceAtLeast(1L)

    private inner class VersionOutputCapture(
        private val thread: Thread,
        private val output: AtomicReference<String?>,
    ) {
        fun await(
            deadlineNanos: Long,
            input: InputStream,
        ): String? {
            thread.join(remainingMillis(deadlineNanos))
            if (thread.isAlive) {
                runCatching { input.close() }
                thread.interrupt()
                thread.join(CloudflareVersionProbeReapTimeoutMs)
                if (thread.isAlive) {
                    throw CloudflareProbeCleanupException("Cloudflare version output reader could not be stopped")
                }
                return null
            }
            return output.get()
        }

        fun close() {
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(CloudflareVersionProbeReapTimeoutMs)
                if (thread.isAlive) {
                    throw CloudflareProbeCleanupException("Cloudflare version output reader could not be stopped")
                }
            }
        }
    }

    private class CloudflareProbeCleanupException(
        message: String,
    ) : IllegalStateException(message)
}
