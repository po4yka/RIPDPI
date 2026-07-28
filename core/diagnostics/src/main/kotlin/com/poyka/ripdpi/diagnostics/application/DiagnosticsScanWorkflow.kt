@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiConnectionConcurrencyPolicy
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.core.withConnectionConcurrencyPolicy
import com.poyka.ripdpi.data.DirectModeVerdictResult
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedConnectionConcurrencyPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxEchStable
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.data.toTemporaryResolverOverride
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import kotlinx.serialization.json.Json

@Suppress("detekt.TooManyFunctions")
internal object DiagnosticsScanWorkflow {
    private const val MaxConnectionConcurrency = 8
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
            "seqovl",
            "tlsrec_split",
            "tlsrec_seqovl",
            "tlsrec_disorder",
            "tlsrec_fake",
            "fake_flags",
        )
    private val DerivableQuicStrategyFamilies =
        setOf(
            "quic_disabled",
            "quic_compat_burst",
            "quic_realistic_burst",
            "quic_burst",
            "quic_multi_initial_realistic",
            "quic_sni_split",
            "quic_crypto_split",
            "quic_padding_ladder",
            "quic_cid_churn",
            "quic_packet_number_gap",
            "quic_version_negotiation_decoy",
            "quic_fake_version",
            "quic_dummy_prepend",
            "quic_ipfrag2",
            "quic_ipfrag2_ipv6_ext",
        )
    private val NonPromotableStrategyProbeOutcomes = setOf("failed", "skipped", "not_applicable", "eliminated")

    private data class ValidatedStrategyProbeRecommendation(
        val recommendation: StrategyProbeRecommendation,
        val winningTcpCandidate: StrategyProbeCandidateSummary?,
        val winningQuicCandidate: StrategyProbeCandidateSummary?,
        val isValid: Boolean,
    )

    private data class StrategyPathSuppression(
        val reason: String,
        val summary: String,
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
        MISSING_CONTROL_EVIDENCE,
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
                    recommendation =
                        recommendation.recommendation.withConnectionConcurrencyAssessment(
                            strategyProbe.connectionConcurrencyAssessment,
                        ),
                )
            }
        val resolverRecommendation =
            ResolverRecommendationEngine.compute(
                report = report,
                settings = settings,
                preferredPath = preferredDnsPath,
            )
        val strategyRecommendation =
            StrategyRecommendationEngine.compute(
                report = report,
                currentTcpFamily = settings.deriveStrategyLaneFamilies().tcpStrategyFamily,
            )
        val directModeVerdict =
            deriveDirectModeVerdict(report)?.takeUnless { verdict ->
                verdict.result == DirectModeVerdictResult.TRANSPARENT_WORKS &&
                    (
                        report.strategyProbeReport?.let(::evaluateBackgroundAutoPersistEligibility)
                            is BackgroundAutoPersistEligibility.Rejected
                    )
            }
        val concurrencyDiagnosis =
            strategyProbe?.connectionConcurrencyAssessment?.let { assessment ->
                Diagnosis(
                    code = "connection_concurrency_interaction",
                    summary = "TLS fingerprint × same-SNI parallelism: ${assessment.verdict}",
                    severity =
                        if (assessment.verdict == ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED) {
                            "warning"
                        } else {
                            "info"
                        },
                    evidence =
                        listOf(
                            "verdict=${assessment.verdict}",
                            "coverage=${assessment.cleanCells}/${assessment.plannedCells}",
                            "safeCap=${assessment.safeCap ?: "unknown"}",
                            "selectedProfile=${assessment.selectedProfileId ?: "unknown"}",
                        ),
                    recommendation =
                        "A confirmed interaction applies the learned owned-stack profile and cap on the next " +
                            "service start; browser-originated TLS in proxy mode may bypass this control.",
                )
            }
        return report.copy(
            strategyProbeReport = strategyProbe,
            resolverRecommendation = resolverRecommendation,
            strategyRecommendation = strategyRecommendation,
            directModeVerdict = directModeVerdict,
            diagnoses = report.diagnoses + listOfNotNull(concurrencyDiagnosis),
        )
    }

    private fun StrategyProbeRecommendation.withConnectionConcurrencyAssessment(
        assessment: ConnectionConcurrencyAssessment?,
    ): StrategyProbeRecommendation {
        val confirmed = assessment?.takeIf { it.verdict == ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED }
        val selectedProfile = confirmed?.selectedProfileId
        val preferences = decodeRipDpiProxyUiPreferences(recommendedProxyConfigJson)
        return if (confirmed != null && selectedProfile != null && preferences != null) {
            copy(
                recommendedProxyConfigJson =
                    preferences
                        .withConnectionConcurrencyPolicy(
                            RipDpiConnectionConcurrencyPolicy(
                                selectedProfileId = selectedProfile,
                                perProfileCaps =
                                    confirmed.healthyCapsByProfile.mapValues { (_, cap) ->
                                        cap.coerceIn(1, MaxConnectionConcurrency)
                                    },
                            ),
                        ).toNativeConfigJson(),
            )
        } else {
            this
        }
    }

    fun shouldApplyTemporaryResolverOverride(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        serviceStatus: com.poyka.ripdpi.data.AppStatus,
        serviceMode: Mode,
        pathMode: ScanPathMode,
    ): Boolean {
        if (report.resolverRecommendation == null) return false
        return (
            report.strategyProbeReport == null ||
                report.strategyProbeReport.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED ||
                report.strategyProbeReport.completionKind == StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK
        ) &&
            serviceMode == Mode.VPN &&
            (
                serviceStatus == com.poyka.ripdpi.data.AppStatus.Running ||
                    (pathMode == ScanPathMode.RAW_PATH && serviceStatus == com.poyka.ripdpi.data.AppStatus.Halted)
            ) &&
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
        val strategyProbe = report.strategyProbeReport
        return when {
            !resolverOverrideApplied -> false
            pathMode != ScanPathMode.RAW_PATH -> false
            strategyProbe == null -> false
            strategyProbe.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> true
            strategyProbe.completionKind == StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK -> true
            else -> false
        }
    }

    fun evaluateBackgroundAutoPersistEligibility(
        strategyProbe: StrategyProbeReport,
    ): BackgroundAutoPersistEligibility {
        val assessment = strategyProbe.auditAssessment
        val rejectionReason =
            when {
                assessment == null -> {
                    BackgroundAutoPersistRejectionReason.MISSING_AUDIT_ASSESSMENT
                }

                assessment.confidence.level != StrategyProbeAuditConfidenceLevel.HIGH -> {
                    BackgroundAutoPersistRejectionReason.LOW_CONFIDENCE
                }

                assessment.coverage.matrixCoveragePercent < BackgroundAutoPersistMinMatrixCoveragePercent -> {
                    BackgroundAutoPersistRejectionReason.INSUFFICIENT_MATRIX_COVERAGE
                }

                assessment.coverage.winnerCoveragePercent < BackgroundAutoPersistMinWinnerCoveragePercent -> {
                    BackgroundAutoPersistRejectionReason.INSUFFICIENT_WINNER_COVERAGE
                }

                !hasSuccessfulControlEvidence(strategyProbe) -> {
                    BackgroundAutoPersistRejectionReason.MISSING_CONTROL_EVIDENCE
                }

                !hasWinningTargetSuccess(strategyProbe) -> {
                    BackgroundAutoPersistRejectionReason.NO_WINNER_TARGET_SUCCESS
                }

                else -> {
                    null
                }
            }
        return if (rejectionReason != null) {
            BackgroundAutoPersistEligibility.Rejected(rejectionReason)
        } else {
            BackgroundAutoPersistEligibility.Eligible
        }
    }

    fun buildRememberedNetworkPolicy(
        strategyProbe: StrategyProbeReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
        fingerprint: NetworkFingerprint,
        hostAutolearnStorePath: String?,
        json: Json,
    ): RememberedNetworkPolicyJson? {
        val recommendation = resolveValidatedStrategyProbeRecommendation(strategyProbe, settings)
        val isEligible =
            strategyProbe.suiteId != StrategyProbeSuiteFullMatrixV1 &&
                recommendation.isValid &&
                evaluateBackgroundAutoPersistEligibility(strategyProbe) == BackgroundAutoPersistEligibility.Eligible &&
                hasWinningTargetSuccess(strategyProbe)
        if (!isEligible) return null
        val activeDns = settings.activeDnsSettings()
        val networkScopeKey = fingerprint.scopeKey()
        val concurrencyAssessment = strategyProbe.connectionConcurrencyAssessment
        val concurrencyPolicy =
            concurrencyAssessment
                ?.takeIf { it.verdict == ConnectionConcurrencyVerdict.CONJUNCTION_CONFIRMED }
                ?.selectedProfileId
                ?.let { selectedProfile ->
                    RememberedConnectionConcurrencyPolicyJson(
                        classifierVersion = concurrencyAssessment.classifierVersion,
                        selectedProfileId = selectedProfile,
                        perProfileCaps =
                            concurrencyAssessment.healthyCapsByProfile.mapValues { (_, cap) ->
                                cap.coerceIn(1, MaxConnectionConcurrency)
                            },
                    )
                }
        val recommendationConfigJson =
            if (concurrencyPolicy == null) {
                recommendation.recommendation.recommendedProxyConfigJson
            } else {
                decodeRipDpiProxyUiPreferences(recommendation.recommendation.recommendedProxyConfigJson)
                    ?.withConnectionConcurrencyPolicy(
                        RipDpiConnectionConcurrencyPolicy(
                            selectedProfileId = concurrencyPolicy.selectedProfileId,
                            perProfileCaps = concurrencyPolicy.perProfileCaps,
                        ),
                    )?.toNativeConfigJson()
                    ?: recommendation.recommendation.recommendedProxyConfigJson
            }
        val normalizedProxyConfigJson =
            stripRipDpiRuntimeContext(
                RipDpiProxyJsonPreferences(
                    configJson = recommendationConfigJson,
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
            connectionConcurrencyPolicy = concurrencyPolicy,
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
        val suppression = detectTlsPathSuppression(settings, winningTcpCandidate)
        val preferences = decodeRipDpiProxyUiPreferences(baseRecommendation.recommendedProxyConfigJson)

        val inputsPresent =
            baseRecommendation.transportPivot == null && preferences != null
        val validInputs =
            if (inputsPresent && winningTcpCandidate != null && winningQuicCandidate != null) {
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
                val winnersPromotable =
                    winningTcpCandidate.isPromotableStrategyWinner(settings) &&
                        winningQuicCandidate.isPromotableStrategyWinner(settings)
                if (familiesMatch && winnersPromotable) {
                    Triple(preferences, winningTcpCandidate, winningQuicCandidate)
                } else {
                    null
                }
            } else {
                null
            }
        return if (validInputs != null) {
            val (validPreferences, validTcpCandidate, validQuicCandidate) = validInputs
            val strategySignature =
                deriveBypassStrategySignature(
                    preferences = validPreferences,
                    routeGroup = null,
                    modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue }),
                ).copy(
                    dnsStrategyFamily = activeDns.strategyFamily(),
                    dnsStrategyLabel = activeDns.strategyLabel(),
                )
            ValidatedStrategyProbeRecommendation(
                recommendation =
                    baseRecommendation.copy(
                        tcpCandidateFamily = validTcpCandidate.family,
                        quicCandidateFamily = validQuicCandidate.family,
                        quicCandidateLayoutFamily = validQuicCandidate.quicLayoutFamily,
                        dnsStrategyFamily = activeDns.strategyFamily(),
                        dnsStrategyLabel = activeDns.strategyLabel(),
                        strategySignature = strategySignature,
                        tlsPathSuppressed = suppression != null,
                        tlsPathSuppressionReason = suppression?.reason,
                        tlsPathSuppressionSummary = suppression?.summary,
                    ),
                winningTcpCandidate = validTcpCandidate,
                winningQuicCandidate = validQuicCandidate,
                isValid = true,
            )
        } else {
            ValidatedStrategyProbeRecommendation(
                recommendation =
                    baseRecommendation.copy(
                        tlsPathSuppressed = suppression != null,
                        tlsPathSuppressionReason = suppression?.reason,
                        tlsPathSuppressionSummary = suppression?.summary,
                    ),
                winningTcpCandidate = winningTcpCandidate,
                winningQuicCandidate = winningQuicCandidate,
                isValid = false,
            )
        }
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

    private fun StrategyProbeCandidateSummary.isPromotableStrategyWinner(
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): Boolean {
        val hasTargetSuccess = !skipped && succeededTargets > 0 && totalTargets > 0
        val hasPromotableOutcome = outcome.lowercase() !in NonPromotableStrategyProbeOutcomes
        val hasProductionEmitter = emitterTier != StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY
        val hasExactEmitter = !exactEmitterRequiresRoot || !emitterDowngraded
        val rootedCandidate = exactEmitterRequiresRoot || emitterTier == StrategyEmitterTier.ROOTED_PRODUCTION
        val hasRootAccess = !rootedCandidate || settings.rootModeEnabled
        return hasTargetSuccess &&
            hasPromotableOutcome &&
            hasProductionEmitter &&
            hasExactEmitter &&
            hasRootAccess
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

    private fun hasSuccessfulControlEvidence(strategyProbe: StrategyProbeReport): Boolean {
        val recommendedCandidateIds =
            setOf(
                strategyProbe.recommendation.tcpCandidateId,
                strategyProbe.recommendation.quicCandidateId,
            )
        return (strategyProbe.tcpCandidates + strategyProbe.quicCandidates).any { candidate ->
            candidate.id in recommendedCandidateIds &&
                !candidate.skipped &&
                candidate.domainOutcomes.any { outcome -> outcome.isControl && outcome.succeeded }
        }
    }

    private fun detectTlsPathSuppression(
        settings: com.poyka.ripdpi.proto.AppSettings,
        winningTcpCandidate: StrategyProbeCandidateSummary?,
    ): StrategyPathSuppression? {
        val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })
        if (mode != Mode.Proxy) {
            return null
        }
        val selectedTlsProfile = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile)
        val targetsEch =
            selectedTlsProfile == TlsFingerprintProfileFirefoxEchStable ||
                winningTcpCandidate?.family == "ech_split" ||
                winningTcpCandidate?.family == "ech_tlsrec"
        return if (targetsEch) {
            StrategyPathSuppression(
                reason = "proxy_mode_browser_native_ech_suppressed",
                summary =
                    "Proxy mode leaves browser-originated TLS and ECH under the browser/OS stack; " +
                        "the selected ECH-aware template applies only to traffic the app originates itself.",
            )
        } else {
            StrategyPathSuppression(
                reason = "proxy_mode_browser_native_tls_suppressed",
                summary =
                    "Proxy mode leaves browser-originated TLS under the browser/OS stack; " +
                        "the selected TLS template applies only to traffic the app originates itself.",
            )
        }
    }

    private fun StrategyProbeRecommendation.clearDerivedMetadata(): StrategyProbeRecommendation =
        copy(
            tcpCandidateFamily = null,
            quicCandidateFamily = null,
            quicCandidateLayoutFamily = null,
            dnsStrategyFamily = null,
            dnsStrategyLabel = null,
            strategySignature = null,
            tlsPathSuppressed = false,
            tlsPathSuppressionReason = null,
            tlsPathSuppressionSummary = null,
        )
}
