@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

interface PingProber {
    suspend fun ping(host: String): PingProbeResult
}

data class PingProbeResult(
    val host: String,
    val resolvedIpv4: String?,
    val replied: Boolean,
    val rttMs: Double?,
    val jitterMs: Double? = null,
    val rawOutput: String? = null,
) {
    companion object {
        fun replied(
            host: String,
            resolvedIpv4: String,
            rttMs: Double,
            jitterMs: Double? = null,
            rawOutput: String? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                replied = true,
                rttMs = rttMs,
                jitterMs = jitterMs,
                rawOutput = rawOutput,
            )

        fun unreachable(
            host: String,
            resolvedIpv4: String?,
            rawOutput: String? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                replied = false,
                rttMs = null,
                rawOutput = rawOutput,
            )
    }
}

class SystemPingProber internal constructor(
    private val dispatchers: AppCoroutineDispatchers,
    private val sampleCount: Int,
    private val timeoutSeconds: Int,
    private val processStarter: (List<String>) -> Process,
) : PingProber {
    constructor(
        dispatchers: AppCoroutineDispatchers,
        sampleCount: Int = 1,
        timeoutSeconds: Int = PING_TIMEOUT_SECONDS,
    ) : this(dispatchers, sampleCount, timeoutSeconds, { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    })

    init {
        require(sampleCount in 1..MAX_PING_SAMPLES)
        require(timeoutSeconds in 1..MAX_PING_TIMEOUT_SECONDS)
    }

    override suspend fun ping(host: String): PingProbeResult {
        val address =
            try {
                resolveDetectionAddresses(host, TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()))
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull()
                    ?.hostAddress
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (address == null) return PingProbeResult.unreachable(host, resolvedIpv4 = null)
        return runInterruptible(dispatchers.io) { runPing(host, address) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runPing(
        host: String,
        address: String,
    ): PingProbeResult {
        val process =
            try {
                processStarter(listOf("ping", "-c", sampleCount.toString(), "-W", timeoutSeconds.toString(), address))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (_: Exception) {
                return PingProbeResult.unreachable(host, address)
            }
        return try {
            if (!process.waitFor(timeoutSeconds.toLong() * sampleCount, TimeUnit.SECONDS)) {
                return PingProbeResult.unreachable(host, address)
            }
            val output = readOutput(process) ?: return PingProbeResult.unreachable(host, address)
            val rtt = parseRttMs(output)
            if (process.exitValue() == 0 && rtt != null) {
                PingProbeResult.replied(host, address, rtt, jitterMs = parseJitterMs(output), rawOutput = output)
            } else {
                PingProbeResult.unreachable(host, address, output)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            PingProbeResult.unreachable(host, address)
        } finally {
            terminateAndReap(process)
        }
    }

    private fun readOutput(process: Process): String? =
        process.inputStream.bufferedReader().use { reader ->
            val output = CharArray(MAX_PING_OUTPUT_CHARS + 1)
            var size = 0
            while (size < output.size) {
                val count = reader.read(output, size, output.size - size)
                if (count == -1) return@use String(output, 0, size)
                size += count
            }
            null
        }

    private fun terminateAndReap(process: Process) {
        var interrupted = Thread.interrupted()
        try {
            if (process.isAlive) process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_CLEANUP_TIMEOUT_MS)
            while (process.isAlive && System.nanoTime() < deadline) {
                try {
                    process.waitFor((deadline - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            process.outputStream.closeQuietly()
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    // The summary average (iputils and toybox) wins; without it, the mean of all replies, so a
    // slow first (radio wake-up) reply does not stand for the whole sample.
    internal fun parseRttMs(output: String): Double? =
        summaryRttRegex
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?: replyRttsMs(output).takeIf { it.isNotEmpty() }?.average()

    // iputils prints mdev; toybox does not, so derive it (population deviation, as mdev) from the replies.
    internal fun parseJitterMs(output: String): Double? =
        summaryRttRegex
            .find(output)
            ?.groupValues
            ?.getOrNull(2)
            ?.toDoubleOrNull()
            ?: replyRttsMs(output).takeIf { it.size >= 2 }?.let { rtts ->
                val mean = rtts.average()
                sqrt(rtts.sumOf { (it - mean) * (it - mean) } / rtts.size)
            }

    private fun replyRttsMs(output: String): List<Double> =
        singleReplyRttRegex.findAll(output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

    private companion object {
        private const val PING_TIMEOUT_SECONDS = 3
        private const val MAX_PING_TIMEOUT_SECONDS = 30
        private const val MAX_PING_SAMPLES = 32
        private const val PROCESS_CLEANUP_TIMEOUT_MS = 1_000L
        private const val MAX_PING_OUTPUT_CHARS = 32_768
        private val singleReplyRttRegex = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")
        private val summaryRttRegex = Regex("""=\s*[0-9.]+/([0-9.]+)/[0-9.]+(?:/([0-9.]+))?\s*ms""")
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Stream cleanup must not replace cancellation or the probe outcome.
    }
}
