package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeConfirmGoodDpiEvidence
import com.poyka.ripdpi.data.NativeConfirmGoodDpiEvidenceSource
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val ConfirmGoodControlTimeoutMs = 5_000
private const val DefaultHttpsPort = 443
private const val RequiredConfirmGoodTargets = 2

internal suspend fun collectConfirmGoodDpiEvidence(
    intent: DiagnosticsIntent,
    networkFingerprint: NetworkFingerprint?,
    serviceStateStore: ServiceStateStore,
): ConfirmGoodDpiEvidence? {
    val serviceTelemetry = serviceStateStore.telemetry.value
    val relay = serviceTelemetry.relayTelemetry
    val eligible =
        intent.kind == ScanKind.STRATEGY_PROBE &&
            relay.confirmGoodDpiEligible &&
            serviceStateStore.status.value.first == AppStatus.Running
    return if (eligible) {
        val scope = networkFingerprint?.scopeKey()
        val passive =
            relay.confirmGoodDpiEvidence
                ?.takeIf {
                    scope != null &&
                        serviceTelemetry.runtimeFieldTelemetry.telemetryNetworkFingerprintHash == scope &&
                        it.distinctTargetCount >= RequiredConfirmGoodTargets
                }?.toDiagnosticsEvidence()
        val active =
            if (intent.executionPolicy.manualOnly) {
                collectActiveConfirmGoodEvidence(intent, relay.listenerAddress)
            } else {
                null
            }
        when {
            active != null && passive != null -> {
                active.copy(
                    source = ConfirmGoodDpiEvidenceSource.MIXED,
                    stalledFlowCount = active.stalledFlowCount + passive.stalledFlowCount,
                    distinctTargetCount = maxOf(active.distinctTargetCount, passive.distinctTargetCount),
                )
            }

            active != null -> {
                active
            }

            else -> {
                passive
            }
        }
    } else {
        null
    }
}

internal suspend fun collectActiveConfirmGoodEvidence(
    intent: DiagnosticsIntent,
    listenerAddress: String?,
    probe: (InetSocketAddress, DomainTarget) -> Boolean = ::probeRealityControl,
): ConfirmGoodDpiEvidence? {
    val listener = listenerAddress?.toSocketAddress()
    val controls =
        intent.domainTargets
            .filter(DomainTarget::isControl)
            .distinctBy { it.host.lowercase() }
            .take(2)
    return if (listener != null && controls.size >= RequiredConfirmGoodTargets) {
        val stalled =
            withContext(Dispatchers.IO) {
                controls.count { target -> probe(listener, target) }
            }
        if (stalled >= RequiredConfirmGoodTargets) {
            ConfirmGoodDpiEvidence(
                source = ConfirmGoodDpiEvidenceSource.ACTIVE,
                stalledFlowCount = stalled,
                distinctTargetCount = controls.size,
                catalogProfileValidated = true,
                realityHandshakeConfirmed = true,
                applicationResponseBytes = 0,
            )
        } else {
            null
        }
    } else {
        null
    }
}

private fun probeRealityControl(
    relayAddress: InetSocketAddress,
    target: DomainTarget,
): Boolean {
    var relayConnected = false
    return try {
        val proxy = Proxy(Proxy.Type.SOCKS, relayAddress)
        Socket(proxy).use { socket ->
            socket.soTimeout = ConfirmGoodControlTimeoutMs
            socket.connect(
                InetSocketAddress.createUnresolved(target.host, target.httpsPort ?: DefaultHttpsPort),
                ConfirmGoodControlTimeoutMs,
            )
            relayConnected = true
            val tlsFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls =
                tlsFactory.createSocket(
                    socket,
                    target.host,
                    target.httpsPort ?: DefaultHttpsPort,
                    true,
                ) as SSLSocket
            tls.use {
                it.soTimeout = ConfirmGoodControlTimeoutMs
                it.startHandshake()
                val request = buildConfirmGoodRequest(target)
                it.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                it.inputStream.read()
                false
            }
        }
    } catch (_: SocketTimeoutException) {
        relayConnected
    } catch (_: java.io.IOException) {
        false
    }
}

private fun String.toSocketAddress(): InetSocketAddress? {
    val separator = lastIndexOf(':')
    return if (separator <= 0 || separator == lastIndex) {
        null
    } else {
        val host = substring(0, separator).removePrefix("[").removeSuffix("]")
        substring(separator + 1).toIntOrNull()?.let { port -> InetSocketAddress(host, port) }
    }
}

private fun buildConfirmGoodRequest(target: DomainTarget): String =
    "GET ${target.httpPath} HTTP/1.1\r\n" +
        "Host: ${target.host}\r\n" +
        "Range: bytes=0-0\r\n" +
        "Connection: close\r\n\r\n"

private fun NativeConfirmGoodDpiEvidence.toDiagnosticsEvidence(): ConfirmGoodDpiEvidence =
    ConfirmGoodDpiEvidence(
        source =
            when (source) {
                NativeConfirmGoodDpiEvidenceSource.ACTIVE -> ConfirmGoodDpiEvidenceSource.ACTIVE
                NativeConfirmGoodDpiEvidenceSource.PASSIVE -> ConfirmGoodDpiEvidenceSource.PASSIVE
                NativeConfirmGoodDpiEvidenceSource.MIXED -> ConfirmGoodDpiEvidenceSource.MIXED
            },
        stalledFlowCount = stalledFlowCount,
        distinctTargetCount = distinctTargetCount,
        catalogProfileValidated = catalogProfileValidated,
        realityHandshakeConfirmed = realityHandshakeConfirmed,
        applicationResponseBytes = applicationResponseBytes,
        quicControlSucceeded = quicControlSucceeded,
    )
