package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    val peerAddress: String? = null,
    val attempts: Map<QuicFingerprint, List<QuicProbeSample>> = emptyMap(),
    val startFailure: QuicProbeStartFailure? = null,
)

enum class QuicProbeStartFailure {
    DNS_TIMEOUT,
    DNS_ERROR,
}

enum class QuicProbeOutcome(
    internal val nativeCode: Int? = null,
) {
    // Keep these wire values paired with the Rust JNI result codes.
    INITIAL_CRYPTO(1),
    INITIAL_ACK(2),
    INITIAL_OTHER(3),
    INITIAL_CLOSE(4),
    RETRY(5),
    VERSION_NEGOTIATION(6),
    NO_RESPONSE,
    UNCORRELATED_RESPONSE(0),
    TRANSPORT_ERROR,
    VALIDATION_ERROR(-1),
    PACKET_BUILD_ERROR,
    BUDGET_EXHAUSTED,
}

data class QuicProbeSample(
    val outcome: QuicProbeOutcome,
    val latencyMs: Long? = null,
    val invalidDatagrams: Int = 0,
)

interface QuicUdpProbe {
    suspend fun resolve(
        target: String,
        timeoutMs: Int,
    ): InetAddress?

    suspend fun sendInitial(
        address: InetAddress,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        deadline: TimeMark,
        validate: (ByteArray) -> QuicProbeOutcome,
    ): QuicProbeSample
}

class QuicH3FingerprintProbe(
    private val socket: QuicUdpProbe = DatagramSocketQuicUdpProbe(),
    private val packetFactory: QuicInitialPacketFactory = QuicFingerprintFactory,
    private val responseValidator: QuicProbeResponseValidator = NativeQuicInitialPacketBindings(),
    private val port: Int = DefaultQuicPort,
    private val timeoutMs: Int = DefaultTimeoutMs,
    private val concurrency: Int = DefaultConcurrency,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend fun check(target: String): QuicProbeResult {
        val deadline = timeSource.markNow() + (timeoutMs.toLong() * QuicFingerprint.entries.size).milliseconds
        val address =
            try {
                socket.resolve(target, timeoutMs)
            } catch (_: IOException) {
                return unresolved(target, QuicProbeStartFailure.DNS_ERROR)
            } catch (_: SecurityException) {
                return unresolved(target, QuicProbeStartFailure.DNS_ERROR)
            } ?: return unresolved(target, QuicProbeStartFailure.DNS_TIMEOUT)

        val firstPass =
            QuicFingerprint.entries.associateWith { fingerprint ->
                probeFingerprint(target, address, fingerprint, deadline)
            }
        val missing = firstPass.filterValues { sample -> sample.shouldRetry }.keys
        val retried =
            if (firstPass.values.any { sample -> sample.isCorrelated } && deadline.hasNotPassedNow()) {
                coroutineScope {
                    missing
                        .map { fingerprint ->
                            async { fingerprint to probeFingerprint(target, address, fingerprint, deadline) }
                        }.awaitAll()
                        .toMap()
                }
            } else {
                emptyMap()
            }
        val attempts = firstPass.mapValues { (fingerprint, first) -> listOfNotNull(first, retried[fingerprint]) }
        val confirmed = firstPass + retried
        val chrome = confirmed.getValue(QuicFingerprint.CHROME_120)
        val firefox = confirmed.getValue(QuicFingerprint.FIREFOX_121)
        val generic = confirmed.getValue(QuicFingerprint.GENERIC_V1)
        val vn = confirmed.getValue(QuicFingerprint.VN_PROBE)

        val v1 = listOf(chrome, firefox, generic)
        val samples = attempts.values.flatten()
        val udpReachable =
            samples.any { sample ->
                sample.isCorrelated || sample.invalidDatagrams > 0 ||
                    sample.outcome == QuicProbeOutcome.VALIDATION_ERROR
            }
        val initialCryptoForAll = v1.all { it.hasInitialCrypto }
        val hasVn = vn.outcome == QuicProbeOutcome.VERSION_NEGOTIATION
        val allTimedOut = samples.all { sample -> sample.outcome == QuicProbeOutcome.NO_RESPONSE }
        val verdict =
            when {
                initialCryptoForAll && hasVn -> QuicProbeVerdict.QUIC_OK
                allTimedOut -> QuicProbeVerdict.QUIC_TIMEOUT
                samples.all { it.shouldRetry } -> QuicProbeVerdict.QUIC_DROPPED
                initialCryptoForAll && !hasVn -> QuicProbeVerdict.QUIC_VN_REJECTED
                else -> QuicProbeVerdict.QUIC_DEGRADED
            }
        return QuicProbeResult(
            target = target,
            verdict = verdict,
            chromeOk = chrome.hasInitialCrypto,
            firefoxOk = firefox.hasInitialCrypto,
            genericOk = generic.hasInitialCrypto,
            vnOk = hasVn,
            udpReachable = udpReachable,
            serverInitialLatencyMs = v1.firstOrNull { it.hasInitialCrypto }?.latencyMs,
            peerAddress = address.hostAddress,
            attempts = attempts,
        )
    }

    suspend fun checkAll(targets: List<String>): List<QuicProbeResult> =
        coroutineScope {
            val next = AtomicInteger()
            val results = arrayOfNulls<QuicProbeResult>(targets.size)
            val workers =
                List(minOf(targets.size, concurrency.coerceIn(1, DefaultConcurrency))) {
                    async {
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= targets.size) break
                            results[index] = check(targets[index])
                        }
                    }
                }
            workers.awaitAll()
            results.map { requireNotNull(it) }
        }

    private suspend fun probeFingerprint(
        target: String,
        address: InetAddress,
        fingerprint: QuicFingerprint,
        targetDeadline: TimeMark,
    ): QuicProbeSample {
        currentCoroutineContext().ensureActive()
        val deadline = timeSource.markNow() + minOf(timeoutMs.milliseconds, -targetDeadline.elapsedNow())
        if (deadline.hasPassedNow()) return QuicProbeSample(QuicProbeOutcome.BUDGET_EXHAUSTED)
        val packet =
            try {
                packetFactory.create(fingerprint = fingerprint, target = target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                return QuicProbeSample(QuicProbeOutcome.PACKET_BUILD_ERROR)
            }
        return try {
            socket.sendInitial(address, port, fingerprint, packet, deadline) { response ->
                responseValidator.validate(packet, response, fingerprint == QuicFingerprint.VN_PROBE)
            }
        } catch (_: IOException) {
            QuicProbeSample(QuicProbeOutcome.TRANSPORT_ERROR)
        } catch (_: SecurityException) {
            QuicProbeSample(QuicProbeOutcome.TRANSPORT_ERROR)
        }
    }

    private fun unresolved(
        target: String,
        reason: QuicProbeStartFailure,
    ): QuicProbeResult =
        QuicProbeResult(
            target = target,
            verdict = QuicProbeVerdict.QUIC_TIMEOUT,
            chromeOk = false,
            firefoxOk = false,
            genericOk = false,
            vnOk = false,
            udpReachable = false,
            serverInitialLatencyMs = null,
            startFailure = reason,
        )

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

object QuicFingerprintFactory : QuicInitialPacketFactory {
    const val QuicV1Version: Int = 0x00000001
    const val ReservedVersion: Int = 0x1A2A3A4A

    private val nativeFactory = NativeQuicInitialPacketFactory()

    override fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray =
        nativeFactory.create(
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
        add(((version ushr VersionByte3Shift) and ByteMask).toByte())
        add(((version ushr VersionByte2Shift) and ByteMask).toByte())
        add(((version ushr VersionByte1Shift) and ByteMask).toByte())
        add((version and ByteMask).toByte())
    }

    private fun MutableList<Byte>.addVarInt(value: Int) {
        require(value in 0..MaxTwoByteVarInt)
        add(((value ushr VersionByte1Shift) or TwoByteVarIntPrefix).toByte())
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
    private const val VersionByte3Shift = 24
    private const val VersionByte2Shift = 16
    private const val VersionByte1Shift = 8
}

interface QuicInitialPacketNativeBindings {
    fun create(requestJson: String): String?
}

fun interface QuicProbeResponseValidator {
    fun validate(
        request: ByteArray,
        response: ByteArray,
        versionNegotiation: Boolean,
    ): QuicProbeOutcome
}

class NativeQuicInitialPacketBindings :
    QuicInitialPacketNativeBindings,
    QuicProbeResponseValidator {
    override fun create(requestJson: String): String? {
        System.loadLibrary("ripdpi")
        return jniCreate(requestJson)
    }

    override fun validate(
        request: ByteArray,
        response: ByteArray,
        versionNegotiation: Boolean,
    ): QuicProbeOutcome =
        try {
            System.loadLibrary("ripdpi")
            decodeQuicValidation(jniValidate(request, response, versionNegotiation))
        } catch (_: UnsatisfiedLinkError) {
            QuicProbeOutcome.VALIDATION_ERROR
        } catch (_: SecurityException) {
            QuicProbeOutcome.VALIDATION_ERROR
        }

    private external fun jniCreate(requestJson: String): String?

    private external fun jniValidate(
        request: ByteArray,
        response: ByteArray,
        versionNegotiation: Boolean,
    ): Int
}

private fun decodeQuicValidation(result: Int): QuicProbeOutcome =
    QuicProbeOutcome.entries.firstOrNull { it.nativeCode == result } ?: QuicProbeOutcome.VALIDATION_ERROR

internal class NativeQuicInitialPacketFactory(
    private val bindings: QuicInitialPacketNativeBindings = NativeQuicInitialPacketBindings(),
    private val json: Json = RipDpiJson,
) {
    fun create(
        fingerprint: QuicFingerprint,
        target: String,
    ): ByteArray =
        createOrNull(fingerprint, target)
            ?: error("Native QUIC Initial packet generation failed for $fingerprint")

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
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
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

private val QuicProbeSample.hasInitialCrypto: Boolean
    get() = outcome == QuicProbeOutcome.INITIAL_CRYPTO

private val QuicProbeSample.isCorrelated: Boolean
    get() =
        when (outcome) {
            QuicProbeOutcome.INITIAL_CRYPTO,
            QuicProbeOutcome.INITIAL_ACK,
            QuicProbeOutcome.INITIAL_OTHER,
            QuicProbeOutcome.INITIAL_CLOSE,
            QuicProbeOutcome.RETRY,
            QuicProbeOutcome.VERSION_NEGOTIATION,
            -> true

            else -> false
        }

private val QuicProbeSample.shouldRetry: Boolean
    get() = outcome == QuicProbeOutcome.NO_RESPONSE || outcome == QuicProbeOutcome.UNCORRELATED_RESPONSE
