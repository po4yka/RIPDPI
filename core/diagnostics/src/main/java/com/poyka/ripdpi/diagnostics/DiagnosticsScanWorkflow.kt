package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.data.TemporaryResolverOverride
import kotlinx.serialization.json.Json

internal object DiagnosticsScanWorkflow {

    private const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"

    fun enrichScanReport(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredDnsPath: EncryptedDnsPathCandidate?,
    ): ScanReport {
        val activeDns = settings.activeDnsSettings()
        val strategyProbe =
            report.strategyProbeReport?.let { strategyProbe ->
                val recommendation = strategyProbe.recommendation
                val laneFamilies =
                    decodeRipDpiProxyUiPreferences(recommendation.recommendedProxyConfigJson)
                        ?.deriveStrategyLaneFamilies(activeDns = activeDns)
                val strategySignature =
                    decodeRipDpiProxyUiPreferences(recommendation.recommendedProxyConfigJson)
                        ?.let { preferences ->
                            deriveBypassStrategySignature(
                                preferences = preferences,
                                routeGroup = null,
                                modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }),
                            )
                        }?.copy(
                            dnsStrategyFamily = activeDns.strategyFamily(),
                            dnsStrategyLabel = activeDns.strategyLabel(),
                        )
                val winningTcpCandidate =
                    strategyProbe.tcpCandidates.firstOrNull { it.id == recommendation.tcpCandidateId }
                val winningQuicCandidate =
                    strategyProbe.quicCandidates.firstOrNull { it.id == recommendation.quicCandidateId }
                strategyProbe.copy(
                    recommendation =
                        recommendation.copy(
                            tcpCandidateFamily = winningTcpCandidate?.family ?: laneFamilies?.tcpStrategyFamily,
                            quicCandidateFamily = winningQuicCandidate?.family ?: laneFamilies?.quicStrategyFamily,
                            dnsStrategyFamily = activeDns.strategyFamily(),
                            dnsStrategyLabel = activeDns.strategyLabel(),
                            strategySignature = strategySignature,
                        ),
                )
            }
        val resolverRecommendation =
            ResolverRecommendationEngine.compute(
                report = report,
                settings = settings,
                preferredPath = preferredDnsPath,
            )
        return report.copy(
            strategyProbeReport = strategyProbe,
            resolverRecommendation = resolverRecommendation,
        )
    }

    fun shouldApplyTemporaryResolverOverride(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        serviceStatus: com.poyka.ripdpi.data.AppStatus,
        serviceMode: Mode,
    ): Boolean {
        if (report.resolverRecommendation == null) return false
        return report.strategyProbeReport == null &&
            serviceMode == Mode.VPN &&
            serviceStatus == com.poyka.ripdpi.data.AppStatus.Running &&
            settings.activeDnsSettings().mode == DnsModePlainUdp
    }

    fun buildTemporaryResolverOverride(recommendation: ResolverRecommendation): TemporaryResolverOverride {
        val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        return TemporaryResolverOverride(
            resolverId = selectedPath.resolverId,
            protocol = selectedPath.protocol,
            host = selectedPath.host,
            port = selectedPath.port,
            tlsServerName = selectedPath.tlsServerName,
            bootstrapIps = selectedPath.bootstrapIps,
            dohUrl = selectedPath.dohUrl,
            dnscryptProviderName = selectedPath.dnscryptProviderName,
            dnscryptPublicKey = selectedPath.dnscryptPublicKey,
            reason = recommendation.rationale,
            appliedAt = System.currentTimeMillis(),
        )
    }

    fun buildRememberedNetworkPolicy(
        strategyProbe: StrategyProbeReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        fingerprint: NetworkFingerprint,
        hostAutolearnStorePath: String?,
        json: Json,
    ): RememberedNetworkPolicyJson? {
        if (strategyProbe.suiteId == StrategyProbeSuiteFullMatrixV1) {
            return null
        }
        val recommendedCandidateIds =
            setOf(
                strategyProbe.recommendation.tcpCandidateId,
                strategyProbe.recommendation.quicCandidateId,
            )
        val hasWinningTarget =
            (strategyProbe.tcpCandidates + strategyProbe.quicCandidates).any { candidate ->
                candidate.id in recommendedCandidateIds && candidate.succeededTargets > 0
            }
        if (!hasWinningTarget) {
            return null
        }
        val winningTcpStrategyFamily =
            strategyProbe.tcpCandidates.firstOrNull { it.id == strategyProbe.recommendation.tcpCandidateId }?.family
        val winningQuicStrategyFamily =
            strategyProbe.quicCandidates.firstOrNull { it.id == strategyProbe.recommendation.quicCandidateId }?.family
        val activeDns = settings.activeDnsSettings()
        val winningDnsStrategyFamily =
            strategyProbe.recommendation.dnsStrategyFamily ?: activeDns.strategyFamily()
        val networkScopeKey = fingerprint.scopeKey()
        val normalizedProxyConfigJson =
            stripRipDpiRuntimeContext(
                RipDpiProxyJsonPreferences(
                    configJson = strategyProbe.recommendation.recommendedProxyConfigJson,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    networkScopeKey = networkScopeKey,
                    runtimeContext = activeDns.toRipDpiRuntimeContext(),
                ).toNativeConfigJson(),
            )
        val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })
        val dnsPolicy =
            if (mode == Mode.VPN) {
                activeDns.toVpnDnsPolicyJson()
            } else {
                null
            }
        return RememberedNetworkPolicyJson(
            fingerprintHash = networkScopeKey,
            mode = mode.preferenceValue,
            summary = fingerprint.summary(),
            proxyConfigJson = normalizedProxyConfigJson,
            vpnDnsPolicy = dnsPolicy,
            strategySignatureJson =
                strategyProbe.recommendation.strategySignature?.let {
                    json.encodeToString(BypassStrategySignature.serializer(), it)
                },
            winningTcpStrategyFamily = winningTcpStrategyFamily,
            winningQuicStrategyFamily = winningQuicStrategyFamily,
            winningDnsStrategyFamily = winningDnsStrategyFamily,
        )
    }
}
