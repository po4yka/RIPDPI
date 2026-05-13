@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CallTransportPath
import com.poyka.ripdpi.core.detection.CallTransportResult
import com.poyka.ripdpi.core.detection.CallTransportStunObservation
import com.poyka.ripdpi.core.detection.CallTransportStunScope
import com.poyka.ripdpi.core.detection.CallTransportStunServer
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

fun interface CallTransportStunClient {
    suspend fun reflexiveAddress(
        path: CallTransportPath,
        server: CallTransportStunServer,
    ): String?
}

fun interface CallTransportMtProtoProber {
    suspend fun canReach(path: CallTransportPath): Boolean
}

interface CallTransportPathAwareStunClient : CallTransportStunClient {
    val supportedPaths: Set<CallTransportPath>
}

interface CallTransportPathAwareMtProtoProber : CallTransportMtProtoProber {
    val supportedPaths: Set<CallTransportPath>
}

object CallTransportLeakChecker {
    suspend fun check(
        enabled: Boolean,
        stunClient: CallTransportStunClient,
        mtProtoProber: CallTransportMtProtoProber,
        proxyEndpoint: ProxyEndpoint? = null,
        socks5StunClient: Socks5StunClient? = null,
        socks5MtProtoProber: MtProtoProber? = null,
        servers: List<CallTransportStunServer> = DefaultStunServers,
    ): CallTransportResult =
        coroutineScope {
            if (!enabled) {
                return@coroutineScope disabledResult()
            }

            val stunPaths = stunClient.supportedPaths()
            val stunObservations =
                servers
                    .flatMap { server ->
                        CallTransportPath.entries.filter { path -> path in stunPaths }.map { path ->
                            async {
                                runNullableProbe {
                                    stunClient.reflexiveAddress(path = path, server = server)
                                }?.let { address ->
                                    CallTransportStunObservation(path, server, address)
                                }
                            }
                        }
                    }.mapNotNull { deferred -> deferred.await() }

            val mtProtoReachable =
                if (CallTransportPath.VPN in mtProtoProber.supportedPaths()) {
                    runBooleanProbe {
                        mtProtoProber.canReach(CallTransportPath.VPN)
                    }
                } else {
                    false
                }

            val proxyStun =
                if (proxyEndpoint?.type == ProxyType.SOCKS5 && socks5StunClient != null) {
                    listOfNotNull(
                        runNullableProbe {
                            socks5StunClient.reflexiveAddress(proxyEndpoint)
                        },
                    )
                } else {
                    emptyList()
                }.distinct()

            val proxyMtProtoReachable =
                proxyEndpoint?.takeIf { it.type == ProxyType.SOCKS5 && socks5MtProtoProber != null }?.let { endpoint ->
                    runBooleanProbe {
                        socks5MtProtoProber?.canReach(endpoint) == true
                    }
                } ?: false

            resultFrom(
                observations = stunObservations,
                mtProtoReachable = mtProtoReachable || proxyMtProtoReachable,
                proxyEndpoint = proxyEndpoint,
                proxyStunReflexiveAddresses = proxyStun,
            )
        }

    fun defaultStunClient(dispatchers: AppCoroutineDispatchers): CallTransportStunClient =
        DefaultCallTransportStunClient(dispatchers)

    fun defaultMtProtoProber(dispatchers: AppCoroutineDispatchers): CallTransportMtProtoProber =
        DefaultCallTransportMtProtoProber(dispatchers)

    private suspend fun <T> runNullableProbe(block: suspend () -> T?): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private suspend fun runBooleanProbe(block: suspend () -> Boolean): Boolean =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }

    private fun disabledResult(): CallTransportResult =
        CallTransportResult(
            category =
                CategoryResult(
                    name = "Call transport",
                    detected = false,
                    findings = listOf(Finding("Call transport leak check disabled")),
                ),
        )

    private fun resultFrom(
        observations: List<CallTransportStunObservation>,
        mtProtoReachable: Boolean,
        proxyEndpoint: ProxyEndpoint?,
        proxyStunReflexiveAddresses: List<String>,
    ): CallTransportResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        val mismatch = observations.pathMismatch()

        if (observations.isEmpty()) {
            findings += Finding("STUN reflexive addresses: unavailable")
        } else {
            observations.forEach { observation ->
                findings +=
                    Finding(
                        "${observation.server.scope.label()} STUN ${observation.path.label()}: " +
                            observation.reflexiveAddress,
                    )
            }
        }

        if (mismatch != null) {
            findings +=
                Finding(
                    description =
                        "Call STUN leak: VPN path ${mismatch.vpnIps} differs from underlying path " +
                            mismatch.underlyingIps,
                    detected = true,
                    source = EvidenceSource.CALL_TRANSPORT,
                    confidence = EvidenceConfidence.HIGH,
                )
            evidence +=
                EvidenceItem(
                    source = EvidenceSource.CALL_TRANSPORT,
                    detected = true,
                    confidence = EvidenceConfidence.HIGH,
                    description = "STUN reflexive addresses differ between VPN and underlying call paths",
                )
        }

        if (mtProtoReachable) {
            findings +=
                Finding(
                    description = "MTProto DC2 reachable from VPN call transport path",
                    detected = true,
                    needsReview = true,
                    source = EvidenceSource.CALL_TRANSPORT,
                    confidence = EvidenceConfidence.MEDIUM,
                )
            evidence +=
                EvidenceItem(
                    source = EvidenceSource.CALL_TRANSPORT,
                    detected = true,
                    confidence = EvidenceConfidence.MEDIUM,
                    description = "Telegram DC2 accepted a TCP connection on the call transport path",
                )
        }

        proxyStunReflexiveAddresses.forEach { address ->
            findings += Finding("SOCKS5 UDP associate STUN reflexive address: $address")
        }

        val detected = evidence.any { it.detected && it.confidence == EvidenceConfidence.HIGH }
        return CallTransportResult(
            category =
                CategoryResult(
                    name = "Call transport",
                    detected = detected,
                    findings = findings,
                    needsReview = !detected && evidence.isNotEmpty(),
                    evidence = evidence,
                ),
            stunObservations = observations,
            mtProtoReachable = mtProtoReachable,
            proxyEndpoint = proxyEndpoint,
            proxyStunReflexiveAddresses = proxyStunReflexiveAddresses,
        )
    }

    private fun List<CallTransportStunObservation>.pathMismatch(): PathMismatch? {
        val vpnIps = filter { it.path == CallTransportPath.VPN }.map { it.reflexiveAddress }.distinct().sorted()
        val underlyingIps =
            filter { it.path == CallTransportPath.UNDERLYING }.map { it.reflexiveAddress }.distinct().sorted()
        if (vpnIps.isEmpty() || underlyingIps.isEmpty() || vpnIps == underlyingIps) return null
        return PathMismatch(vpnIps = vpnIps, underlyingIps = underlyingIps)
    }

    private data class PathMismatch(
        val vpnIps: List<String>,
        val underlyingIps: List<String>,
    )

    val DefaultStunServers =
        listOf(
            CallTransportStunServer("stun.sberbank.ru", 19_302, CallTransportStunScope.RU),
            CallTransportStunServer("stun.fitauto.ru", 19_302, CallTransportStunScope.RU),
            CallTransportStunServer("stun.l.google.com", 19_302, CallTransportStunScope.GLOBAL),
            CallTransportStunServer("stun1.l.google.com", 19_302, CallTransportStunScope.GLOBAL),
            CallTransportStunServer("stun2.l.google.com", 19_302, CallTransportStunScope.GLOBAL),
            CallTransportStunServer("stun.cloudflare.com", 3_478, CallTransportStunScope.GLOBAL),
            CallTransportStunServer("global.stun.twilio.com", 3_478, CallTransportStunScope.GLOBAL),
            CallTransportStunServer("stun.nextcloud.com", 3_478, CallTransportStunScope.GLOBAL),
        )
}

private fun CallTransportStunClient.supportedPaths(): Set<CallTransportPath> =
    (this as? CallTransportPathAwareStunClient)?.supportedPaths.orVpnOnly()

private fun CallTransportMtProtoProber.supportedPaths(): Set<CallTransportPath> =
    (this as? CallTransportPathAwareMtProtoProber)?.supportedPaths.orVpnOnly()

private fun Set<CallTransportPath>?.orVpnOnly(): Set<CallTransportPath> =
    takeUnless { paths -> paths.isNullOrEmpty() } ?: VpnOnlyCallTransportPaths

private val VpnOnlyCallTransportPaths = setOf(CallTransportPath.VPN)

private class DefaultCallTransportMtProtoProber(
    private val dispatchers: AppCoroutineDispatchers,
) : CallTransportPathAwareMtProtoProber {
    override val supportedPaths: Set<CallTransportPath> = setOf(CallTransportPath.VPN)

    override suspend fun canReach(path: CallTransportPath): Boolean =
        withContext(dispatchers.io) {
            if (path != CallTransportPath.VPN) return@withContext false
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(TelegramDc2Host, TelegramDcPort), TimeoutMs)
                    true
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }

    private companion object {
        const val TelegramDc2Host = "149.154.167.51"
        const val TelegramDcPort = 443
        const val TimeoutMs = 3_000
    }
}

private class DefaultCallTransportStunClient(
    private val dispatchers: AppCoroutineDispatchers,
    private val timeoutMs: Int = 3_000,
    private val random: SecureRandom = SecureRandom(),
) : CallTransportPathAwareStunClient {
    override val supportedPaths: Set<CallTransportPath> = setOf(CallTransportPath.VPN)

    override suspend fun reflexiveAddress(
        path: CallTransportPath,
        server: CallTransportStunServer,
    ): String? =
        withContext(dispatchers.io) {
            if (path != CallTransportPath.VPN) return@withContext null
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val transactionId = ByteArray(StunTransactionIdBytes).also(random::nextBytes)
                    val request = buildStunBindingRequest(transactionId)
                    val target = InetAddress.getByName(server.host)
                    socket.send(DatagramPacket(request, request.size, target, server.port))
                    val buffer = ByteArray(MaxUdpPacketBytes)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    parseStunResponse(buffer.copyOf(response.length), transactionId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        }
}

private const val StunBindingRequest = 0x0001
private const val StunBindingResponse = 0x0101
private const val StunMappedAddress = 0x0001
private const val StunXorMappedAddress = 0x0020
private const val StunMagicCookie = 0x2112A442
private const val StunHeaderBytes = 20
private const val StunTransactionIdBytes = 12
private const val StunAddressIpv4 = 0x01
private const val MaxUdpPacketBytes = 1_500
private const val StunAttributeHeaderBytes = 4
private const val StunAttributeAlignmentBytes = 4
private const val StunIpv4AddressAttributeBytes = 8
private const val StunAddressFamilyOffset = 1
private const val StunAddressValueOffset = 4
private const val Ipv4AddressBytes = 4
private const val StunAttributeTypeBytes = 2
private const val UnsignedByteMask = 0xFF
private const val UnsignedShortMask = 0xFFFF

private fun buildStunBindingRequest(transactionId: ByteArray): ByteArray =
    ByteBuffer
        .allocate(StunHeaderBytes)
        .putShort(StunBindingRequest.toShort())
        .putShort(0)
        .putInt(StunMagicCookie)
        .put(transactionId)
        .array()

private fun parseStunResponse(
    packet: ByteArray,
    transactionId: ByteArray,
): String? {
    if (packet.size < StunHeaderBytes) return null
    val buffer = ByteBuffer.wrap(packet)
    val type = buffer.short.toInt() and UnsignedShortMask
    val length = buffer.short.toInt() and UnsignedShortMask
    val cookie = buffer.int
    val tx = ByteArray(StunTransactionIdBytes).also(buffer::get)
    if (type != StunBindingResponse || cookie != StunMagicCookie || !tx.contentEquals(transactionId)) return null

    var offset = StunHeaderBytes
    while (
        offset + StunAttributeHeaderBytes <= StunHeaderBytes + length &&
        offset + StunAttributeHeaderBytes <= packet.size
    ) {
        val attrType =
            ByteBuffer.wrap(packet, offset, StunAttributeTypeBytes).short.toInt() and UnsignedShortMask
        val attrLength =
            ByteBuffer.wrap(packet, offset + StunAttributeTypeBytes, StunAttributeTypeBytes).short.toInt() and
                UnsignedShortMask
        val valueOffset = offset + StunAttributeHeaderBytes
        if (valueOffset + attrLength > packet.size) return null
        when (attrType) {
            StunXorMappedAddress -> return parseXorMappedIpv4(packet, valueOffset, attrLength)
            StunMappedAddress -> return parseMappedIpv4(packet, valueOffset, attrLength)
        }
        offset =
            valueOffset + attrLength +
            stunAttributePadding(attrLength)
    }
    return null
}

private fun parseXorMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (!isIpv4AddressAttribute(bytes, offset, length)) {
        return null
    }
    val cookieBytes = ByteBuffer.allocate(Ipv4AddressBytes).putInt(StunMagicCookie).array()
    val address =
        ByteArray(Ipv4AddressBytes) { index ->
            (bytes[offset + StunAddressValueOffset + index].toInt() xor cookieBytes[index].toInt()).toByte()
        }
    return InetAddress.getByAddress(address).hostAddress
}

private fun parseMappedIpv4(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String? {
    if (!isIpv4AddressAttribute(bytes, offset, length)) {
        return null
    }
    return InetAddress
        .getByAddress(bytes.copyOfRange(offset + StunAddressValueOffset, offset + StunIpv4AddressAttributeBytes))
        .hostAddress
}

private fun CallTransportPath.label(): String =
    name
        .lowercase()
        .replace('_', ' ')

private fun CallTransportStunScope.label(): String =
    name
        .lowercase()
        .replaceFirstChar(Char::uppercaseChar)

private fun stunAttributePadding(length: Int): Int =
    (StunAttributeAlignmentBytes - (length % StunAttributeAlignmentBytes)) % StunAttributeAlignmentBytes

private fun isIpv4AddressAttribute(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): Boolean =
    length >= StunIpv4AddressAttributeBytes &&
        bytes[offset + StunAddressFamilyOffset].unsigned() == StunAddressIpv4

private fun Byte.unsigned(): Int = toInt() and UnsignedByteMask
