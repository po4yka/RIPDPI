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
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
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
                val udpRelay = openUdpAssociation(control.inetAddress, input, output)
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
    try {
        udp.send(DatagramPacket(frame, frame.size, udpRelay))
    } catch (_: IOException) {
        return RelayUdpProbeResult.failure(RelayProbeFailure.UdpWrite, associationOpened = true)
    }
    val response = DatagramPacket(ByteArray(MaxUdpProbeResponseBytes), MaxUdpProbeResponseBytes)
    return try {
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
        RelayUdpProbeResult.failure(RelayProbeFailure.UdpIo, associationOpened = true)
    }
}

internal fun sendDnsProbePayload(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
    timeoutMillis: Int,
): Boolean {
    require(timeoutMillis > 0) { "UDP probe timeout must be positive" }
    val frame = encodeSocksUdpFrame(target, query)
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
    return sendSocksUdpFrame(udp, udpRelay, frame) &&
        awaitMatchingDnsResponse(udp, udpRelay, target, query, deadlineNanos)
}

private fun sendSocksUdpFrame(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    frame: ByteArray,
): Boolean =
    try {
        udp.send(DatagramPacket(frame, frame.size, udpRelay))
        true
    } catch (_: IOException) {
        false
    }

private fun awaitMatchingDnsResponse(
    udp: DatagramSocket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
    deadlineNanos: Long,
): Boolean {
    var acknowledged = false
    var waiting = true
    while (waiting) {
        val response = receiveBeforeDeadline(udp, deadlineNanos)
        if (response == null) {
            waiting = false
        } else if (isMatchingSocksDnsResponse(response, udpRelay, target, query)) {
            acknowledged = true
            waiting = false
        }
    }
    return acknowledged
}

private fun receiveBeforeDeadline(
    udp: DatagramSocket,
    deadlineNanos: Long,
): DatagramPacket? {
    val remainingNanos = deadlineNanos - System.nanoTime()
    val remainingMillis =
        TimeUnit.NANOSECONDS
            .toMillis(remainingNanos)
            .coerceAtLeast(1L)
            .toInt()
    return if (remainingNanos <= 0L) {
        null
    } else {
        try {
            DatagramPacket(ByteArray(MaxUdpProbeResponseBytes), MaxUdpProbeResponseBytes).also { response ->
                udp.soTimeout = remainingMillis
                udp.receive(response)
            }
        } catch (_: IOException) {
            null
        }
    }
}

private fun isMatchingSocksDnsResponse(
    response: DatagramPacket,
    udpRelay: InetSocketAddress,
    target: InetSocketAddress,
    query: ByteArray,
): Boolean {
    val datagram = decodeSocksUdpDatagram(response.data, response.length)
    return matchesSocksEndpoint(response.socketAddress, udpRelay) &&
        datagram != null &&
        matchesSocksEndpoint(datagram.target, target) &&
        isMatchingDnsResponse(query, datagram.payload)
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
    controlRemoteAddress: InetAddress,
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
    val effectiveAddress = effectiveUdpRelayAddress(relayAddress, controlRemoteAddress)
    return InetSocketAddress(effectiveAddress, relayPort)
}

internal fun effectiveUdpRelayAddress(
    relayAddress: InetAddress,
    controlRemoteAddress: InetAddress,
): InetAddress =
    if (relayAddress.isAnyLocalAddress) {
        controlRemoteAddress
    } else {
        relayAddress
    }

private fun requireSocksProtocol(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw ProtocolException(message)
}

private fun readSocksAddress(input: DataInputStream): InetAddress =
    when (input.readUnsignedByte()) {
        AddressIpv4.toInt() -> InetAddress.getByAddress(input.readNBytesExact(Ipv4AddressBytes))
        AddressIpv6.toInt() -> InetAddress.getByAddress(input.readNBytesExact(Ipv6AddressBytes))
        else -> throw ProtocolException("SOCKS address type is unsupported")
    }

private fun encodeSocksUdpFrame(
    target: InetSocketAddress,
    payload: ByteArray,
): ByteArray {
    val address = target.address ?: throw ProtocolException("UDP probe target must be an IP address")
    val addressBytes = address.address
    val addressType = if (addressBytes.size == Ipv4AddressBytes) AddressIpv4 else AddressIpv6
    return ByteArray(SocksUdpAddressOffset + addressBytes.size + SocksPortBytes + payload.size).also { frame ->
        frame[SocksUdpAddressTypeOffset] = addressType
        addressBytes.copyInto(frame, destinationOffset = SocksUdpAddressOffset)
        val portOffset = SocksUdpAddressOffset + addressBytes.size
        frame.writeUnsignedShort(portOffset, target.port)
        payload.copyInto(frame, destinationOffset = portOffset + SocksPortBytes)
    }
}

private fun decodeSocksUdpPayload(
    frame: ByteArray,
    length: Int,
): ByteArray? = decodeSocksUdpDatagram(frame, length)?.payload

private data class DecodedSocksUdpDatagram(
    val target: InetSocketAddress,
    val payload: ByteArray,
)

private fun decodeSocksUdpDatagram(
    frame: ByteArray,
    length: Int,
): DecodedSocksUdpDatagram? =
    if (hasValidSocksUdpHeader(frame, length)) {
        decodeSocksUdpTargetAndPort(frame, length)?.let { (target, payloadOffset) ->
            frame.copyPayloadOrNull(payloadOffset, length)?.let { payload ->
                DecodedSocksUdpDatagram(target = target, payload = payload)
            }
        }
    } else {
        null
    }

private fun decodeSocksUdpTargetAndPort(
    frame: ByteArray,
    length: Int,
): Pair<InetSocketAddress, Int>? =
    socksAddressLength(frame)?.let { addressLength ->
        val portOffset = SocksUdpAddressOffset + addressLength
        val payloadOffset = portOffset + SocksPortBytes
        if (payloadOffset < length) {
            decodeSocksUdpTarget(frame, portOffset)?.let { target -> target to payloadOffset }
        } else {
            null
        }
    }

private fun decodeSocksUdpTarget(
    frame: ByteArray,
    portOffset: Int,
): InetSocketAddress? =
    when (frame[SocksUdpAddressTypeOffset]) {
        AddressIpv4,
        AddressIpv6,
        -> {
            InetSocketAddress(
                InetAddress.getByAddress(frame.copyOfRange(SocksUdpAddressOffset, portOffset)),
                frame.readUnsignedShort(portOffset),
            )
        }

        AddressDomain -> {
            val domainLength = frame[SocksUdpAddressOffset].toUByte().toInt()
            if (domainLength > 0) {
                val domain =
                    String(
                        frame,
                        SocksUdpAddressOffset + SocksDomainLengthPrefixBytes,
                        domainLength,
                        StandardCharsets.US_ASCII,
                    )
                InetSocketAddress.createUnresolved(domain, frame.readUnsignedShort(portOffset))
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

private fun matchesSocksEndpoint(
    actual: SocketAddress?,
    expected: InetSocketAddress,
): Boolean {
    val actualEndpoint = actual as? InetSocketAddress
    return actualEndpoint?.let { endpoint ->
        val actualAddress = endpoint.address
        val expectedAddress = expected.address
        endpoint.port == expected.port &&
            if (actualAddress != null && expectedAddress != null) {
                actualAddress.address.contentEquals(expectedAddress.address)
            } else {
                endpoint.hostString.equals(expected.hostString, ignoreCase = true)
            }
    } ?: false
}

private fun hasValidSocksUdpHeader(
    frame: ByteArray,
    length: Int,
): Boolean {
    if (length < MinimumSocksUdpFrameBytes) return false
    val reservedBytesValid =
        frame[SocksUdpReservedFirstOffset] == SocksReserved &&
            frame[SocksUdpReservedSecondOffset] == SocksReserved
    val fragmentationDisabled = frame[SocksUdpFragmentOffset] == SocksNoFragment
    return reservedBytesValid && fragmentationDisabled
}

private fun socksAddressLength(frame: ByteArray): Int? =
    when (frame[SocksUdpAddressTypeOffset]) {
        AddressIpv4 -> {
            Ipv4AddressBytes
        }

        AddressIpv6 -> {
            Ipv6AddressBytes
        }

        AddressDomain -> {
            frame
                .getOrNull(SocksUdpAddressOffset)
                ?.toUByte()
                ?.toInt()
                ?.plus(SocksDomainLengthPrefixBytes)
        }

        else -> {
            null
        }
    }

private fun ByteArray.copyPayloadOrNull(
    payloadOffset: Int,
    frameLength: Int,
): ByteArray? = if (payloadOffset < frameLength) copyOfRange(payloadOffset, frameLength) else null

private fun dnsQuery(payloadSizeBytes: Int = BaseDnsQueryBytes): ByteArray {
    val queryId = SecureRandom().nextInt(DnsTransactionIdUpperBound)
    val baseQueryBytes = DnsHeaderBytes + DnsQuestion.size
    val includePadding = payloadSizeBytes >= baseQueryBytes + EdnsPaddingMinimumOverheadBytes
    val querySize =
        if (includePadding) {
            payloadSizeBytes
        } else {
            baseQueryBytes
        }
    return ByteArray(querySize).also { query ->
        query.writeUnsignedShort(DnsTransactionIdOffset, queryId)
        query.writeUnsignedShort(DnsFlagsOffset, DnsRecursionDesiredFlag)
        query.writeUnsignedShort(DnsQuestionCountOffset, DnsSingleRecordCount)
        DnsQuestion.copyInto(query, destinationOffset = DnsHeaderBytes)
        if (includePadding) {
            query.writeUnsignedShort(DnsAdditionalCountOffset, DnsSingleRecordCount)
            val optOffset = baseQueryBytes
            val paddingLength = payloadSizeBytes - baseQueryBytes - EdnsPaddingMinimumOverheadBytes
            writeEdnsPadding(query, optOffset, paddingLength)
        }
    }
}

private fun writeEdnsPadding(
    query: ByteArray,
    optOffset: Int,
    paddingLength: Int,
) {
    query[optOffset + EdnsNameOffset] = DnsNameTerminator
    query.writeUnsignedShort(optOffset + EdnsRecordTypeOffset, DnsRecordTypeOpt)
    query.writeUnsignedShort(optOffset + EdnsUdpPayloadSizeOffset, EdnsUdpPayloadSize)
    query.writeUnsignedShort(
        optOffset + EdnsRecordDataLengthOffset,
        paddingLength + EdnsOptionHeaderBytes,
    )
    query.writeUnsignedShort(optOffset + EdnsOptionCodeOffset, DnsEdnsPaddingOption)
    query.writeUnsignedShort(optOffset + EdnsOptionLengthOffset, paddingLength)
}

private fun isMatchingDnsResponse(
    query: ByteArray,
    response: ByteArray,
): Boolean {
    if (response.size < DnsHeaderBytes) return false
    val transactionMatches =
        response.readUnsignedShort(DnsTransactionIdOffset) == query.readUnsignedShort(DnsTransactionIdOffset)
    val isResponse = response.readUnsignedShort(DnsFlagsOffset) and DnsResponseFlag != 0
    val hasQuestion = response.readUnsignedShort(DnsQuestionCountOffset) > 0
    return transactionMatches && isResponse && hasQuestion
}

private fun ByteArray.writeUnsignedShort(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr ByteBits).toByte()
    this[offset + LowByteOffset] = value.toByte()
}

private fun ByteArray.readUnsignedShort(offset: Int): Int =
    (this[offset].toUByte().toInt() shl ByteBits) or this[offset + LowByteOffset].toUByte().toInt()

private fun DataInputStream.readNBytesExact(size: Int): ByteArray = ByteArray(size).also { bytes -> readFully(bytes) }

private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

private const val TcpProbeTimeoutSeconds = 10L
private const val UdpProbeTimeoutMillis = 5_000
private const val MaxUdpProbeResponseBytes = 4_096
private const val MinimumSocksUdpFrameBytes = 10
private const val Ipv4AddressBytes = 4
private const val Ipv6AddressBytes = 16
private const val SocksUdpReservedFirstOffset = 0
private const val SocksUdpReservedSecondOffset = 1
private const val SocksUdpFragmentOffset = 2
private const val SocksUdpAddressTypeOffset = 3
private const val SocksUdpAddressOffset = 4
private const val SocksPortBytes = 2
private const val SocksDomainLengthPrefixBytes = 1
private const val SocksNoFragment: Byte = 0
private const val DnsHeaderBytes = 12
private const val BaseDnsQueryBytes = 29
private const val DnsResponseFlag = 0x8000
private const val DnsTransactionIdUpperBound = 1 shl 16
private const val DnsTransactionIdOffset = 0
private const val DnsFlagsOffset = 2
private const val DnsQuestionCountOffset = 4
private const val DnsAdditionalCountOffset = 10
private const val DnsRecursionDesiredFlag = 0x0100
private const val DnsSingleRecordCount = 1
private const val DnsRecordTypeA = 1
private const val DnsClassInternet = 1
private const val DnsRecordTypeOpt = 41
private const val DnsNameTerminator: Byte = 0
private const val DnsPort = 53
private const val DefaultDnsAddress = "94.140.14.14"
private const val EdnsUdpPayloadSize = 1_232
private const val EdnsPaddingMinimumOverheadBytes = 15
private const val EdnsOptionHeaderBytes = 4
private const val DnsEdnsPaddingOption = 12
private const val EdnsNameOffset = 0
private const val EdnsRecordTypeOffset = 1
private const val EdnsUdpPayloadSizeOffset = 3
private const val EdnsRecordDataLengthOffset = 9
private const val EdnsOptionCodeOffset = 11
private const val EdnsOptionLengthOffset = 13
private const val ByteBits = 8
private const val LowByteOffset = 1
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
private val DnsExampleLabel = "example".toByteArray(StandardCharsets.US_ASCII)
private val DnsComLabel = "com".toByteArray(StandardCharsets.US_ASCII)
private val DnsQuestion =
    byteArrayOf(DnsExampleLabel.size.toByte()) +
        DnsExampleLabel +
        byteArrayOf(DnsComLabel.size.toByte()) +
        DnsComLabel +
        byteArrayOf(DnsNameTerminator) +
        unsignedShortBytes(DnsRecordTypeA) +
        unsignedShortBytes(DnsClassInternet)
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

private fun unsignedShortBytes(value: Int): ByteArray =
    byteArrayOf(
        (value ushr ByteBits).toByte(),
        value.toByte(),
    )
