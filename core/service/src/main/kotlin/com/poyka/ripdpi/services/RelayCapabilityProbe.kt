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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
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
    UdpProbeTargetMissing("udp_probe_target_missing"),
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
    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        dnsTarget: InetSocketAddress,
    ): RelayUdpProbeResult
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
                    val dnsTarget = requirements.udpAssociateTarget
                    if (dnsTarget == null) {
                        null
                    } else {
                        async { udpProbe.probe(endpoint, dnsTarget) }
                    }
                } else {
                    null
                }
            val tcpResult = tcp?.await() ?: RelayTcpProbeResult(succeeded = true)
            val udpResult =
                when {
                    udp != null -> udp.await()
                    requirements.udpAssociate -> RelayUdpProbeResult.failure(RelayProbeFailure.UdpProbeTargetMissing)
                    else -> RelayUdpProbeResult.notRequired()
                }
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
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
    ): RelayUdpPayloadHealthEvidence = payloadHealthProbe.probe(endpoint, families, targets)
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
    private val timeoutMillis: Int,
) : RelayUdpAssociateProbe {
    constructor() : this(
        timeoutMillis = UdpProbeTimeoutMillis,
    )

    /**
     * cancel-safe at the coroutine boundary: all blocking socket operations
     * have a fixed deadline and every socket is closed before returning.
     */
    override suspend fun probe(
        endpoint: RelayProbeEndpoint,
        dnsTarget: InetSocketAddress,
    ): RelayUdpProbeResult =
        try {
            runInterruptible(Dispatchers.IO) { probeBlocking(endpoint, dnsTarget) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout)
        } catch (_: IOException) {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpIo)
        }

    private fun probeBlocking(
        endpoint: RelayProbeEndpoint,
        dnsTarget: InetSocketAddress,
    ): RelayUdpProbeResult {
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
                    probeSingleDnsDatagram(udp, udpRelay, dnsTarget, timeoutMillis)
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
    timeoutMillis: Int,
): RelayUdpProbeResult {
    val query = dnsQuery()
    val outcome = sendDnsProbePayloadOutcome(udp, udpRelay, dnsTarget, query, timeoutMillis)
    return when (outcome) {
        DnsProbePayloadOutcome.Acknowledged -> {
            RelayUdpProbeResult.success()
        }

        DnsProbePayloadOutcome.WriteFailed -> {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpWrite, associationOpened = true)
        }

        DnsProbePayloadOutcome.ReadTimeout -> {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout, associationOpened = true)
        }

        DnsProbePayloadOutcome.ResponseMismatch -> {
            RelayUdpProbeResult.failure(RelayProbeFailure.DnsResponse, associationOpened = true)
        }

        DnsProbePayloadOutcome.IoFailure -> {
            RelayUdpProbeResult.failure(RelayProbeFailure.UdpIo, associationOpened = true)
        }
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

private fun dnsQuery(payloadSizeBytes: Int? = null): ByteArray {
    val queryId = DnsSecureRandom.nextInt(DnsTransactionIdUpperBound)
    val question = dnsQuestion()
    val baseQueryBytes = DnsHeaderBytes + question.size
    val requestedSize = payloadSizeBytes ?: baseQueryBytes
    val includePadding = requestedSize >= baseQueryBytes + EdnsPaddingMinimumOverheadBytes
    val querySize =
        if (includePadding) {
            requestedSize
        } else {
            baseQueryBytes
        }
    return ByteArray(querySize).also { query ->
        query.writeUnsignedShort(DnsTransactionIdOffset, queryId)
        query.writeUnsignedShort(DnsFlagsOffset, DnsRecursionDesiredFlag)
        query.writeUnsignedShort(DnsQuestionCountOffset, DnsSingleRecordCount)
        question.copyInto(query, destinationOffset = DnsHeaderBytes)
        if (includePadding) {
            query.writeUnsignedShort(DnsAdditionalCountOffset, DnsSingleRecordCount)
            val optOffset = baseQueryBytes
            val paddingLength = requestedSize - baseQueryBytes - EdnsPaddingMinimumOverheadBytes
            writeEdnsPadding(query, optOffset, paddingLength)
        }
    }
}

private fun dnsQuestion(): ByteArray {
    val nonceLabel =
        ByteArray(DnsNonceLabelBytes) {
            DnsNonceAlphabet[DnsSecureRandom.nextInt(DnsNonceAlphabet.size)]
        }
    return byteArrayOf(nonceLabel.size.toByte()) +
        nonceLabel +
        byteArrayOf(DnsInvalidLabel.size.toByte()) +
        DnsInvalidLabel +
        byteArrayOf(DnsNameTerminator) +
        unsignedShortBytes(DnsRecordTypeA) +
        unsignedShortBytes(DnsClassInternet)
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

private fun ByteArray.writeUnsignedShort(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr ByteBits).toByte()
    this[offset + LowByteOffset] = value.toByte()
}

private fun DataInputStream.readNBytesExact(size: Int): ByteArray = ByteArray(size).also { bytes -> readFully(bytes) }

private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

private const val TcpProbeTimeoutSeconds = 10L
private const val UdpProbeTimeoutMillis = 5_000
private const val Ipv4AddressBytes = 4
private const val Ipv6AddressBytes = 16
private const val DnsHeaderBytes = 12
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
private const val DnsNonceLabelBytes = 12
private const val SocksVersion: Byte = 5
private const val NoAuthentication: Byte = 0
private const val SocksNoAuthMethodCount: Byte = 1
private const val SocksReserved: Byte = 0
private const val SocksPortZero = 0
private const val UdpAssociateCommand: Byte = 3
private const val ReplySucceeded = 0
private const val AddressIpv4: Byte = 1
private const val AddressIpv6: Byte = 4
private val SuccessfulStatusRange = 200..299
private val DnsInvalidLabel = "invalid".toByteArray(StandardCharsets.US_ASCII)
private val DnsNonceAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toByteArray(StandardCharsets.US_ASCII)
private val DnsSecureRandom = SecureRandom()
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
