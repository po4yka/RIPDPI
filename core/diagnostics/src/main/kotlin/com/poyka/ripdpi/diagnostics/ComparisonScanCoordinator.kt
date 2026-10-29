package com.poyka.ripdpi.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComparisonScanCoordinator
    @Inject
    constructor() {
        fun assessConnectivity(
            rawReports: List<ScanReport>,
            inPathReport: ScanReport?,
            rawPathSessionIds: List<String>,
            inPathSessionId: String?,
            serviceRuntimeAssessment: ConnectivityServiceRuntimeAssessment = ConnectivityServiceRuntimeAssessment(),
        ): ConnectivityAssessment {
            val rawTargets = aggregateTargets(rawReports)
            val inPathTargets = aggregateTargets(listOfNotNull(inPathReport))
            val rawEvidence = rawTargets.toConnectivityEvidence(rawPathSessionIds)
            val inPathEvidence =
                inPathTargets
                    .filterKeys { it in rawTargets.keys }
                    .toConnectivityEvidence(listOfNotNull(inPathSessionId))
            val resolverAssessment = buildResolverAssessment(rawReports)
            val rawControlSuccess = rawEvidence.controlSuccessCount > 0
            val rawControlFailure = rawEvidence.controlFailureCount > 0
            val rawAffectedFailure = rawEvidence.affectedTargetFailureCount > 0
            val resolverMismatchAuthorities =
                resolverAssessment.mismatchTargets.map(String::normalizedProbeAuthority).toSet()
            val resolverLinkedFailureCount =
                rawEvidence.affectedTargets.count { affectedTarget ->
                    affectedTarget.normalizedProbeAuthority() in resolverMismatchAuthorities
                }
            val inPathRegression = detectInPathRegression(rawTargets, inPathTargets)
            val code =
                deriveAssessmentCode(
                    rawControlSuccess = rawControlSuccess,
                    rawControlFailure = rawControlFailure,
                    rawAffectedFailure = rawAffectedFailure,
                    resolverLinkedFailure = resolverLinkedFailureCount > 0,
                    inPathRegression = inPathRegression,
                    serviceRuntimeAssessment = serviceRuntimeAssessment,
                    inPathReport = inPathReport,
                    inPathEvidence = inPathEvidence,
                )
            val confidence =
                when (code) {
                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> "low"

                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE,
                    ConnectivityAssessmentCode.RESOLVER_INTERFERENCE,
                    -> "medium"

                    else -> "high"
                }
            val controlOutcome =
                when {
                    rawEvidence.controls.isEmpty() -> "no_controls"
                    rawControlSuccess && !rawControlFailure -> "raw_controls_passed"
                    rawControlSuccess -> "raw_controls_mixed"
                    else -> "raw_controls_failed"
                }
            return ConnectivityAssessment(
                assessmentCode = code,
                assessmentSummary =
                    assessmentSummary(
                        code = code,
                        resolverLinkedFailureCount = resolverLinkedFailureCount,
                        affectedTargetFailureCount = rawEvidence.affectedTargetFailureCount,
                    ),
                confidence = confidence,
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

                    inPathRegression -> {
                        ConnectivityAssessmentCode.VPN_PATH_REGRESSION
                    }

                    resolverLinkedFailure && rawAffectedFailure -> {
                        ConnectivityAssessmentCode.RESOLVER_INTERFERENCE
                    }

                    rawControlSuccess && rawAffectedFailure -> {
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
                rawTargets: Map<String, TargetOutcome>,
                inPathTargets: Map<String, TargetOutcome>,
            ): Boolean =
                rawTargets.any { (target, rawOutcome) ->
                    val inPathOutcome = inPathTargets[target] ?: return@any false
                    rawOutcome.success && !inPathOutcome.success
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

            private fun aggregateTargets(reports: List<ScanReport>): Map<String, TargetOutcome> =
                buildMap {
                    reports.flatMap(ScanReport::observations).forEach { observation ->
                        val outcome = observation.toTargetOutcome() ?: return@forEach
                        val existing = get(outcome.id)
                        put(outcome.id, merge(existing, outcome))
                    }
                }

            private fun ObservationFact.toTargetOutcome(): TargetOutcome? =
                when (kind) {
                    ObservationKind.DOMAIN -> domain?.let { domainTargetOutcome(it) }
                    ObservationKind.SERVICE -> service?.let { serviceTargetOutcome(target, it) }
                    ObservationKind.CIRCUMVENTION -> circumvention?.let { circumventionTargetOutcome(target, it) }
                    else -> null
                }

            private fun Map<String, TargetOutcome>.toConnectivityEvidence(
                sessionIds: List<String>,
            ): ConnectivityEvidence {
                val controls = values.filter { it.isControl }.map(TargetOutcome::id).sorted()
                val affected = values.filter { !it.isControl && !it.success }.map(TargetOutcome::id).sorted()
                return ConnectivityEvidence(
                    sessionIds = sessionIds.distinct(),
                    controls = controls,
                    affectedTargets = affected,
                    controlSuccessCount = values.count { it.isControl && it.success },
                    controlFailureCount = values.count { it.isControl && !it.success },
                    affectedTargetSuccessCount = values.count { !it.isControl && it.success },
                    affectedTargetFailureCount = values.count { !it.isControl && !it.success },
                )
            }
        }
    }

private data class TargetOutcome(
    val id: String,
    val isControl: Boolean = false,
    val success: Boolean,
)

private fun merge(
    existing: TargetOutcome?,
    next: TargetOutcome,
): TargetOutcome =
    when {
        existing == null -> next
        existing.success -> existing
        next.success -> next
        else -> existing
    }

private fun domainTargetOutcome(fact: DomainObservationFact): TargetOutcome {
    val success =
        fact.httpStatus == HttpProbeStatus.OK ||
            fact.tls13Status == TlsProbeStatus.OK ||
            fact.tls12Status == TlsProbeStatus.OK ||
            fact.tls13Status == TlsProbeStatus.VERSION_SPLIT ||
            fact.tls12Status == TlsProbeStatus.VERSION_SPLIT
    val failure =
        fact.transportFailure != TransportFailureKind.NONE ||
            fact.httpStatus == HttpProbeStatus.UNREACHABLE ||
            fact.tls13Status == TlsProbeStatus.HANDSHAKE_FAILED ||
            fact.tls12Status == TlsProbeStatus.HANDSHAKE_FAILED ||
            fact.tls13Status == TlsProbeStatus.CERT_INVALID ||
            fact.tls12Status == TlsProbeStatus.CERT_INVALID
    return TargetOutcome(
        id = fact.host,
        isControl = fact.isControl,
        success = success && !failure,
    )
}

private fun serviceTargetOutcome(
    target: String,
    fact: ServiceObservationFact,
): TargetOutcome =
    TargetOutcome(
        id = target,
        success =
            fact.endpointStatus == EndpointProbeStatus.OK ||
                fact.bootstrapStatus == HttpProbeStatus.OK ||
                fact.mediaStatus == HttpProbeStatus.OK,
    )

private fun circumventionTargetOutcome(
    target: String,
    fact: CircumventionObservationFact,
): TargetOutcome =
    TargetOutcome(
        id = target,
        success =
            fact.handshakeStatus == EndpointProbeStatus.OK ||
                fact.bootstrapStatus == HttpProbeStatus.OK,
    )
