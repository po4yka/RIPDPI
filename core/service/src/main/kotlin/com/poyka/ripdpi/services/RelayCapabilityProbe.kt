package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

data class RelayProbeEndpoint(
    val host: String,
    val port: Int,
)

data class RelayCapabilityProbeResult(
    val succeeded: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val failure: String?,
)

/** Per-capability evidence used by local acceptance diagnostics. */
data class RelayCapabilityProbeEvidence(
    val tcpSucceeded: Boolean,
    val tcpStatusCode: Int?,
    val tcpFailure: String?,
    val udpAssociationOpened: Boolean,
    val udpSucceeded: Boolean,
    val udpFailure: String?,
    val latencyMs: Long,
)

enum class RelayProbeFailure(
    val wireValue: String,
) {
    TcpConnect("tcp_connect"),
    TcpHttpStatus("tcp_http_status"),
    UdpAssociateOpen("udp_associate_open"),
    UdpWrite("udp_write"),
    UdpReadTimeout("udp_read_timeout"),
    DnsResponse("dns_response"),
    UdpIo("udp_io"),
}

internal data class RelayTcpProbeResult(
    val succeeded: Boolean,
    val statusCode: Int? = null,
    val failure: RelayProbeFailure? = null,
)

internal fun interface RelayTcpProbe {
    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        url: String,
    ): RelayTcpProbeResult
}

internal data class RelayUdpProbeResult(
    val succeeded: Boolean,
    val associationOpened: Boolean,
    val failure: RelayProbeFailure? = null,
) {
    companion object {
        fun success(): RelayUdpProbeResult = RelayUdpProbeResult(succeeded = true, associationOpened = true)

        fun notRequired(): RelayUdpProbeResult = RelayUdpProbeResult(succeeded = true, associationOpened = false)

        fun failure(
            failure: RelayProbeFailure,
            associationOpened: Boolean = false,
        ): RelayUdpProbeResult =
            RelayUdpProbeResult(
                succeeded = false,
                associationOpened = associationOpened,
                failure = failure,
            )
    }
}

internal fun classifyUdpAssociationIoFailure(associationOpened: Boolean): RelayProbeFailure =
    if (associationOpened) RelayProbeFailure.UdpIo else RelayProbeFailure.UdpAssociateOpen

internal fun interface RelayUdpAssociateProbe {
    suspend fun probe(endpoint: RelayProbeEndpoint): RelayUdpProbeResult
}

/**
 * Verifies the capabilities required by a VPN session through the candidate's
 * local SOCKS listener. TCP and UDP probes run concurrently so adding a UDP
 * requirement does not extend the initial-race deadline.
 */
class RelayCapabilityProbe internal constructor(
    private val tcpProbe: RelayTcpProbe,
    private val udpProbe: RelayUdpAssociateProbe,
    private val payloadHealthProbe: RelayUdpPayloadHealthProbe = Socks5DnsUdpPayloadHealthProbe(),
) {
    @Inject
    constructor() : this(
        tcpProbe = OkHttpRelayTcpProbe(),
        udpProbe = Socks5DnsUdpAssociateProbe(),
        payloadHealthProbe = Socks5DnsUdpPayloadHealthProbe(),
    )

    /** cancel-safe: child probes either expose cancellable calls or bounded blocking I/O. */
    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayCapabilityProbeResult {
        val evidence = probeEvidence(endpoint, url, requirements)
        return RelayCapabilityProbeResult(
            succeeded = evidence.tcpSucceeded && evidence.udpSucceeded,
            statusCode = evidence.tcpStatusCode,
            latencyMs = evidence.latencyMs,
            failure = evidence.tcpFailure ?: evidence.udpFailure,
        )
    }

    /** cancel-safe: child probes either expose cancellable calls or bounded blocking I/O. */
    suspend fun probeEvidence(
        endpoint: RelayProbeEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayCapabilityProbeEvidence =
        coroutineScope {
            val startedAt = System.nanoTime()
            val tcp =
                if (requirements.tcpConnect) {
                    async { tcpProbe.probe(endpoint, url) }
                } else {
                    null
                }
            val udp =
                if (requirements.udpAssociate) {
                    async { udpProbe.probe(endpoint) }
                } else {
                    null
                }
            val tcpResult = tcp?.await() ?: RelayTcpProbeResult(succeeded = true)
            val udpResult = udp?.await() ?: RelayUdpProbeResult.notRequired()
            RelayCapabilityProbeEvidence(
                tcpSucceeded = tcpResult.succeeded,
                tcpStatusCode = tcpResult.statusCode,
                tcpFailure = tcpResult.failure?.wireValue,
                udpAssociationOpened = udpResult.associationOpened,
                udpSucceeded = udpResult.succeeded,
                udpFailure = udpResult.failure?.wireValue,
                latencyMs = elapsedMillis(startedAt),
            )
        }

    /** cancel-safe: the SOCKS UDP ladder uses bounded blocking socket deadlines. */
    suspend fun probePayloadHealth(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
    ): RelayUdpPayloadHealthEvidence = payloadHealthProbe.probe(endpoint, families)
}

private class OkHttpRelayTcpProbe : RelayTcpProbe {
    override suspend fun probe(
        endpoint: RelayProbeEndpoint,
        url: String,
    ): RelayTcpProbeResult {
        val client =
            OkHttpClient
                .Builder()
                .proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress.createUnresolved(endpoint.host, endpoint.port),
                    ),
                ).followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(TcpProbeTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
            )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(
                                RelayTcpProbeResult(
                                    succeeded = false,
                                    failure = RelayProbeFailure.TcpConnect,
                                ),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            val succeeded = response.code in SuccessfulStatusRange
                            if (continuation.isActive) {
                                continuation.resume(
                                    RelayTcpProbeResult(
                                        succeeded = succeeded,
                                        statusCode = response.code,
                                        failure = if (succeeded) null else RelayProbeFailure.TcpHttpStatus,
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

internal class Socks5DnsUdpAssociateProbe internal constructor(
    private val dnsTarget: InetSocketAddress,
    private val timeoutMillis: Int,
) : RelayUdpAssociateProbe {
    constructor() : this(
        dnsTarget = InetSocketAddress(DefaultDnsAddress, DnsPort),
        timeoutMillis = UdpProbeTimeoutMillis,
    )

    /**
     * cancel-safe at the coroutine boundary: all blocking socket operations
     * have a fixed deadline and every socket is closed before returning.
     */
    override suspend fun probe(endpoint: RelayProbeEndpoint): RelayUdpProbeResult =
        try {
            runInterruptible(Dispatchers.IO) { probeBlocking(endpoint) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout)
        } catch (_: IOException) {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpIo)
        }

    private fun probeBlocking(endpoint: RelayProbeEndpoint): RelayUdpProbeResult {
        var associationOpened = false
        return try {
            Socket().use { control ->
                control.soTimeout = timeoutMillis
                control.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis)
                val input = DataInputStream(control.getInputStream())
                val output = DataOutputStream(control.getOutputStream())
                negotiateNoAuthentication(input, output)
                val udpRelay = openUdpAssociation(endpoint, input, output)
                associationOpened = true
                DatagramSocket().use { udp ->
                    udp.soTimeout = timeoutMillis
                    probeSingleDnsDatagram(udp, udpRelay, dnsTarget)
                }
            }
        } catch (_: ProtocolException) {
            RelayUdpProbeResult.failure(classifyUdpAssociationIoFailure(associationOpened), associationOpened)
        } catch (_: SocketTimeoutException) {
            RelayUdpProbeResult.failure(classifyUdpAssociationIoFailure(associationOpened), associationOpened)
        } catch (_: IOException) {
            RelayUdpProbeResult.failure(classifyUdpAssociationIoFailure(associationOpened), associationOpened)
        }
    }
}

private fun probeSingleDnsDatagram(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    dnsTarget: InetSocketAddress,
): RelayUdpProbeResult {
    val query = dnsQuery()
    val frame = encodeSocksUdpFrame(dnsTarget, query)
    return try {
        udp.send(DatagramPacket(frame, frame.size, udpRelay))
        val response = DatagramPacket(ByteArray(MaxUdpProbeResponseBytes), MaxUdpProbeResponseBytes)
        udp.receive(response)
        val payload = decodeSocksUdpPayload(response.data, response.length)
        when {
            payload == null -> RelayUdpProbeResult.failure(RelayProbeFailure.DnsResponse, associationOpened = true)
            isMatchingDnsResponse(query, payload) -> RelayUdpProbeResult.success()
            else -> RelayUdpProbeResult.failure(RelayProbeFailure.DnsResponse, associationOpened = true)
        }
    } catch (_: SocketTimeoutException) {
        RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout, associationOpened = true)
    } catch (_: IOException) {
        RelayUdpProbeResult.failure(RelayProbeFailure.UdpWrite, associationOpened = true)
    }
}

internal fun sendDnsProbePayload(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
): Boolean {
    val frame = encodeSocksUdpFrame(target, query)
    return try {
        udp.send(DatagramPacket(frame, frame.size, udpRelay))
        val response = DatagramPacket(ByteArray(MaxUdpProbeResponseBytes), MaxUdpProbeResponseBytes)
        udp.receive(response)
        val payload = decodeSocksUdpPayload(response.data, response.length)
        payload != null && isMatchingDnsResponse(query, payload)
    } catch (_: IOException) {
        false
    }
}

internal fun negotiateNoAuthentication(
    input: DataInputStream,
    output: DataOutputStream,
) {
    output.write(byteArrayOf(SocksVersion, SocksNoAuthMethodCount, NoAuthentication))
    output.flush()
    requireSocksProtocol(
        input.readUnsignedByte() == SocksVersion.toInt() &&
            input.readUnsignedByte() == NoAuthentication.toInt(),
        "SOCKS authentication negotiation failed",
    )
}

internal fun openUdpAssociation(
    endpoint: RelayProbeEndpoint,
    input: DataInputStream,
    output: DataOutputStream,
): InetSocketAddress {
    output.write(SocksUdpAssociateRequest)
    output.flush()
    requireSocksProtocol(
        input.readUnsignedByte() == SocksVersion.toInt() && input.readUnsignedByte() == ReplySucceeded,
        "SOCKS UDP ASSOCIATE was rejected",
    )
    requireSocksProtocol(
        input.readUnsignedByte() == SocksReserved.toInt(),
        "SOCKS UDP ASSOCIATE reserved byte is invalid",
    )
    val relayAddress = readSocksAddress(input)
    val relayPort = input.readUnsignedShort()
    requireSocksProtocol(relayPort != SocksPortZero, "SOCKS UDP ASSOCIATE returned port zero")
    val effectiveAddress =
        if (relayAddress.isAnyLocalAddress) {
            InetAddress.getByName(endpoint.host)
        } else {
            relayAddress
        }
    return InetSocketAddress(effectiveAddress, relayPort)
}

private fun requireSocksProtocol(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw ProtocolException(message)
}

@Suppress("MagicNumber")
private fun readSocksAddress(input: DataInputStream): InetAddress =
    when (input.readUnsignedByte()) {
        AddressIpv4.toInt() -> InetAddress.getByAddress(input.readNBytesExact(4))
        AddressIpv6.toInt() -> InetAddress.getByAddress(input.readNBytesExact(16))
        else -> throw ProtocolException("SOCKS address type is unsupported")
    }

@Suppress("MagicNumber")
private fun encodeSocksUdpFrame(
    target: InetSocketAddress,
    payload: ByteArray,
): ByteArray {
    val address = target.address ?: throw ProtocolException("UDP probe target must be an IP address")
    val addressBytes = address.address
    val addressType = if (addressBytes.size == 4) AddressIpv4 else AddressIpv6
    return ByteArray(4 + addressBytes.size + 2 + payload.size).also { frame ->
        frame[3] = addressType
        addressBytes.copyInto(frame, destinationOffset = 4)
        val portOffset = 4 + addressBytes.size
        frame[portOffset] = (target.port ushr 8).toByte()
        frame[portOffset + 1] = target.port.toByte()
        payload.copyInto(frame, destinationOffset = portOffset + 2)
    }
}

@Suppress("ComplexCondition", "MagicNumber", "ReturnCount")
private fun decodeSocksUdpPayload(
    frame: ByteArray,
    length: Int,
): ByteArray? {
    if (length < MinimumSocksUdpFrameBytes || frame[0] != 0.toByte() || frame[1] != 0.toByte() ||
        frame[2] != 0.toByte()
    ) {
        return null
    }
    val addressLength =
        when (frame[3]) {
            AddressIpv4 -> {
                4
            }

            AddressIpv6 -> {
                16
            }

            AddressDomain -> {
                if (length < 5) return null
                1 + frame[4].toUByte().toInt()
            }

            else -> {
                return null
            }
        }
    val payloadOffset = 4 + addressLength + 2
    if (payloadOffset >= length) return null
    return frame.copyOfRange(payloadOffset, length)
}

@Suppress("MagicNumber")
private fun dnsQuery(payloadSizeBytes: Int = BaseDnsQueryBytes): ByteArray {
    val queryId = SecureRandom().nextInt(1 shl 16)
    val question =
        byteArrayOf(
            7,
            'e'.code.toByte(),
            'x'.code.toByte(),
            'a'.code.toByte(),
            'm'.code.toByte(),
            'p'.code.toByte(),
            'l'.code.toByte(),
            'e'.code.toByte(),
            3,
            'c'.code.toByte(),
            'o'.code.toByte(),
            'm'.code.toByte(),
            0,
            0,
            1,
            0,
            1,
        )
    val baseQueryBytes = DnsHeaderBytes + question.size
    val includePadding = payloadSizeBytes >= baseQueryBytes + EdnsPaddingMinimumOverheadBytes
    val querySize =
        if (includePadding) {
            payloadSizeBytes
        } else {
            baseQueryBytes
        }
    return ByteArray(querySize).also { query ->
        query[0] = (queryId ushr 8).toByte()
        query[1] = queryId.toByte()
        query[2] = 1
        query[5] = 1
        question.copyInto(query, destinationOffset = DnsHeaderBytes)
        if (includePadding) {
            query[11] = 1
            val optOffset = baseQueryBytes
            query[optOffset] = 0
            query[optOffset + 1] = 0
            query[optOffset + 2] = 41
            query[optOffset + 3] = (EdnsUdpPayloadSize ushr 8).toByte()
            query[optOffset + 4] = EdnsUdpPayloadSize.toByte()
            val paddingLength = payloadSizeBytes - baseQueryBytes - EdnsPaddingMinimumOverheadBytes
            val rdLength = paddingLength + EdnsOptionHeaderBytes
            query[optOffset + 9] = (rdLength ushr 8).toByte()
            query[optOffset + 10] = rdLength.toByte()
            query[optOffset + 11] = 0
            query[optOffset + 12] = DnsEdnsPaddingOption
            query[optOffset + 13] = (paddingLength ushr 8).toByte()
            query[optOffset + 14] = paddingLength.toByte()
        }
    }
}

@Suppress("MagicNumber")
private fun isMatchingDnsResponse(
    query: ByteArray,
    response: ByteArray,
): Boolean =
    response.size >= DnsHeaderBytes &&
        response[0] == query[0] &&
        response[1] == query[1] &&
        response[2].toInt() and DnsResponseFlag != 0 &&
        ((response[4].toUByte().toInt() shl 8) or response[5].toUByte().toInt()) > 0

private fun DataInputStream.readNBytesExact(size: Int): ByteArray = ByteArray(size).also { bytes -> readFully(bytes) }

private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

private const val TcpProbeTimeoutSeconds = 10L
private const val UdpProbeTimeoutMillis = 5_000
private const val MaxUdpProbeResponseBytes = 4_096
private const val MinimumSocksUdpFrameBytes = 10
private const val DnsHeaderBytes = 12
private const val BaseDnsQueryBytes = 29
private const val DnsResponseFlag = 0x80
private const val DnsPort = 53
private const val DefaultDnsAddress = "94.140.14.14"
private const val EdnsUdpPayloadSize = 1_232
private const val EdnsPaddingMinimumOverheadBytes = 15
private const val EdnsOptionHeaderBytes = 4
private const val DnsEdnsPaddingOption: Byte = 12
private const val SocksVersion: Byte = 5
private const val NoAuthentication: Byte = 0
private const val SocksNoAuthMethodCount: Byte = 1
private const val SocksReserved: Byte = 0
private const val SocksPortZero = 0
private const val UdpAssociateCommand: Byte = 3
private const val ReplySucceeded = 0
private const val AddressIpv4: Byte = 1
private const val AddressDomain: Byte = 3
private const val AddressIpv6: Byte = 4
private val SuccessfulStatusRange = 200..299
private val SocksUdpAssociateRequest =
    byteArrayOf(
        SocksVersion,
        UdpAssociateCommand,
        SocksReserved,
        AddressIpv4,
        SocksReserved,
        SocksReserved,
        SocksReserved,
        SocksReserved,
        SocksReserved,
        SocksReserved,
    )
