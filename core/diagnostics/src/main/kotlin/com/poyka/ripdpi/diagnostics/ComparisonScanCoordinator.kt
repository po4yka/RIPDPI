package com.poyka.ripdpi.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComparisonScanCoordinator
    @Inject
    constructor() {
        internal fun assessConnectivity(
            rawReports: List<ScanReport>,
            inPathReport: ScanReport?,
            rawPathSessionIds: List<String>,
            inPathSessionId: String?,
            serviceRuntimeAssessment: ConnectivityServiceRuntimeAssessment = ConnectivityServiceRuntimeAssessment(),
            rawProfilesByTarget: Map<ConnectivityTargetKey, Set<String>>? = null,
            activePathIsVpn: Boolean = false,
        ): ConnectivityAssessment {
            val completeRawReports = rawReports.filter { it.completionKind == ScanCompletionKind.NORMAL }
            val completedRawCount = completeRawReports.distinctBy(ScanReport::sessionId).size
            val rawReportCount =
                maxOf(rawReports.distinctBy(ScanReport::sessionId).size, rawPathSessionIds.distinct().size)
            val completeInPathReport = inPathReport?.takeIf { it.completionKind == ScanCompletionKind.NORMAL }
            val rawObservations = targetObservations(completeRawReports)
            val activeObservations = targetObservations(listOfNotNull(completeInPathReport))
            val rawTargets = rawObservations.aggregateTargets()
            val inPathTargets = activeObservations.aggregateTargets()
            val comparisonInputs =
                ConnectivityComparisonInputs(rawObservations, activeObservations, rawProfilesByTarget)
            val comparison =
                ConnectivityPathComparison(
                    completeRawReports = completedRawCount,
                    incompleteRawReports = rawReportCount - completedRawCount,
                    activePathCoverage = inPathReport.activePathCoverage(inPathSessionId),
                    activePathIsVpn = activePathIsVpn,
                ).withObservations(comparisonInputs)
            val matchedRawTargets = comparisonInputs.matchedRaw().aggregateTargets()
            val rawEvidence = rawTargets.toConnectivityEvidence(completeRawReports.map(ScanReport::sessionId))
            val inPathEvidence =
                inPathTargets
                    .filterKeys { it in matchedRawTargets.keys }
                    .toConnectivityEvidence(listOfNotNull(completeInPathReport?.sessionId))
            val resolverAssessment = buildResolverAssessment(completeRawReports)
            val rawControlSuccess = rawEvidence.controlSuccessCount > 0
            val rawControlFailure = rawEvidence.controlFailureCount > 0
            val controlTiers = ControlTierOutcome.of(rawTargets.values)
            val rawAffectedFailure = rawEvidence.affectedTargetFailureCount > 0
            val resolverMismatchAuthorities =
                resolverAssessment.mismatchTargets.map(String::normalizedProbeAuthority).toSet()
            val resolverLinkedFailureCount =
                rawEvidence.affectedTargets.count { affectedTarget ->
                    affectedTarget.normalizedProbeAuthority() in resolverMismatchAuthorities
                }
            val inPathRegression = detectInPathRegression(matchedRawTargets, inPathTargets)
            val code =
                deriveAssessmentCode(
                    rawControlSuccess = rawControlSuccess,
                    rawControlFailure = rawControlFailure,
                    controlTiers = controlTiers,
                    rawAffectedFailure = rawAffectedFailure,
                    resolverLinkedFailure = resolverLinkedFailureCount > 0,
                    inPathRegression = inPathRegression,
                    serviceRuntimeAssessment = serviceRuntimeAssessment,
                    inPathReport = completeInPathReport,
                    inPathEvidence = inPathEvidence,
                )
            val controlOutcome =
                when {
                    rawEvidence.controls.isEmpty() -> "no_controls"
                    controlTiers.internationalFailure -> "raw_controls_international_failed"
                    controlTiers.cdnOnlyFailure -> "raw_controls_cdn_blocked"
                    rawControlSuccess && !rawControlFailure -> "raw_controls_passed"
                    rawControlSuccess -> "raw_controls_mixed"
                    rawControlFailure -> "raw_controls_failed"
                    else -> "raw_controls_inconclusive"
                }
            return ConnectivityAssessment(
                assessmentCode = code,
                assessmentSummary =
                    assessmentSummary(
                        code = code,
                        resolverLinkedFailureCount = resolverLinkedFailureCount,
                        affectedTargetFailureCount = rawEvidence.affectedTargetFailureCount,
                    ),
                confidence = comparison.confidenceFor(code),
                pathComparison = comparison,
                rawPathEvidence = rawEvidence,
                inPathEvidence = inPathEvidence,
                controlOutcome = controlOutcome,
                affectedTargets = rawEvidence.affectedTargets,
                resolverAssessment = resolverAssessment,
                serviceRuntimeAssessment = serviceRuntimeAssessment,
                recommendedNextAction = assessmentNextAction(code),
            )
        }

        companion object {
            private fun deriveAssessmentCode(
                rawControlSuccess: Boolean,
                rawControlFailure: Boolean,
                controlTiers: ControlTierOutcome,
                rawAffectedFailure: Boolean,
                resolverLinkedFailure: Boolean,
                inPathRegression: Boolean,
                serviceRuntimeAssessment: ConnectivityServiceRuntimeAssessment,
                inPathReport: ScanReport?,
                inPathEvidence: ConnectivityEvidence,
            ): ConnectivityAssessmentCode =
                when {
                    rawControlFailure && !rawControlSuccess && rawAffectedFailure -> {
                        ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE
                    }

                    controlTiers.internationalFailure && rawAffectedFailure -> {
                        ConnectivityAssessmentCode.RAW_NETWORK_INTERNATIONAL_FAILURE
                    }

                    inPathRegression -> {
                        ConnectivityAssessmentCode.VPN_PATH_REGRESSION
                    }

                    resolverLinkedFailure && rawAffectedFailure -> {
                        ConnectivityAssessmentCode.RESOLVER_INTERFERENCE
                    }

                    controlTiers.foreignBaselinePassed(rawControlSuccess) && rawAffectedFailure -> {
                        ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING
                    }

                    serviceRuntimeAssessment.actionable &&
                        (
                            inPathReport == null ||
                                inPathEvidence.controlFailureCount > inPathEvidence.controlSuccessCount
                        ) -> {
                        ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE
                    }

                    else -> {
                        ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE
                    }
                }

            private fun assessmentSummary(
                code: ConnectivityAssessmentCode,
                resolverLinkedFailureCount: Int,
                affectedTargetFailureCount: Int,
            ): String =
                when (code) {
                    ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE -> {
                        "Observed pattern: raw-path control and affected targets failed together. " +
                            "This is consistent with a broader direct-network problem; cause is not established."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_INTERNATIONAL_FAILURE -> {
                        "Observed pattern: domestic controls passed while every foreign control and affected " +
                            "targets failed. This is consistent with a degraded international link; " +
                            "cause is not established."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING -> {
                        "Observed pattern: raw-path controls passed while affected targets failed. " +
                            "This is consistent with target-specific direct-path interference; " +
                            "cause is not established."
                    }

                    ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> {
                        "Observed pattern: the paired in-path run performed worse than raw path on the " +
                            "same targets. This is a candidate signal of an in-path regression; " +
                            "cause is not established."
                    }

                    ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> {
                        val unexplainedFailureCount = affectedTargetFailureCount - resolverLinkedFailureCount
                        val unexplainedSummary =
                            if (unexplainedFailureCount > 0) {
                                val noun = if (unexplainedFailureCount == 1) "failure remains" else "failures remain"
                                " $unexplainedFailureCount $noun unexplained by DNS evidence."
                            } else {
                                ""
                            }
                        "Observed pattern: resolver divergence aligned with $resolverLinkedFailureCount of " +
                            "$affectedTargetFailureCount affected-target failures. This is a candidate signal " +
                            "for resolver interference; cause is not established.$unexplainedSummary"
                    }

                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> {
                        "Diagnostics did not complete a clean in-path confirmation and native " +
                            "runtime state shows an actionable proxy or tunnel failure."
                    }

                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> {
                        "Observed pattern: evidence is mixed or lacks a paired in-path comparison. " +
                            "The cause is not established."
                    }
                }

            private fun assessmentNextAction(code: ConnectivityAssessmentCode): String =
                when (code) {
                    ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE -> {
                        "Run a paired raw-path and in-path reproduction, then inspect underlying " +
                            "network evidence before attributing a cause."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_INTERNATIONAL_FAILURE -> {
                        "Compare the in-path run with the domestic and foreign controls; a working in-path " +
                            "route means the bypass carries traffic the direct international link drops."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING -> {
                        "Run a paired raw-path and in-path reproduction and inspect control and " +
                            "affected-target evidence before attributing a cause."
                    }

                    ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> {
                        "Run a paired raw-path and in-path reproduction, then inspect proxy/tunnel " +
                            "component state and the paired target list."
                    }

                    ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> {
                        "Run a paired raw-path and in-path reproduction with resolver override or " +
                            "encrypted DNS failover, then inspect the mismatched hosts."
                    }

                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> {
                        "Inspect proxy/tunnel runtime errors and rerun the in-path repro once " +
                            "the service is stable."
                    }

                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> {
                        "Run a paired raw-path and in-path reproduction on complaint-specific targets, " +
                            "then inspect the captured evidence."
                    }
                }

            private val ResolverDiagnosisCodes =
                setOf(
                    "dns_tampering",
                    "dns_nxdomain_mismatch",
                    "dns_system_resolution_failed",
                    "dns_record_divergence",
                    "dns_compatible_divergence",
                    "dns_sinkhole_substitution",
                )
            private val TrustedResolverEvidence = setOf("trusted_agreement", "primary_only")
            private val LimitedResolverEvidence = setOf("single_fallback", "disagreement", "unavailable")

            private fun detectInPathRegression(
                rawTargets: Map<ConnectivityTargetKey, TargetOutcome>,
                inPathTargets: Map<ConnectivityTargetKey, TargetOutcome>,
            ): Boolean =
                rawTargets.any { (target, rawOutcome) ->
                    val inPathOutcome = inPathTargets[target] ?: return@any false
                    inPathOutcome.failed &&
                        rawOutcome.components.any { (component, status) ->
                            status == DiagnosticsOutcomeBucket.Healthy &&
                                inPathOutcome.components[component] == DiagnosticsOutcomeBucket.Failed
                        }
                }

            private fun buildResolverAssessment(rawReports: List<ScanReport>): ConnectivityResolverAssessment {
                val dnsResults =
                    rawReports
                        .flatMap(ScanReport::results)
                        .filter { result -> result.probeType == "dns_integrity" }
                val observationMismatchTargets =
                    rawReports
                        .flatMap(ScanReport::observations)
                        .filter { observation ->
                            observation.kind == ObservationKind.DNS &&
                                observation.dns?.status in
                                setOf(
                                    DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                    DnsObservationStatus.SINKHOLE_SUBSTITUTION,
                                    DnsObservationStatus.NXDOMAIN_MISMATCH,
                                )
                        }.map { it.target }
                val systemResolutionFailureTargets =
                    dnsResults
                        .filter { result ->
                            result.outcome == "dns_system_resolution_failed" &&
                                result.detailValue("oracleTrust") in TrustedResolverEvidence
                        }.map(ProbeResult::target)
                val mismatchTargets = (observationMismatchTargets + systemResolutionFailureTargets).distinct()
                val diagnosisCodes =
                    rawReports
                        .flatMap(ScanReport::diagnoses)
                        .map(Diagnosis::code)
                        .filter { it in ResolverDiagnosisCodes }
                        .distinct()
                val strongestSignal =
                    when {
                        diagnosisCodes.isNotEmpty() -> diagnosisCodes.first()
                        mismatchTargets.isNotEmpty() -> "dns_divergence"
                        else -> "none"
                    }
                val resolverSignalSummary =
                    when {
                        mismatchTargets.isNotEmpty() -> {
                            "Encrypted and raw resolver signals diverged for ${mismatchTargets.joinToString(", ")}."
                        }

                        diagnosisCodes.isNotEmpty() -> {
                            "Resolver-related diagnoses were recorded: ${diagnosisCodes.joinToString(", ")}."
                        }

                        else -> {
                            "No resolver interference signal recorded."
                        }
                    }
                val trustedAgreementCount = dnsResults.count { it.detailValue("oracleTrust") == "trusted_agreement" }
                val primaryOnlyCount = dnsResults.count { it.detailValue("oracleTrust") == "primary_only" }
                val limitedOracleCount =
                    dnsResults.count { it.detailValue("oracleTrust") in LimitedResolverEvidence }
                val oracleEvidenceSummary =
                    listOfNotNull(
                        trustedAgreementCount.takeIf { it > 0 }?.let { "$it trusted agreement" },
                        primaryOnlyCount.takeIf { it > 0 }?.let { "$it primary-only" },
                        limitedOracleCount.takeIf { it > 0 }?.let { "$it limited, disagreement, or unavailable" },
                    ).joinToString(", ")
                val summary =
                    if (oracleEvidenceSummary.isEmpty()) {
                        resolverSignalSummary
                    } else {
                        "$resolverSignalSummary Across ${dnsResults.size} DNS checks, " +
                            "oracle evidence: $oracleEvidenceSummary."
                    }
                return ConnectivityResolverAssessment(
                    strongestSignal = strongestSignal,
                    mismatchTargets = mismatchTargets,
                    diagnosisCodes = diagnosisCodes,
                    summary = summary,
                )
            }

            private fun targetObservations(
                reports: List<ScanReport>,
            ): Map<ConnectivityTargetKey, List<ConnectivityTargetObservation>> =
                reports
                    .flatMap { report ->
                        report.observations.mapNotNull { observation ->
                            observation.toTargetOutcome(report)?.let { target ->
                                ConnectivityTargetObservation(report.sessionId, report.profileId, target)
                            }
                        }
                    }.groupBy { it.target.key }

            private fun Map<ConnectivityTargetKey, List<ConnectivityTargetObservation>>.aggregateTargets():
                Map<ConnectivityTargetKey, TargetOutcome> =
                mapValues { (_, observations) ->
                    observations.map(ConnectivityTargetObservation::target).reduce(::merge)
                }

            private fun ObservationFact.toTargetOutcome(report: ScanReport): TargetOutcome? =
                when (kind) {
                    ObservationKind.DOMAIN -> {
                        domain?.let { fact ->
                            val result = report.domainResult(fact.host)
                            val normalized =
                                result?.let {
                                    DiagnosticsOutcomeTaxonomy
                                        .classifyProbeResult(
                                            report.pathMode,
                                            it,
                                            report.results,
                                        ).bucket
                                }
                            val tier =
                                ControlTier.fromWireName(
                                    result?.details?.firstOrNull { it.key == "controlTier" }?.value,
                                )
                            domainTargetOutcome(
                                fact,
                                normalized,
                                result,
                            ).copy(controlTier = tier?.takeIf { fact.isControl })
                        }
                    }

                    ObservationKind.SERVICE, ObservationKind.CIRCUMVENTION -> {
                        endpointTargetOutcome(report)
                    }

                    else -> {
                        null
                    }
                }

            private fun Map<ConnectivityTargetKey, TargetOutcome>.toConnectivityEvidence(
                sessionIds: List<String>,
            ): ConnectivityEvidence {
                val controls = values.filter { it.isControl }.map(TargetOutcome::id).sorted()
                val affected = values.filter { !it.isControl && it.failed }.map(TargetOutcome::id).sorted()
                return ConnectivityEvidence(
                    sessionIds = sessionIds.distinct(),
                    controls = controls,
                    affectedTargets = affected,
                    controlSuccessCount = values.count { it.isControl && it.success },
                    controlFailureCount = values.count { it.isControl && it.failed },
                    affectedTargetSuccessCount = values.count { !it.isControl && it.success },
                    affectedTargetFailureCount = values.count { !it.isControl && it.failed },
                )
            }
        }
    }

private fun ScanReport?.activePathCoverage(sessionId: String?): ConnectivityActivePathCoverage =
    when {
        this?.completionKind == ScanCompletionKind.NORMAL -> ConnectivityActivePathCoverage.COMPLETE
        this != null || sessionId != null -> ConnectivityActivePathCoverage.INCOMPLETE
        else -> ConnectivityActivePathCoverage.NOT_RUN
    }

internal data class TargetOutcome(
    val key: ConnectivityTargetKey,
    val isControl: Boolean = false,
    val bucket: DiagnosticsOutcomeBucket,
    val components: Map<String, DiagnosticsOutcomeBucket>,
    val controlTier: ControlTier? = null,
) {
    val id: String get() = key.id
    val success: Boolean get() = bucket == DiagnosticsOutcomeBucket.Healthy
    val failed: Boolean get() = bucket == DiagnosticsOutcomeBucket.Failed
}

private class ControlTierOutcome(
    private val domesticPassed: Boolean,
    private val foreignPassed: Boolean,
    private val foreignAllFailed: Boolean,
    private val hasTiers: Boolean,
    val cdnOnlyFailure: Boolean,
) {
    val internationalFailure: Boolean get() = domesticPassed && foreignAllFailed

    fun foreignBaselinePassed(anyControlPassed: Boolean): Boolean = if (hasTiers) foreignPassed else anyControlPassed

    companion object {
        fun of(targets: Collection<TargetOutcome>): ControlTierOutcome {
            val controls = targets.filter { it.isControl }
            val domestic = controls.filter { it.controlTier == ControlTier.DOMESTIC }
            val foreign = controls - domestic.toSet()
            val cdn = foreign.filter { it.controlTier == ControlTier.CDN }
            val otherForeign = foreign - cdn.toSet()
            return ControlTierOutcome(
                domesticPassed = domestic.any { it.success },
                foreignPassed = foreign.any { it.success },
                foreignAllFailed = foreign.isNotEmpty() && foreign.all { it.failed },
                hasTiers = controls.any { it.controlTier != null },
                cdnOnlyFailure =
                    cdn.isNotEmpty() && cdn.all { it.failed } && otherForeign.isNotEmpty() &&
                        otherForeign.all { it.success } && domestic.none { it.failed },
            )
        }
    }
}

private fun merge(
    existing: TargetOutcome?,
    next: TargetOutcome,
): TargetOutcome =
    if (existing == null) {
        next
    } else {
        existing.copy(
            controlTier = existing.controlTier ?: next.controlTier,
            bucket = if (existing.bucket == next.bucket) existing.bucket else DiagnosticsOutcomeBucket.Inconclusive,
            components =
                (existing.components.keys + next.components.keys).associateWith { component ->
                    val first = existing.components[component]
                    val second = next.components[component]
                    when {
                        first == null -> requireNotNull(second)
                        second == null || first == second -> first
                        else -> DiagnosticsOutcomeBucket.Inconclusive
                    }
                },
        )
    }

private fun domainTargetOutcome(
    fact: DomainObservationFact,
    normalized: DiagnosticsOutcomeBucket?,
    result: ProbeResult?,
): TargetOutcome {
    val http = fact.httpComparisonBucket(result)
    val components =
        mapOf(
            "http" to http,
            "tls13" to fact.tls13Status.bucket(),
            "tls12" to fact.tls12Status.bucket(),
            "ech" to fact.tlsEchStatus.bucket(),
        ).measuredComponents()
    val certificateFailure =
        fact.certificateAnomaly || fact.tls13Status == TlsProbeStatus.CERT_INVALID ||
            fact.tls12Status == TlsProbeStatus.CERT_INVALID
    val domainBucket =
        normalized ?: when {
            certificateFailure -> DiagnosticsOutcomeBucket.Failed

            DiagnosticsOutcomeBucket.Healthy in components.values -> DiagnosticsOutcomeBucket.Healthy

            DiagnosticsOutcomeBucket.Failed in components.values ||
                fact.transportFailure != TransportFailureKind.NONE -> DiagnosticsOutcomeBucket.Failed

            else -> DiagnosticsOutcomeBucket.Inconclusive
        }
    val bucket =
        when {
            fact.isControl && http == DiagnosticsOutcomeBucket.Healthy && !certificateFailure -> http

            http == DiagnosticsOutcomeBucket.Failed && result?.detailValue("httpStatus") == "http_unreachable" -> http

            domainBucket == DiagnosticsOutcomeBucket.Healthy &&
                (fact.isControl || http == DiagnosticsOutcomeBucket.Failed) -> http

            else -> domainBucket
        }
    return TargetOutcome(ConnectivityTargetKey(ObservationKind.DOMAIN, fact.host), fact.isControl, bucket, components)
}

internal fun ScanReport.domainResult(host: String): ProbeResult? =
    results.firstOrNull { it.probeType == "domain_reachability" && it.target == host }

internal fun DomainObservationFact.httpComparisonBucket(result: ProbeResult?): DiagnosticsOutcomeBucket =
    if (
        httpStatus == HttpProbeStatus.UNREACHABLE &&
        (
            result?.detailValue("httpStatus")?.startsWith("http_status_") == true ||
                result?.detailValue("httpResponse") in setOf("response_too_large", "unsupported_content_encoding")
        )
    ) {
        DiagnosticsOutcomeBucket.Attention
    } else {
        httpStatus.bucket()
    }

private fun ObservationFact.endpointTargetOutcome(report: ScanReport): TargetOutcome? {
    val isService = kind == ObservationKind.SERVICE
    val probeType = if (isService) "service_reachability" else "circumvention_reachability"
    val recordedOutcome =
        evidence.singleOrNull()?.takeIf {
            it == "inconclusive" ||
                it.startsWith(if (isService) "service_" else "circumvention_")
        }
    val result =
        report.results.singleOrNull {
            it.probeType == probeType && it.target == target &&
                (recordedOutcome == null || it.outcome == recordedOutcome)
        }
    val nativeBucket =
        (recordedOutcome ?: result?.outcome)?.let {
            DiagnosticsOutcomeTaxonomy.classifyProbeOutcome(probeType, report.pathMode, it).bucket
        }
    val components =
        if (isService) {
            service?.componentBuckets(result, nativeBucket)
        } else {
            circumvention?.let {
                mapOf(
                    "handshake" to it.handshakeStatus.bucket(),
                    "bootstrap" to
                        it.bootstrapStatus.endpointBucket(result?.detailValue("bootstrapStatus"), nativeBucket),
                )
            }
        }?.measuredComponents() ?: return null
    val bucket =
        nativeBucket ?: when {
            DiagnosticsOutcomeBucket.Failed in components.values -> DiagnosticsOutcomeBucket.Failed
            DiagnosticsOutcomeBucket.Attention in components.values -> DiagnosticsOutcomeBucket.Attention
            DiagnosticsOutcomeBucket.Healthy in components.values -> DiagnosticsOutcomeBucket.Healthy
            else -> DiagnosticsOutcomeBucket.Inconclusive
        }
    return TargetOutcome(ConnectivityTargetKey(kind, target), bucket = bucket, components = components)
}

private fun ServiceObservationFact.componentBuckets(
    result: ProbeResult?,
    nativeBucket: DiagnosticsOutcomeBucket?,
): Map<String, DiagnosticsOutcomeBucket> =
    mapOf(
        "endpoint" to endpointStatus.bucket(),
        "bootstrap" to bootstrapStatus.endpointBucket(result?.detailValue("bootstrapStatus"), nativeBucket),
        "media" to mediaStatus.endpointBucket(result?.detailValue("mediaStatus"), nativeBucket),
        "quic" to
            when (quicStatus) {
                QuicProbeStatus.INITIAL_RESPONSE -> DiagnosticsOutcomeBucket.Healthy

                QuicProbeStatus.EMPTY, QuicProbeStatus.ERROR -> DiagnosticsOutcomeBucket.Failed

                QuicProbeStatus.RESPONSE,
                QuicProbeStatus.INITIAL_ACK,
                QuicProbeStatus.INITIAL_OTHER,
                QuicProbeStatus.INITIAL_CLOSE,
                QuicProbeStatus.RETRY,
                QuicProbeStatus.VERSION_NEGOTIATION,
                QuicProbeStatus.INVALID_RESPONSE,
                QuicProbeStatus.TIMEOUT,
                -> DiagnosticsOutcomeBucket.Attention

                QuicProbeStatus.NOT_RUN,
                QuicProbeStatus.SETUP_ERROR,
                QuicProbeStatus.CANCELLED,
                QuicProbeStatus.DEADLINE_EXCEEDED,
                QuicProbeStatus.UNKNOWN,
                -> DiagnosticsOutcomeBucket.Inconclusive
            },
    )

private fun HttpProbeStatus.endpointBucket(
    status: String?,
    nativeBucket: DiagnosticsOutcomeBucket?,
): DiagnosticsOutcomeBucket {
    val code = status?.removePrefix("http_status_")?.toIntOrNull()
    val missingStatusInNonHealthyResult =
        status == null && nativeBucket != null && nativeBucket != DiagnosticsOutcomeBucket.Healthy
    return when {
        code != null && code in HttpAttentionStatusMin..HttpAttentionStatusMax -> DiagnosticsOutcomeBucket.Attention
        missingStatusInNonHealthyResult && this == HttpProbeStatus.OK -> DiagnosticsOutcomeBucket.Inconclusive
        else -> bucket()
    }
}

private fun Map<String, DiagnosticsOutcomeBucket>.measuredComponents(): Map<String, DiagnosticsOutcomeBucket> =
    filterValues { it != DiagnosticsOutcomeBucket.Inconclusive }

private fun HttpProbeStatus.bucket(): DiagnosticsOutcomeBucket =
    when (this) {
        HttpProbeStatus.OK -> DiagnosticsOutcomeBucket.Healthy
        HttpProbeStatus.BLOCKPAGE, HttpProbeStatus.UNREACHABLE -> DiagnosticsOutcomeBucket.Failed
        HttpProbeStatus.NOT_RUN -> DiagnosticsOutcomeBucket.Inconclusive
    }

private fun TlsProbeStatus.bucket(): DiagnosticsOutcomeBucket =
    when (this) {
        TlsProbeStatus.OK -> DiagnosticsOutcomeBucket.Healthy

        TlsProbeStatus.HANDSHAKE_FAILED, TlsProbeStatus.CERT_INVALID -> DiagnosticsOutcomeBucket.Failed

        TlsProbeStatus.VERSION_SPLIT,
        TlsProbeStatus.INTERRUPTED,
        TlsProbeStatus.NOT_RUN,
        -> DiagnosticsOutcomeBucket.Inconclusive
    }

private fun EndpointProbeStatus.bucket(): DiagnosticsOutcomeBucket =
    when (this) {
        EndpointProbeStatus.OK -> DiagnosticsOutcomeBucket.Healthy
        EndpointProbeStatus.BLOCKED, EndpointProbeStatus.FAILED -> DiagnosticsOutcomeBucket.Failed
        EndpointProbeStatus.NOT_RUN -> DiagnosticsOutcomeBucket.Inconclusive
    }

private const val HttpAttentionStatusMin = 300
private const val HttpAttentionStatusMax = 599
