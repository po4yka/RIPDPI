package com.poyka.ripdpi.data

enum class FailureClass(
    val wireValue: String,
) {
    TunnelEstablish("tunnel_establish"),
    DnsInterference("dns_interference"),
    TlsInterference("tls_interference"),
    Timeout("timeout"),
    ResetAbort("reset_abort"),
    NetworkHandover("network_handover"),
    NativeIo("native_io"),
    Unexpected("unexpected"),
}

enum class RttBand(
    val wireValue: String,
) {
    Lt50("lt50"),
    Between50And99("50_99"),
    Between100And249("100_249"),
    Between250And499("250_499"),
    Gte500("500_plus"),
    Unknown("unknown");

    companion object
}

data class RuntimeFieldTelemetry(
    val failureClass: FailureClass? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val winningDnsStrategyFamily: String? = null,
    val proxyRttBand: RttBand = RttBand.Unknown,
    val resolverRttBand: RttBand = RttBand.Unknown,
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
) {
    val winningStrategyFamily: String?
        get() =
            aggregateWinningStrategyFamily(
                winningTcpStrategyFamily = winningTcpStrategyFamily,
                winningQuicStrategyFamily = winningQuicStrategyFamily,
                winningDnsStrategyFamily = winningDnsStrategyFamily,
            )

    val rttBand: RttBand
        get() = aggregateRttBand(proxyRttBand, resolverRttBand)

    val retryCount: Long
        get() = proxyRouteRetryCount + tunnelRecoveryRetryCount
}

fun deriveRuntimeFieldTelemetry(
    telemetryNetworkFingerprintHash: String?,
    winningTcpStrategyFamily: String?,
    winningQuicStrategyFamily: String?,
    winningDnsStrategyFamily: String?,
    proxyTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
    tunnelRecoveryRetryCount: Long,
    failureReason: FailureReason? = null,
): RuntimeFieldTelemetry =
    RuntimeFieldTelemetry(
        failureClass = classifyFailureClass(failureReason, proxyTelemetry, tunnelTelemetry),
        telemetryNetworkFingerprintHash = telemetryNetworkFingerprintHash,
        winningTcpStrategyFamily = winningTcpStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningQuicStrategyFamily = winningQuicStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningDnsStrategyFamily = winningDnsStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        proxyRttBand = RttBand.fromLatencyMs(proxyTelemetry.upstreamRttMs),
        resolverRttBand =
            RttBand.fromLatencyMs(
                tunnelTelemetry.resolverLatencyAvgMs ?: tunnelTelemetry.resolverLatencyMs,
            ),
        proxyRouteRetryCount = proxyTelemetry.routeChanges,
        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
    )

fun aggregateWinningStrategyFamily(
    winningTcpStrategyFamily: String?,
    winningQuicStrategyFamily: String?,
    winningDnsStrategyFamily: String? = null,
): String? =
    listOfNotNull(
        winningTcpStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningQuicStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningDnsStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().takeIf { it.isNotEmpty() }?.joinToString(" + ")

fun aggregateRttBand(
    proxyRttBand: RttBand,
    resolverRttBand: RttBand,
): RttBand =
    listOf(proxyRttBand, resolverRttBand)
        .filterNot { it == RttBand.Unknown }
        .maxByOrNull { it.ordinal }
        ?: RttBand.Unknown

fun classifyFailureClass(
    failureReason: FailureReason?,
    proxyTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
): FailureClass? {
    classifyFailureReasonDirectly(failureReason)?.let { return it }
    latestFailureText(proxyTelemetry, tunnelTelemetry)?.let { text ->
        classifyFailureText(text)?.let { return it }
    }
    return tunnelTelemetry.networkHandoverClass
        ?.takeIf { it.isNotBlank() }
        ?.let { FailureClass.NetworkHandover }
}

private fun classifyFailureReasonDirectly(failureReason: FailureReason?): FailureClass? =
    when (failureReason) {
        null -> null
        FailureReason.TunnelEstablishmentFailed -> FailureClass.TunnelEstablish
        is FailureReason.NativeError ->
            classifyFailureText(failureReason.message) ?: FailureClass.NativeIo
        is FailureReason.Unexpected ->
            failureReason.cause.message
                ?.let(::classifyFailureText)
                ?: FailureClass.Unexpected
    }

private fun latestFailureText(
    proxyTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
): String? {
    val latestEvent =
        (proxyTelemetry.nativeEvents + tunnelTelemetry.nativeEvents)
            .filter { event ->
                event.level.equals("error", ignoreCase = true) ||
                    event.level.equals("warn", ignoreCase = true)
            }.maxByOrNull { it.createdAt }
            ?.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    return latestEvent
        ?: tunnelTelemetry.lastError?.trim()?.takeIf { it.isNotEmpty() }
        ?: proxyTelemetry.lastError?.trim()?.takeIf { it.isNotEmpty() }
        ?: tunnelTelemetry.resolverFallbackReason?.trim()?.takeIf { it.isNotEmpty() }
}

private fun classifyFailureText(text: String): FailureClass? {
    val normalized = text.trim().lowercase()
    return when {
        normalized.contains("tunnel establishment") ||
            normalized.contains("vpn field not null") ||
            normalized.contains("tun fd") ->
            FailureClass.TunnelEstablish

        normalized.contains("dns_substitution") ||
            normalized.contains("dns_expected_mismatch") ||
            normalized.contains("udp dns") ||
            normalized.contains("resolver override") ||
            normalized.contains("dns failure") ||
            normalized.contains("dns blocked") ->
            FailureClass.DnsInterference

        normalized.contains("tls") ||
            normalized.contains("ssl") ||
            normalized.contains("mitm") ||
            normalized.contains("handshake") ||
            normalized.contains("whitelist_sni") ||
            normalized.contains("sni block") ->
            FailureClass.TlsInterference

        normalized.contains("timeout") ||
            normalized.contains("timed out") ->
            FailureClass.Timeout

        normalized.contains("connection reset") ||
            normalized.contains("connection aborted") ||
            normalized.contains("broken pipe") ||
            normalized.contains("reset") ||
            normalized.contains("abort") ->
            FailureClass.ResetAbort

        normalized.contains("transport_switch") ||
            normalized.contains("connectivity_loss") ||
            normalized.contains("link_refresh") ||
            normalized.contains("handover") ->
            FailureClass.NetworkHandover

        normalized.contains("i/o") ||
            normalized.contains("socket") ||
            normalized.contains("proxy exited") ||
            normalized.contains("native error") ->
            FailureClass.NativeIo

        else -> null
    }
}

fun RttBand.Companion.fromLatencyMs(latencyMs: Long?): RttBand =
    when {
        latencyMs == null || latencyMs < 0L -> RttBand.Unknown
        latencyMs < 50L -> RttBand.Lt50
        latencyMs < 100L -> RttBand.Between50And99
        latencyMs < 250L -> RttBand.Between100And249
        latencyMs < 500L -> RttBand.Between250And499
        else -> RttBand.Gte500
    }
