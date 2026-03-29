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
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.data.toTemporaryResolverOverride
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import kotlinx.serialization.json.Json

internal object DiagnosticsScanWorkflow {
    private const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
    private const val BackgroundAutoPersistMinMatrixCoveragePercent = 75
    private const val BackgroundAutoPersistMinWinnerCoveragePercent = 50
    private val DerivableTcpStrategyFamilies =
        setOf(
            "hostfake",
            "fake_approx",
            "split",
            "disorder",
            "fake",
            "oob",
            "disoob",
            "tlsrec",
            "tlsrandrec",
            "tlsrec_split",
            "tlsrec_disorder",
            "tlsrec_fake",
        )
    private val DerivableQuicStrategyFamilies =
        setOf(
            "quic_disabled",
            "quic_compat_burst",
            "quic_realistic_burst",
            "quic_burst",
        )

    private data class ValidatedStrategyProbeRecommendation(
        val recommendation: StrategyProbeRecommendation,
        val winningTcpCandidate: StrategyProbeCandidateSummary?,
        val winningQuicCandidate: StrategyProbeCandidateSummary?,
        val isValid: Boolean,
    )

    internal sealed interface BackgroundAutoPersistEligibility {
        data object Eligible : BackgroundAutoPersistEligibility

        data class Rejected(
            val reason: BackgroundAutoPersistRejectionReason,
        ) : BackgroundAutoPersistEligibility
    }

    internal enum class BackgroundAutoPersistRejectionReason {
        MISSING_AUDIT_ASSESSMENT,
        LOW_CONFIDENCE,
        INSUFFICIENT_MATRIX_COVERAGE,
        INSUFFICIENT_WINNER_COVERAGE,
        NO_WINNER_TARGET_SUCCESS,
    }

    fun enrichScanReport(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredDnsPath: EncryptedDnsPathCandidate?,
    ): ScanReport {
        val strategyProbe =
            report.strategyProbeReport?.let { strategyProbe ->
                val recommendation = resolveValidatedStrategyProbeRecommendation(strategyProbe, settings)
                strategyProbe.copy(
                    recommendation = recommendation.recommendation,
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
        return (
            report.strategyProbeReport == null ||
                report.strategyProbeReport.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
        ) &&
            serviceMode == Mode.VPN &&
            serviceStatus == com.poyka.ripdpi.data.AppStatus.Running &&
            settings.activeDnsSettings().mode == DnsModePlainUdp
    }

    fun buildTemporaryResolverOverride(recommendation: ResolverRecommendation): TemporaryResolverOverride {
        val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        return selectedPath.toTemporaryResolverOverride(
            reason = recommendation.rationale,
            appliedAt = System.currentTimeMillis(),
        )
    }

    fun shouldReprobeWithCorrectedDns(
        report: ScanReport,
        pathMode: ScanPathMode,
        resolverOverrideApplied: Boolean,
    ): Boolean {
        if (!resolverOverrideApplied) return false
        if (pathMode != ScanPathMode.RAW_PATH) return false
        val strategyProbe = report.strategyProbeReport ?: return false
        return strategyProbe.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
    }

    fun evaluateBackgroundAutoPersistEligibility(
        strategyProbe: StrategyProbeReport,
    ): BackgroundAutoPersistEligibility {
        val assessment =
            strategyProbe.auditAssessment ?: return BackgroundAutoPersistEligibility.Rejected(
                BackgroundAutoPersistRejectionReason.MISSING_AUDIT_ASSESSMENT,
            )
        if (assessment.confidence.level != StrategyProbeAuditConfidenceLevel.HIGH) {
            return BackgroundAutoPersistEligibility.Rejected(
                BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE,
            )
        }
        if (assessment.coverage.matrixCoveragePercent < BackgroundAutoPersistMinMatrixCoveragePercent) {
            return BackgroundAutoPersistEligibility.Rejected(
                BackgroundAutoPersistRejectionReason.INSUFFICIENT_MATRIX_COVERAGE,
            )
        }
        if (assessment.coverage.winnerCoveragePercent < BackgroundAutoPersistMinWinnerCoveragePercent) {
            return BackgroundAutoPersistEligibility.Rejected(
                BackgroundAutoPersistRejectionReason.INSUFFICIENT_WINNER_COVERAGE,
            )
        }
        if (!hasWinningTargetSuccess(strategyProbe)) {
            return BackgroundAutoPersistEligibility.Rejected(
                BackgroundAutoPersistRejectionReason.NO_WINNER_TARGET_SUCCESS,
            )
        }
        return BackgroundAutoPersistEligibility.Eligible
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
        val recommendation = resolveValidatedStrategyProbeRecommendation(strategyProbe, settings)
        if (!recommendation.isValid) {
            return null
        }
        if (!hasWinningTargetSuccess(strategyProbe)) {
            return null
        }
        val activeDns = settings.activeDnsSettings()
        val networkScopeKey = fingerprint.scopeKey()
        val normalizedProxyConfigJson =
            stripRipDpiRuntimeContext(
                RipDpiProxyJsonPreferences(
                    configJson = recommendation.recommendation.recommendedProxyConfigJson,
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
                recommendation.recommendation.strategySignature?.let {
                    json.encodeToString(BypassStrategySignature.serializer(), it)
                },
            winningTcpStrategyFamily = recommendation.winningTcpCandidate?.family,
            winningQuicStrategyFamily = recommendation.winningQuicCandidate?.family,
            winningDnsStrategyFamily =
                recommendation.recommendation.dnsStrategyFamily ?: activeDns.strategyFamily(),
        )
    }

    private fun resolveValidatedStrategyProbeRecommendation(
        strategyProbe: StrategyProbeReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ValidatedStrategyProbeRecommendation {
        val activeDns = settings.activeDnsSettings()
        val baseRecommendation = strategyProbe.recommendation.clearDerivedMetadata()
        val winningTcpCandidate =
            strategyProbe.tcpCandidates.firstOrNull { it.id == baseRecommendation.tcpCandidateId }
        val winningQuicCandidate =
            strategyProbe.quicCandidates.firstOrNull { it.id == baseRecommendation.quicCandidateId }
        val preferences = decodeRipDpiProxyUiPreferences(baseRecommendation.recommendedProxyConfigJson)
        if (preferences == null || winningTcpCandidate == null || winningQuicCandidate == null) {
            return ValidatedStrategyProbeRecommendation(
                recommendation = baseRecommendation,
                winningTcpCandidate = winningTcpCandidate,
                winningQuicCandidate = winningQuicCandidate,
                isValid = false,
            )
        }
        val laneFamilies = preferences.deriveStrategyLaneFamilies(activeDns = activeDns)
        val familiesMatch =
            laneFamilyMatches(
                derivedFamily = laneFamilies.tcpStrategyFamily,
                winningFamily = winningTcpCandidate.family,
                derivableFamilies = DerivableTcpStrategyFamilies,
            ) &&
                laneFamilyMatches(
                    derivedFamily = laneFamilies.quicStrategyFamily,
                    winningFamily = winningQuicCandidate.family,
                    derivableFamilies = DerivableQuicStrategyFamilies,
                )
        if (!familiesMatch) {
            return ValidatedStrategyProbeRecommendation(
                recommendation = baseRecommendation,
                winningTcpCandidate = winningTcpCandidate,
                winningQuicCandidate = winningQuicCandidate,
                isValid = false,
            )
        }
        val strategySignature =
            deriveBypassStrategySignature(
                preferences = preferences,
                routeGroup = null,
                modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue }),
            ).copy(
                dnsStrategyFamily = activeDns.strategyFamily(),
                dnsStrategyLabel = activeDns.strategyLabel(),
            )
        return ValidatedStrategyProbeRecommendation(
            recommendation =
                baseRecommendation.copy(
                    tcpCandidateFamily = winningTcpCandidate.family,
                    quicCandidateFamily = winningQuicCandidate.family,
                    dnsStrategyFamily = activeDns.strategyFamily(),
                    dnsStrategyLabel = activeDns.strategyLabel(),
                    strategySignature = strategySignature,
                ),
            winningTcpCandidate = winningTcpCandidate,
            winningQuicCandidate = winningQuicCandidate,
            isValid = true,
        )
    }

    private fun laneFamilyMatches(
        derivedFamily: String?,
        winningFamily: String,
        derivableFamilies: Set<String>,
    ): Boolean {
        if (winningFamily !in derivableFamilies) {
            return true
        }
        return derivedFamily == winningFamily
    }

    private fun hasWinningTargetSuccess(strategyProbe: StrategyProbeReport): Boolean {
        val recommendedCandidateIds =
            setOf(
                strategyProbe.recommendation.tcpCandidateId,
                strategyProbe.recommendation.quicCandidateId,
            )
        return (strategyProbe.tcpCandidates + strategyProbe.quicCandidates).any { candidate ->
            candidate.id in recommendedCandidateIds && candidate.succeededTargets > 0
        }
    }

    private fun StrategyProbeRecommendation.clearDerivedMetadata(): StrategyProbeRecommendation =
        copy(
            tcpCandidateFamily = null,
            quicCandidateFamily = null,
            dnsStrategyFamily = null,
            dnsStrategyLabel = null,
            strategySignature = null,
        )
}
