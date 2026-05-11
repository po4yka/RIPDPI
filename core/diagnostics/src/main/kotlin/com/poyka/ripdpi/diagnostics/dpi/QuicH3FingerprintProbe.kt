package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest

enum class QuicFingerprint {
    CHROME_120,
    FIREFOX_121,
    GENERIC_V1,
    VN_PROBE,
}

enum class QuicProbeVerdict {
    QUIC_OK,
    QUIC_DROPPED,
    QUIC_VN_REJECTED,
    QUIC_DPI_FINGERPRINT_BLOCK,
    QUIC_DEGRADED,
    QUIC_TIMEOUT,
}

data class QuicProbeResult(
    val target: String,
    val verdict: QuicProbeVerdict,
    val chromeOk: Boolean,
    val firefoxOk: Boolean,
    val genericOk: Boolean,
    val vnOk: Boolean,
    val udpReachable: Boolean,
    val serverInitialLatencyMs: Long?,
)

data class QuicProbeSample(
    val response: ByteArray?,
    val latencyMs: Long?,
)

interface QuicUdpProbe {
    suspend fun udpReachable(
        target: String,
        port: Int,
        timeoutMs: Int,
    ): Boolean

    suspend fun sendInitial(
        target: String,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample
}

class QuicH3FingerprintProbe(
    private val socket: QuicUdpProbe = DatagramSocketQuicUdpProbe(),
    private val packetFactory: QuicFingerprintFactory = QuicFingerprintFactory,
    private val port: Int = DefaultQuicPort,
    private val timeoutMs: Int = DefaultTimeoutMs,
    private val concurrency: Int = DefaultConcurrency,
) {
    suspend fun check(target: String): QuicProbeResult {
        val udpReachable = socket.udpReachable(target = target, port = port, timeoutMs = timeoutMs)
        if (!udpReachable) {
            return dropped(target = target, udpReachable = false)
        }

        val chrome = probeFingerprint(target, QuicFingerprint.CHROME_120, ::isQuicV1LongHeader)
        val firefox = probeFingerprint(target, QuicFingerprint.FIREFOX_121, ::isQuicV1LongHeader)
        val generic = probeFingerprint(target, QuicFingerprint.GENERIC_V1, ::isQuicV1LongHeader)
        val vn = probeFingerprint(target, QuicFingerprint.VN_PROBE, ::isVersionNegotiationPacket)

        val chromeOk = chrome.ok
        val firefoxOk = firefox.ok
        val genericOk = generic.ok
        val vnOk = vn.ok
        val v1OkCount = listOf(chromeOk, firefoxOk, genericOk).count { value -> value }
        val latencyMs = listOf(chrome, firefox, generic).firstOrNull(QuicProbeOutcome::ok)?.latencyMs
        val verdict =
            when {
                chromeOk && firefoxOk && genericOk && vnOk -> QuicProbeVerdict.QUIC_OK
                v1OkCount == 0 && !vnOk -> QuicProbeVerdict.QUIC_DROPPED
                chromeOk && firefoxOk && genericOk && !vnOk -> QuicProbeVerdict.QUIC_VN_REJECTED
                !chromeOk && (firefoxOk || genericOk) -> QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK
                listOf(chrome, firefox, generic, vn).all(QuicProbeOutcome::timedOut) -> QuicProbeVerdict.QUIC_TIMEOUT
                else -> QuicProbeVerdict.QUIC_DEGRADED
            }
        return QuicProbeResult(
            target = target,
            verdict = verdict,
            chromeOk = chromeOk,
            firefoxOk = firefoxOk,
            genericOk = genericOk,
            vnOk = vnOk,
            udpReachable = true,
            serverInitialLatencyMs = latencyMs,
        )
    }

    suspend fun checkAll(targets: List<String>): List<QuicProbeResult> =
        coroutineScope {
            val permits = Semaphore(concurrency.coerceAtLeast(1))
            val checks =
                targets.map { target ->
                    async {
                        permits.withPermit { check(target) }
                    }
                }
            checks.awaitAll()
        }

    private suspend fun probeFingerprint(
        target: String,
        fingerprint: QuicFingerprint,
        classify: (ByteArray) -> Boolean,
    ): QuicProbeOutcome {
        val packet = packetFactory.create(fingerprint = fingerprint, target = target)
        val sample =
            socket.sendInitial(
                target = target,
                port = port,
                fingerprint = fingerprint,
                packet = packet,
                timeoutMs = timeoutMs,
            )
        val response = sample.response ?: return QuicProbeOutcome(ok = false, timedOut = true, latencyMs = null)
        return QuicProbeOutcome(ok = classify(response), timedOut = false, latencyMs = sample.latencyMs)
    }

    private fun dropped(
        target: String,
        udpReachable: Boolean,
    ): QuicProbeResult =
        QuicProbeResult(
            target = target,
            verdict = QuicProbeVerdict.QUIC_DROPPED,
            chromeOk = false,
            firefoxOk = false,
            genericOk = false,
            vnOk = false,
            udpReachable = udpReachable,
            serverInitialLatencyMs = null,
        )

    private companion object {
        private const val DefaultQuicPort = 443
        private const val DefaultTimeoutMs = 3_000
        private const val DefaultConcurrency = 8
    }
}

object QuicFingerprintFactory {
    const val QuicV1Version: Int = 0x00000001
    const val ReservedVersion: Int = 0x1A2A3A4A

    fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray {
        val version =
            when (fingerprint) {
                QuicFingerprint.VN_PROBE -> ReservedVersion
                else -> QuicV1Version
            }
        val dcid = connectionId(target = target, fingerprint = fingerprint, salt = "dcid")
        val scid = connectionId(target = target, fingerprint = fingerprint, salt = "scid")
        val payload = payloadFor(fingerprint, target)
        return buildList {
            add(QuicInitialHeaderByte)
            addVersion(version)
            add(dcid.size.toByte())
            addAll(dcid.toList())
            add(scid.size.toByte())
            addAll(scid.toList())
            add(ZeroLengthToken)
            addVarInt(payload.size + PacketNumberLength)
            addAll(ByteArray(PacketNumberLength) { index -> index.toByte() }.toList())
            addAll(payload.toList())
        }.toByteArray()
    }

    private fun connectionId(
        target: String,
        fingerprint: QuicFingerprint,
        salt: String,
    ): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$salt:$fingerprint:$target".toByteArray())
            .copyOf(ConnectionIdLength)

    private fun payloadFor(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray {
        val marker =
            when (fingerprint) {
                QuicFingerprint.CHROME_120 -> "chrome120"
                QuicFingerprint.FIREFOX_121 -> "firefox121"
                QuicFingerprint.GENERIC_V1 -> "generic-v1"
                QuicFingerprint.VN_PROBE -> "vn-probe"
            }
        return "$marker:$target".toByteArray().padToMinimum(MinimumPayloadSize)
    }

    private fun MutableList<Byte>.addVersion(version: Int) {
        add(((version ushr 24) and ByteMask).toByte())
        add(((version ushr 16) and ByteMask).toByte())
        add(((version ushr 8) and ByteMask).toByte())
        add((version and ByteMask).toByte())
    }

    private fun MutableList<Byte>.addVarInt(value: Int) {
        require(value in 0..MaxTwoByteVarInt)
        add(((value ushr 8) or TwoByteVarIntPrefix).toByte())
        add((value and ByteMask).toByte())
    }

    private fun ByteArray.padToMinimum(size: Int): ByteArray =
        if (this.size >= size) {
            this
        } else {
            this + ByteArray(size - this.size)
        }

    private const val QuicInitialHeaderByte = 0xC3.toByte()
    private const val ZeroLengthToken = 0.toByte()
    private const val PacketNumberLength = 4
    private const val ConnectionIdLength = 8
    private const val MinimumPayloadSize = 32
    private const val ByteMask = 0xFF
    private const val TwoByteVarIntPrefix = 0x40
    private const val MaxTwoByteVarInt = 16_383
}

class DatagramSocketQuicUdpProbe : QuicUdpProbe {
    override suspend fun udpReachable(
        target: String,
        port: Int,
        timeoutMs: Int,
    ): Boolean =
        sendInitial(
            target = target,
            port = port,
            fingerprint = QuicFingerprint.VN_PROBE,
            packet = QuicFingerprintFactory.create(QuicFingerprint.VN_PROBE, target),
            timeoutMs = timeoutMs,
        ).response != null

    override suspend fun sendInitial(
        target: String,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample =
        withContext(Dispatchers.IO) {
            val address = InetAddress.getByName(target)
            val socket = DatagramSocket()
            try {
                socket.soTimeout = timeoutMs
                val startedAt = System.nanoTime()
                socket.send(DatagramPacket(packet, packet.size, address, port))
                val buffer = ByteArray(MaxDatagramBytes)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                QuicProbeSample(
                    response = buffer.copyOf(response.length),
                    latencyMs = (System.nanoTime() - startedAt) / NanosPerMillis,
                )
            } catch (error: SocketTimeoutException) {
                QuicProbeSample(response = null, latencyMs = null)
            } finally {
                socket.close()
            }
        }

    private companion object {
        private const val MaxDatagramBytes = 2_048
        private const val NanosPerMillis = 1_000_000L
    }
}

private data class QuicProbeOutcome(
    val ok: Boolean,
    val timedOut: Boolean,
    val latencyMs: Long?,
)

private fun isQuicV1LongHeader(packet: ByteArray): Boolean =
    packet.hasLongHeaderVersion(QuicFingerprintFactory.QuicV1Version)

private fun isVersionNegotiationPacket(packet: ByteArray): Boolean = packet.hasLongHeaderVersion(0)

private fun ByteArray.hasLongHeaderVersion(version: Int): Boolean =
    size >= QuicVersionEndIndex &&
        first().toInt() and LongHeaderMask != 0 &&
        quicVersion() == version

private fun ByteArray.quicVersion(): Int =
    ((this[1].toInt() and ByteMask) shl 24) or
        ((this[2].toInt() and ByteMask) shl 16) or
        ((this[3].toInt() and ByteMask) shl 8) or
        (this[4].toInt() and ByteMask)

private const val ByteMask = 0xFF
private const val LongHeaderMask = 0x80
private const val QuicVersionEndIndex = 5
