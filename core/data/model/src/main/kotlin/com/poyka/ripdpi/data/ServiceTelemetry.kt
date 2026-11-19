package com.poyka.ripdpi.data

private const val LatencyFastMs = 50L
private const val LatencyModerateMs = 100L
private const val LatencySlowMs = 250L
private const val LatencyVerySlowMs = 500L
private const val DhtTriggerCorrelationWindowMs = 10 * 60 * 1000L

enum class FailureClass(
    val wireValue: String,
) {
    TunnelEstablish("tunnel_establish"),
    WarpProvisioning("warp_provisioning"),
    WarpEndpoint("warp_endpoint"),
    FingerprintPolicy("fingerprint_policy"),
    DnsInterference("dns_interference"),
    TlsInterference("tls_interference"),
    Timeout("timeout"),
    ResetAbort("reset_abort"),
    NetworkHandover("network_handover"),
    Compatibility("relay_compatibility"),
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
    Unknown("unknown"),
    ;

    companion object
}

data class RuntimeFieldTelemetry(
    val failureClass: FailureClass? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val winningDnsStrategyFamily: String? = null,
    val strategyPackId: String? = null,
    val strategyPackVersion: String? = null,
    val tlsProfileId: String? = null,
    val tlsProfileCatalogVersion: String? = null,
    val morphPolicyId: String? = null,
    val quicMigrationStatus: String? = null,
    val ptRuntimeKind: String? = null,
    val ptRuntimeState: String? = null,
    val proxyRttBand: RttBand = RttBand.Unknown,
    val resolverRttBand: RttBand = RttBand.Unknown,
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val dhtTriggerCorrelationActive: Boolean = false,
    val dhtTriggerCorrelationReason: String? = null,
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

@Suppress("LongMethod")
fun deriveRuntimeFieldTelemetry(
    telemetryNetworkFingerprintHash: String?,
    winningTcpStrategyFamily: String?,
    winningQuicStrategyFamily: String?,
    winningDnsStrategyFamily: String?,
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
    tunnelRecoveryRetryCount: Long,
    failureReason: FailureReason? = null,
    failureAtMillis: Long? = null,
): RuntimeFieldTelemetry =
    classifyFailureClass(
        failureReason = failureReason,
        failureAtMillis = failureAtMillis,
        proxyTelemetry = proxyTelemetry,
        relayTelemetry = relayTelemetry,
        warpTelemetry = warpTelemetry,
        tunnelTelemetry = tunnelTelemetry,
    ).let { failureClass ->
        val runtimeStrategyPackId =
            firstPresent(
                proxyTelemetry.strategyPackId,
                relayTelemetry.strategyPackId,
                warpTelemetry.strategyPackId,
                tunnelTelemetry.strategyPackId,
            )
        val runtimeStrategyPackVersion =
            firstPresent(
                proxyTelemetry.strategyPackVersion,
                relayTelemetry.strategyPackVersion,
                warpTelemetry.strategyPackVersion,
                tunnelTelemetry.strategyPackVersion,
            )
        val runtimeTlsProfileId =
            firstPresent(
                relayTelemetry.tlsProfileId,
                proxyTelemetry.tlsProfileId,
                warpTelemetry.tlsProfileId,
                tunnelTelemetry.tlsProfileId,
            )
        val runtimeTlsProfileCatalogVersion =
            firstPresent(
                relayTelemetry.tlsProfileCatalogVersion,
                proxyTelemetry.tlsProfileCatalogVersion,
                warpTelemetry.tlsProfileCatalogVersion,
                tunnelTelemetry.tlsProfileCatalogVersion,
            )
        val runtimeMorphPolicyId =
            firstPresent(
                proxyTelemetry.morphPolicyId,
                relayTelemetry.morphPolicyId,
                warpTelemetry.morphPolicyId,
                tunnelTelemetry.morphPolicyId,
            )
        val runtimeQuicMigrationStatus =
            firstPresent(
                proxyTelemetry.quicMigrationStatus,
                relayTelemetry.quicMigrationStatus,
                warpTelemetry.quicMigrationStatus,
                tunnelTelemetry.quicMigrationStatus,
            )
        val runtimePtKind =
            firstPresent(
                relayTelemetry.ptRuntimeKind,
                proxyTelemetry.ptRuntimeKind,
                warpTelemetry.ptRuntimeKind,
                tunnelTelemetry.ptRuntimeKind,
            )
        val runtimePtState =
            firstPresent(
                relayTelemetry.ptRuntimeState,
                proxyTelemetry.ptRuntimeState,
                warpTelemetry.ptRuntimeState,
                tunnelTelemetry.ptRuntimeState,
            )
        RuntimeFieldTelemetry(
            failureClass = failureClass,
            telemetryNetworkFingerprintHash = telemetryNetworkFingerprintHash,
            winningTcpStrategyFamily = winningTcpStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
            winningQuicStrategyFamily = winningQuicStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
            winningDnsStrategyFamily = winningDnsStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
            strategyPackId = runtimeStrategyPackId,
            strategyPackVersion = runtimeStrategyPackVersion,
            tlsProfileId = runtimeTlsProfileId,
            tlsProfileCatalogVersion = runtimeTlsProfileCatalogVersion,
            morphPolicyId = runtimeMorphPolicyId,
            quicMigrationStatus = runtimeQuicMigrationStatus,
            ptRuntimeKind = runtimePtKind,
            ptRuntimeState = runtimePtState,
            proxyRttBand = RttBand.fromLatencyMs(proxyTelemetry.upstreamRttMs),
            resolverRttBand =
                RttBand.fromLatencyMs(
                    tunnelTelemetry.resolverLatencyAvgMs ?: tunnelTelemetry.resolverLatencyMs,
                ),
            proxyRouteRetryCount = proxyTelemetry.routeChanges,
            tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
            dhtTriggerCorrelationActive =
                hasRecentDhtTriggerCorrelation(
                    proxyTelemetry = proxyTelemetry,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    failureClass = failureClass,
                ),
            dhtTriggerCorrelationReason =
                buildDhtTriggerCorrelationReason(
                    proxyTelemetry = proxyTelemetry,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    failureClass = failureClass,
                ),
        )
    }

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

/**
 * [failureAtMillis] is when [failureReason] was raised. A runtime's `lastFailureClass` lasts for its whole session,
 * so it describes only the live state; a raised reason takes a native class only from a native event recorded
 * within [FreshNativeFailureMillis] before it.
 */
fun classifyFailureClass(
    failureReason: FailureReason?,
    failureAtMillis: Long?,
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
): FailureClass? {
    val nativeClass = latestNativeFailureClass(proxyTelemetry, relayTelemetry, warpTelemetry)
    val freshNativeClass = freshNativeFailureClass(failureAtMillis, proxyTelemetry, relayTelemetry, warpTelemetry)
    return when (failureReason) {
        null -> {
            nativeClass ?: tunnelFailureClass(tunnelTelemetry) ?: tunnelTelemetry.networkHandoverClass
                ?.takeIf { it.isNotBlank() }
                ?.let { FailureClass.NetworkHandover }
        }

        FailureReason.TunnelEstablishmentFailed -> {
            FailureClass.TunnelEstablish
        }

        is FailureReason.WarpProvisioningFailed -> {
            FailureClass.WarpProvisioning
        }

        is FailureReason.WarpEndpointUnavailable -> {
            FailureClass.WarpEndpoint
        }

        is FailureReason.RelayFingerprintPolicyRejected -> {
            FailureClass.FingerprintPolicy
        }

        is FailureReason.RelayConfigRejected -> {
            failureReason.failureClass
        }

        is FailureReason.InitialTransportSelectionFailed -> {
            FailureClass.Timeout
        }

        is FailureReason.WarpRuntimeFailed,
        is FailureReason.NativeError,
        -> {
            freshNativeClass ?: FailureClass.NativeIo
        }

        is FailureReason.Unexpected -> {
            freshNativeClass ?: FailureClass.Unexpected
        }

        is FailureReason.PermissionLost -> {
            FailureClass.Unexpected
        }
    }
}

/**
 * Decodes a typed failure code reported in `NativeRuntimeSnapshot.lastFailureClass`: the Rust
 * `ripdpi_failure_classifier::FailureClass::as_str` values plus the Kotlin relay prelaunch codes.
 * Only exact codes are accepted; message text never selects a class.
 */
fun failureClassFromNativeCode(code: String): FailureClass? =
    when (code) {
        "dns", "dns_tampering", "dns_resolution_failure" -> {
            FailureClass.DnsInterference
        }

        "tls_alert", "tls_handshake_failure", "http_blockpage", "redirect", "confirm_good_dpi_suspected" -> {
            FailureClass.TlsInterference
        }

        "tcp_reset" -> {
            FailureClass.ResetAbort
        }

        "silent_drop", "connection_freeze", "connect_failure", "ip_block_suspect" -> {
            FailureClass.Timeout
        }

        "relay_compatibility", "tuic_version_unsupported", "shadowtls_version_mismatch",
        "masque_h3_tcp_unsupported",
        -> {
            FailureClass.Compatibility
        }

        "quic_breakage", "strategy_execution_failure", "capability_skipped" -> {
            FailureClass.NativeIo
        }

        else -> {
            null
        }
    }

/**
 * The tunnel's own typed signals: a recorded tunnel worker error (its presence, never its text) is a tunnel failure,
 * and an active resolver fallback (the DNS override the service applied after DNS failures) is DNS interference.
 */
private fun tunnelFailureClass(tunnelTelemetry: NativeRuntimeSnapshot): FailureClass? =
    when {
        !tunnelTelemetry.lastError.isNullOrBlank() -> FailureClass.TunnelEstablish
        tunnelTelemetry.resolverFallbackActive -> FailureClass.DnsInterference
        else -> null
    }

private fun latestNativeFailureClass(
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
): FailureClass? =
    listOf(relayTelemetry, warpTelemetry, proxyTelemetry).firstNotNullOfOrNull { snapshot ->
        snapshot.lastFailureClass?.trim()?.let(::failureClassFromNativeCode)
    }

/** How long before a raised failure reason a native failure event still belongs to it. */
const val FreshNativeFailureMillis = 30_000L

private fun freshNativeFailureClass(
    failureAtMillis: Long?,
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
): FailureClass? {
    if (failureAtMillis == null) return null
    return (relayTelemetry.nativeEvents + warpTelemetry.nativeEvents + proxyTelemetry.nativeEvents)
        .filter { it.createdAt in (failureAtMillis - FreshNativeFailureMillis)..failureAtMillis }
        .sortedByDescending { it.createdAt }
        .firstNotNullOfOrNull { event -> event.failureClass?.trim()?.let(::failureClassFromNativeCode) }
}

private fun hasRecentDhtTriggerCorrelation(
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
    failureClass: FailureClass?,
): Boolean =
    buildDhtTriggerCorrelationReason(proxyTelemetry, relayTelemetry, warpTelemetry, tunnelTelemetry, failureClass) !=
        null

private fun firstPresent(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

@Suppress("ReturnCount")
private fun buildDhtTriggerCorrelationReason(
    proxyTelemetry: NativeRuntimeSnapshot,
    relayTelemetry: NativeRuntimeSnapshot,
    warpTelemetry: NativeRuntimeSnapshot,
    tunnelTelemetry: NativeRuntimeSnapshot,
    failureClass: FailureClass?,
): String? {
    val observedAt = tunnelTelemetry.lastDhtTriggerAt ?: return null
    val now =
        maxOf(
            proxyTelemetry.capturedAt,
            relayTelemetry.capturedAt,
            warpTelemetry.capturedAt,
            tunnelTelemetry.capturedAt,
        )
    if (now != 0L && now - observedAt > DhtTriggerCorrelationWindowMs) {
        return null
    }
    val relevantFailure = failureClass?.takeIf(::isDhtCorrelationFailureClass) ?: return null
    val destination = tunnelTelemetry.lastDhtTriggerEndpoint ?: "known trigger CIDR"
    return "Recent UDP traffic to $destination was followed by " +
        "${relevantFailure.wireValue} on relay, WARP, or TLS control-plane paths."
}

private fun isDhtCorrelationFailureClass(failureClass: FailureClass): Boolean =
    failureClass in
        setOf(
            FailureClass.TlsInterference,
            FailureClass.Timeout,
            FailureClass.ResetAbort,
            FailureClass.WarpEndpoint,
            FailureClass.FingerprintPolicy,
        )

fun RttBand.Companion.fromLatencyMs(latencyMs: Long?): RttBand =
    when {
        latencyMs == null || latencyMs < 0L -> RttBand.Unknown
        latencyMs < LatencyFastMs -> RttBand.Lt50
        latencyMs < LatencyModerateMs -> RttBand.Between50And99
        latencyMs < LatencySlowMs -> RttBand.Between100And249
        latencyMs < LatencyVerySlowMs -> RttBand.Between250And499
        else -> RttBand.Gte500
    }
