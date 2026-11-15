@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CallTransportPath
import com.poyka.ripdpi.core.detection.CallTransportResult
import com.poyka.ripdpi.core.detection.CallTransportStunObservation
import com.poyka.ripdpi.core.detection.CallTransportStunScope
import com.poyka.ripdpi.core.detection.CallTransportStunServer
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CheckExecutionStatus
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NumericIp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
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
                                    NumericIp.parseAddress(address)?.toNormalizedString()
                                }?.let { address ->
                                    CallTransportStunObservation(path, server, address)
                                }
                            }
                        }
                    }.mapNotNull { deferred -> deferred.await() }

            val mtProtoReachable =
                mtProtoProber
                    .supportedPaths()
                    .firstOrNull { path ->
                        path == CallTransportPath.VPN || path == CallTransportPath.APP_DEFAULT
                    }?.let { path ->
                        runBooleanProbe {
                            mtProtoProber.canReach(path)
                        }
                    } ?: false

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
                underlyingProbed = CallTransportPath.UNDERLYING in stunPaths,
                stunSupported =
                    stunPaths.isNotEmpty() ||
                        (proxyEndpoint?.type == ProxyType.SOCKS5 && socks5StunClient != null),
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
                    executionStatus = CheckExecutionStatus.SKIPPED,
                ),
        )

    private fun resultFrom(
        observations: List<CallTransportStunObservation>,
        underlyingProbed: Boolean,
        stunSupported: Boolean,
        mtProtoReachable: Boolean,
        proxyEndpoint: ProxyEndpoint?,
        proxyStunReflexiveAddresses: List<String>,
    ): CallTransportResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()

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

        when {
            !stunSupported -> {
                findings += Finding("Call STUN leak: not evaluated, no STUN path is supported")
            }

            !underlyingProbed -> {
                findings += Finding("Call STUN leak: not evaluated, the underlying network path is not probed")
            }

            observations.isNotEmpty() -> {
                findings += Finding("Call STUN leak: not evaluated, STUN addresses do not establish the call path")
            }
        }

        if (mtProtoReachable) {
            findings +=
                Finding(
                    description = "MTProto DC2 reachable on a supported call transport path",
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

        return CallTransportResult(
            category =
                CategoryResult(
                    name = "Call transport",
                    detected = false,
                    findings = findings,
                    needsReview = evidence.isNotEmpty(),
                    evidence = evidence,
                    executionStatus =
                        if (stunSupported) CheckExecutionStatus.INCONCLUSIVE else CheckExecutionStatus.SKIPPED,
                ),
            stunObservations = observations,
            mtProtoReachable = mtProtoReachable,
            proxyEndpoint = proxyEndpoint,
            proxyStunReflexiveAddresses = proxyStunReflexiveAddresses,
        )
    }

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
    (this as? CallTransportPathAwareStunClient)?.supportedPaths ?: AppDefaultCallTransportPaths

private fun CallTransportMtProtoProber.supportedPaths(): Set<CallTransportPath> =
    (this as? CallTransportPathAwareMtProtoProber)?.supportedPaths ?: AppDefaultCallTransportPaths

private val AppDefaultCallTransportPaths = setOf(CallTransportPath.APP_DEFAULT)

private class DefaultCallTransportMtProtoProber(
    private val dispatchers: AppCoroutineDispatchers,
) : CallTransportPathAwareMtProtoProber {
    override val supportedPaths: Set<CallTransportPath> = AppDefaultCallTransportPaths

    override suspend fun canReach(path: CallTransportPath): Boolean =
        withContext(dispatchers.io) {
            if (path != CallTransportPath.APP_DEFAULT) return@withContext false
            try {
                withTimeoutOrNull(TimeoutMs.toLong()) {
                    java.net.Socket().use { socket ->
                        socket.withStunCancellation {
                            socket.connect(java.net.InetSocketAddress(TelegramDc2Host, TelegramDcPort), TimeoutMs)
                            true
                        }
                    }
                } == true
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
    override val supportedPaths: Set<CallTransportPath> = AppDefaultCallTransportPaths

    override suspend fun reflexiveAddress(
        path: CallTransportPath,
        server: CallTransportStunServer,
    ): String? =
        withContext(dispatchers.io) {
            if (path != CallTransportPath.APP_DEFAULT) return@withContext null
            try {
                withTimeoutOrNull(timeoutMs.toLong()) {
                    val target = resolveStunAddress(server.host)
                    DatagramSocket().use { socket ->
                        socket.withStunCancellation {
                            val transactionId = ByteArray(StunTransactionIdBytes).also(random::nextBytes)
                            val request = buildStunBindingRequest(transactionId)
                            socket.connect(java.net.InetSocketAddress(target, server.port))
                            socket.send(DatagramPacket(request, request.size))
                            socket.receiveStunResponse(timeoutMs) { response ->
                                StunBindingResponseDecoder.reflexiveAddress(response, transactionId)
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        }
}

private const val StunBindingRequest = 0x0001
private const val StunMagicCookie = 0x2112A442
private const val StunHeaderBytes = 20
private const val StunTransactionIdBytes = 12

private fun buildStunBindingRequest(transactionId: ByteArray): ByteArray =
    ByteBuffer
        .allocate(StunHeaderBytes)
        .putShort(StunBindingRequest.toShort())
        .putShort(0)
        .putInt(StunMagicCookie)
        .put(transactionId)
        .array()

private fun CallTransportPath.label(): String =
    name
        .lowercase()
        .replace('_', ' ')

private fun CallTransportStunScope.label(): String =
    name
        .lowercase()
        .replaceFirstChar(Char::uppercaseChar)
