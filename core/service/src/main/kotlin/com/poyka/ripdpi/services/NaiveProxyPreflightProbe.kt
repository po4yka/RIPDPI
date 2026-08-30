package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val NaiveProxyProbeTimeoutMillis = 2_000L
private const val NaiveProxyProbeCleanupTimeoutMillis = 250L

internal data class NaiveProxyBinaryRef(
    val absolutePath: String,
)

internal sealed interface NaiveProxyPreflightResult {
    data class Probed(
        val probe: NaiveProxyProbe,
    ) : NaiveProxyPreflightResult

    data class Rejected(
        val message: String,
    ) : NaiveProxyPreflightResult
}

internal fun interface NaiveProxyPreflightProbe {
    suspend fun run(binary: NaiveProxyBinaryRef): NaiveProxyPreflightResult
}

@Singleton
internal class DefaultNaiveProxyPreflightProbe
    internal constructor(
        private val ioDispatcher: CoroutineDispatcher,
        private val timeoutMillis: Long,
    ) : NaiveProxyPreflightProbe {
        @Inject
        constructor(dispatchers: AppCoroutineDispatchers) : this(
            ioDispatcher = dispatchers.io,
            timeoutMillis = NaiveProxyProbeTimeoutMillis,
        )

        override suspend fun run(binary: NaiveProxyBinaryRef): NaiveProxyPreflightResult =
            runInterruptible(ioDispatcher) {
                runProbe(binary)
            }

        private fun runProbe(binary: NaiveProxyBinaryRef): NaiveProxyPreflightResult {
            val process =
                try {
                    startProbeProcess(binary)
                } catch (rejection: NaiveProxyProbeRejection) {
                    return NaiveProxyPreflightResult.Rejected(rejection.publicMessage)
                }
            return try {
                val line = readProbeLine(process)
                val probe =
                    NaiveProxyProbeParser.parse(line)
                        ?: rejectProbe("NaiveProxy pre-launch probe returned an invalid capability line")
                terminateAndReap(process) ?: NaiveProxyPreflightResult.Probed(probe)
            } catch (rejection: NaiveProxyProbeRejection) {
                terminateAndReap(process) ?: NaiveProxyPreflightResult.Rejected(rejection.publicMessage)
            } catch (error: InterruptedException) {
                terminateAndReap(process)
                Thread.currentThread().interrupt()
                throw error
            } catch (_: IOException) {
                terminateAndReap(process)
                    ?: NaiveProxyPreflightResult.Rejected("NaiveProxy pre-launch probe output could not be read")
            } finally {
                process.outputStream.closeQuietly()
                process.inputStream.closeQuietly()
                process.errorStream.closeQuietly()
            }
        }

        private fun startProbeProcess(binary: NaiveProxyBinaryRef): Process =
            try {
                ProcessBuilder(binary.absolutePath, "--probe")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                rejectProbe("NaiveProxy pre-launch probe could not start")
            } catch (_: SecurityException) {
                rejectProbe("NaiveProxy pre-launch probe could not start")
            }

        private fun readProbeLine(process: Process): String {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                rejectProbe("NaiveProxy pre-launch probe timed out")
            }
            val lines =
                process.inputStream
                    .bufferedReader()
                    .useLines { output -> output.filter(String::isNotBlank).toList() }
            if (process.exitValue() != 0) {
                rejectProbe("NaiveProxy helper does not support the required probe")
            }
            return lines.singleOrNull()
                ?: rejectProbe("NaiveProxy pre-launch probe did not return exactly one capability line")
        }

        private fun terminateAndReap(process: Process): NaiveProxyPreflightResult.Rejected? {
            var reaped = !process.isAlive
            if (!reaped) {
                process.destroy()
                reaped = process.waitFor(NaiveProxyProbeCleanupTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            if (!reaped) {
                process.destroyForcibly()
                reaped = process.waitFor(NaiveProxyProbeCleanupTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            return if (reaped) {
                null
            } else {
                NaiveProxyPreflightResult.Rejected("NaiveProxy pre-launch probe could not be terminated")
            }
        }
    }

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Cleanup is best-effort; process reaping determines whether the probe is safe to leave.
    }
}

private class NaiveProxyProbeRejection(
    val publicMessage: String,
) : RuntimeException(publicMessage)

private fun rejectProbe(message: String): Nothing = throw NaiveProxyProbeRejection(message)

internal fun File.asNaiveProxyBinaryRef(): NaiveProxyBinaryRef = NaiveProxyBinaryRef(absolutePath)
