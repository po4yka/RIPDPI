@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.PingProbeFailure
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
    val failure: PingProbeFailure? = null,
    val exitCode: Int? = null,
    val transmitted: Int? = null,
    val received: Int? = null,
) {
    companion object {
        fun replied(
            host: String,
            resolvedIpv4: String,
            rttMs: Double,
            jitterMs: Double? = null,
            rawOutput: String? = null,
            transmitted: Int? = null,
            received: Int? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                replied = true,
                rttMs = rttMs,
                jitterMs = jitterMs,
                rawOutput = rawOutput,
                transmitted = transmitted,
                received = received,
            )

        fun unreachable(
            host: String,
            resolvedIpv4: String?,
            rawOutput: String? = null,
            failure: PingProbeFailure = PingProbeFailure.NO_REPLY,
            exitCode: Int? = null,
            transmitted: Int? = null,
            received: Int? = null,
        ): PingProbeResult =
            PingProbeResult(
                host = host,
                resolvedIpv4 = resolvedIpv4,
                failure = failure,
                exitCode = exitCode,
                transmitted = transmitted,
                received = received,
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

    private val commandProfile by lazy { detectCommandProfile() }

    init {
        require(sampleCount in 1..MAX_PING_SAMPLES)
        require(timeoutSeconds in 1..MAX_PING_TIMEOUT_SECONDS)
    }

    override suspend fun ping(host: String): PingProbeResult {
        val addresses =
            try {
                resolveDetectionAddresses(host, TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()))
                    ?: return PingProbeResult.unreachable(host, null, failure = PingProbeFailure.DNS_TIMEOUT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                return PingProbeResult.unreachable(host, null, failure = PingProbeFailure.DNS_ERROR)
            } catch (_: SecurityException) {
                return PingProbeResult.unreachable(host, null, failure = PingProbeFailure.OS_ERROR)
            }
        val address =
            addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                ?: return PingProbeResult.unreachable(host, null, failure = PingProbeFailure.NO_IPV4)
        return runInterruptible(dispatchers.io) { runPing(host, address) }
    }

    private fun runPing(
        host: String,
        address: String,
    ): PingProbeResult {
        val profile =
            commandProfile
                ?: return PingProbeResult.unreachable(
                    host,
                    address,
                    "Unsupported ping numeric-output profile",
                    failure = PingProbeFailure.UNSUPPORTED_PLATFORM,
                )
        val command =
            listOf("ping") +
                (if (profile == PingCommandProfile.IPUTILS) listOf("-n") else emptyList()) +
                listOf("-c", sampleCount.toString(), "-W", timeoutSeconds.toString(), address)
        val process =
            try {
                processStarter(command)
            } catch (_: IOException) {
                return PingProbeResult.unreachable(host, address, failure = PingProbeFailure.OS_ERROR)
            } catch (_: SecurityException) {
                return PingProbeResult.unreachable(host, address, failure = PingProbeFailure.OS_ERROR)
            }
        return try {
            if (!process.waitFor(timeoutSeconds.toLong() * sampleCount, TimeUnit.SECONDS)) {
                return PingProbeResult.unreachable(host, address, failure = PingProbeFailure.TIMEOUT)
            }
            val output =
                readOutput(process)
                    ?: return PingProbeResult.unreachable(host, address, failure = PingProbeFailure.OUTPUT_LIMIT)
            val rtt = parseRttMs(output)
            if (process.exitValue() == 0 && rtt != null) {
                PingProbeResult.replied(
                    host,
                    address,
                    rtt,
                    jitterMs = parseJitterMs(output),
                    rawOutput = output,
                    transmitted = packetCounts(output)?.first,
                    received = packetCounts(output)?.second,
                )
            } else {
                PingProbeResult.unreachable(
                    host,
                    address,
                    output,
                    failure =
                        when (process.exitValue()) {
                            0 -> PingProbeFailure.MALFORMED_OUTPUT
                            1 -> PingProbeFailure.NO_REPLY
                            else -> PingProbeFailure.OS_ERROR
                        },
                    exitCode = process.exitValue(),
                    transmitted = packetCounts(output)?.first,
                    received = packetCounts(output)?.second,
                )
            }
        } catch (_: IOException) {
            PingProbeResult.unreachable(host, address, failure = PingProbeFailure.OS_ERROR)
        } finally {
            terminateAndReap(process)
        }
    }

    private fun detectCommandProfile(): PingCommandProfile? {
        val version = readCapabilities(listOf("ping", "-V"))
        if (version?.contains("iputils", ignoreCase = true) == true) return PingCommandProfile.IPUTILS
        val help = readCapabilities(listOf("ping", "--help"))
        return if (help?.contains("toybox", ignoreCase = true) == true) PingCommandProfile.TOYBOX else null
    }

    private fun readCapabilities(command: List<String>): String? {
        val process =
            try {
                processStarter(command)
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        return try {
            if (process.waitFor(CAPABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) readOutput(process) else null
        } catch (_: IOException) {
            null
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
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: replyRttsMs(output).takeIf { it.isNotEmpty() }?.average()

    // iputils prints mdev; toybox does not, so derive it (population deviation, as mdev) from the replies.
    internal fun parseJitterMs(output: String): Double? =
        summaryRttRegex
            .find(output)
            ?.groupValues
            ?.getOrNull(2)
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: replyRttsMs(output).takeIf { it.size >= 2 }?.let { rtts ->
                val mean = rtts.average()
                sqrt(rtts.sumOf { (it - mean) * (it - mean) } / rtts.size)
            }

    private fun replyRttsMs(output: String): List<Double> =
        singleReplyRttRegex
            .findAll(output)
            .mapNotNull {
                it.groupValues[1].toDoubleOrNull()?.takeIf { value ->
                    value.isFinite() &&
                        value >= 0.0
                }
            }.toList()

    private enum class PingCommandProfile {
        IPUTILS,
        TOYBOX,
    }

    internal fun packetCounts(output: String): Pair<Int, Int>? {
        val match = Regex("""(\d+) packets transmitted,\s*(\d+)(?: packets)? received""").find(output) ?: return null
        val sent = match.groupValues[1].toIntOrNull() ?: return null
        val received = match.groupValues[2].toIntOrNull() ?: return null
        return (sent to received).takeIf { sent > 0 && received in 0..sent }
    }

    private companion object {
        private const val PING_TIMEOUT_SECONDS = 3
        private const val CAPABILITY_TIMEOUT_MS = 500L
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
