package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

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
    private val packetFactory: QuicInitialPacketFactory = QuicFingerprintFactory,
    private val port: Int = DefaultQuicPort,
    private val timeoutMs: Int = DefaultTimeoutMs,
    private val concurrency: Int = DefaultConcurrency,
) {
    suspend fun check(target: String): QuicProbeResult {
        val chrome = probeFingerprint(target, QuicFingerprint.CHROME_120, ::isQuicV1LongHeader)
        val firefox = probeFingerprint(target, QuicFingerprint.FIREFOX_121, ::isQuicV1LongHeader)
        val generic = probeFingerprint(target, QuicFingerprint.GENERIC_V1, ::isQuicV1LongHeader)
        val vn = probeFingerprint(target, QuicFingerprint.VN_PROBE, ::isVersionNegotiationPacket)

        val outcomes = listOf(chrome, firefox, generic, vn)
        val chromeOk = chrome.ok
        val firefoxOk = firefox.ok
        val genericOk = generic.ok
        val vnOk = vn.ok
        val v1OkCount = listOf(chromeOk, firefoxOk, genericOk).count { value -> value }
        val latencyMs = listOf(chrome, firefox, generic).firstOrNull(QuicProbeOutcome::ok)?.latencyMs
        val udpReachable = outcomes.any { outcome -> !outcome.timedOut }
        val allTimedOut = outcomes.all(QuicProbeOutcome::timedOut)
        val verdict =
            when {
                chromeOk && firefoxOk && genericOk && vnOk -> QuicProbeVerdict.QUIC_OK
                allTimedOut -> QuicProbeVerdict.QUIC_TIMEOUT
                v1OkCount == 0 && !vnOk -> QuicProbeVerdict.QUIC_DROPPED
                chromeOk && firefoxOk && genericOk && !vnOk -> QuicProbeVerdict.QUIC_VN_REJECTED
                !chromeOk && (firefoxOk || genericOk) -> QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK
                else -> QuicProbeVerdict.QUIC_DEGRADED
            }
        return QuicProbeResult(
            target = target,
            verdict = verdict,
            chromeOk = chromeOk,
            firefoxOk = firefoxOk,
            genericOk = genericOk,
            vnOk = vnOk,
            udpReachable = udpReachable,
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

    private companion object {
        private const val DefaultQuicPort = 443
        private const val DefaultTimeoutMs = 3_000
        private const val DefaultConcurrency = 8
    }
}

fun interface QuicInitialPacketFactory {
    fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray
}

class FixtureBackedQuicFingerprintFactory(
    private val fixtures: Map<QuicFingerprint, ByteArray>,
    private val delegate: QuicInitialPacketFactory = QuicFingerprintFactory,
) : QuicInitialPacketFactory {
    override fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray =
        if (target.equals(FixtureTarget, ignoreCase = true)) {
            fixtures[fingerprint] ?: delegate.create(fingerprint = fingerprint, target = target)
        } else {
            delegate.create(fingerprint = fingerprint, target = target)
        }

    private companion object {
        private const val FixtureTarget = "cloudflare.com"
    }
}

object QuicFingerprintFactory : QuicInitialPacketFactory {
    const val QuicV1Version: Int = 0x00000001
    const val ReservedVersion: Int = 0x1A2A3A4A

    private val nativeFactory = NativeQuicInitialPacketFactory()

    override fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray =
        nativeFactory.createOrNull(
            fingerprint = fingerprint,
            target = target,
        ) ?: createSynthetic(
            fingerprint = fingerprint,
            target = target,
        )

    internal fun createSynthetic(
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

interface QuicInitialPacketNativeBindings {
    fun create(requestJson: String): String?
}

class NativeQuicInitialPacketBindings : QuicInitialPacketNativeBindings {
    override fun create(requestJson: String): String? {
        System.loadLibrary("ripdpi")
        return jniCreate(requestJson)
    }

    private external fun jniCreate(requestJson: String): String?
}

internal class NativeQuicInitialPacketFactory(
    private val bindings: QuicInitialPacketNativeBindings = NativeQuicInitialPacketBindings(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun createOrNull(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray? =
        try {
            val request =
                NativeQuicInitialRequest(
                    fingerprint = fingerprint.nativeId,
                    target = target,
                )
            val payload = bindings.create(json.encodeToString(request)) ?: return null
            val response = json.decodeFromString(NativeQuicInitialResponse.serializer(), payload)
            if (!response.error.isNullOrBlank()) {
                null
            } else {
                response.packetBase64?.let { encoded -> Base64.getDecoder().decode(encoded) }
            }
        } catch (error: UnsatisfiedLinkError) {
            null
        } catch (error: SecurityException) {
            null
        } catch (error: SerializationException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }

    private val QuicFingerprint.nativeId: String
        get() =
            when (this) {
                QuicFingerprint.CHROME_120 -> "chrome120"
                QuicFingerprint.FIREFOX_121 -> "firefox121"
                QuicFingerprint.GENERIC_V1 -> "generic_v1"
                QuicFingerprint.VN_PROBE -> "vn_probe"
            }
}

@Serializable
private data class NativeQuicInitialRequest(
    val fingerprint: String,
    val target: String,
)

@Serializable
private data class NativeQuicInitialResponse(
    @SerialName("packetBase64")
    val packetBase64: String? = null,
    val error: String? = null,
)

class DatagramSocketQuicUdpProbe : QuicUdpProbe {
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
