package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ComparisonScanCoordinator
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun compareReports(
            rawPathSessionId: String,
            inPathSessionId: String,
        ): PathComparisonResult {
            val rawReport =
                scanRecordStore.getScanSession(rawPathSessionId)?.let {
                    DiagnosticsSessionQueries.decodeScanReport(json, it.reportJson)
                }
            val inPathReport =
                scanRecordStore
                    .getScanSession(
                        inPathSessionId,
                    )?.let { DiagnosticsSessionQueries.decodeScanReport(json, it.reportJson) }
            if (rawReport == null || inPathReport == null) {
                return PathComparisonResult(
                    rawPathSessionId = rawPathSessionId,
                    inPathSessionId = inPathSessionId,
                    summary = "Comparison unavailable: missing report data",
                )
            }

            val assessment =
                assessConnectivity(
                    rawReports = listOf(rawReport),
                    inPathReport = inPathReport,
                    rawPathSessionIds = listOf(rawPathSessionId),
                    inPathSessionId = inPathSessionId,
                )
            val rawOutcomes = aggregateTargets(listOf(rawReport))
            val inPathOutcomes = aggregateTargets(listOf(inPathReport))
            val domainComparisons =
                (rawOutcomes.keys + inPathOutcomes.keys)
                    .sorted()
                    .mapNotNull { target ->
                        val raw = rawOutcomes[target] ?: return@mapNotNull null
                        if (raw.kind != TargetKind.DOMAIN) return@mapNotNull null
                        val inPath = inPathOutcomes[target]
                        DomainPathComparison(
                            domain = target,
                            rawPathOutcome = raw.outcome,
                            inPathOutcome = inPath?.outcome ?: NotTested,
                            vpnBypasses = !raw.success && (inPath?.success == true),
                            isControl = raw.isControl,
                        )
                    }

            return PathComparisonResult(
                rawPathSessionId = rawPathSessionId,
                inPathSessionId = inPathSessionId,
                domainComparisons = domainComparisons,
                summary = assessment.assessmentSummary,
                assessment = assessment,
            )
        }

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
            val inPathRegression = detectInPathRegression(rawTargets, inPathTargets)
            val code =
                deriveAssessmentCode(
                    rawControlSuccess = rawControlSuccess,
                    rawControlFailure = rawControlFailure,
                    rawAffectedFailure = rawAffectedFailure,
                    inPathRegression = inPathRegression,
                    resolverAssessment = resolverAssessment,
                    serviceRuntimeAssessment = serviceRuntimeAssessment,
                    inPathReport = inPathReport,
                    inPathEvidence = inPathEvidence,
                )
            val confidence =
                when (code) {
                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> "low"
                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> "medium"
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
                assessmentSummary = assessmentSummary(code),
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
                inPathRegression: Boolean,
                resolverAssessment: ConnectivityResolverAssessment,
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

                    resolverAssessment.mismatchTargets.isNotEmpty() && rawAffectedFailure -> {
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

            private fun assessmentSummary(code: ConnectivityAssessmentCode): String =
                when (code) {
                    ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE -> {
                        "Raw-path control targets failed alongside affected targets, so the " +
                            "network looks broadly broken before RIPDPI enters the path."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING -> {
                        "Raw-path controls passed while affected targets still failed, which " +
                            "points to selective blocking on the direct network path."
                    }

                    ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> {
                        "The paired in-path run performed worse than raw path on the same " +
                            "targets, which points to a RIPDPI in-path regression."
                    }

                    ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> {
                        "Resolver divergence was the strongest common signal across failed " +
                            "targets, so DNS interference is the likely cause."
                    }

                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> {
                        "Diagnostics did not complete a clean in-path confirmation and native " +
                            "runtime state shows an actionable proxy or tunnel failure."
                    }

                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> {
                        "The evidence is mixed, so the archive cannot yet isolate whether the " +
                            "failure is raw-network, in-path, or both."
                    }
                }

            private fun assessmentNextAction(code: ConnectivityAssessmentCode): String =
                when (code) {
                    ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE -> {
                        "Verify the underlying network without RIPDPI before retrying diagnostics."
                    }

                    ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING -> {
                        "Treat this as a direct-network censorship/blocking issue and compare " +
                            "with a dedicated in-path repro only if the user reports VPN-only breakage."
                    }

                    ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> {
                        "Reproduce with RIPDPI enabled and inspect proxy/tunnel component state " +
                            "plus the paired target list."
                    }

                    ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> {
                        "Retry with resolver override or encrypted DNS failover enabled and " +
                            "inspect the mismatched hosts."
                    }

                    ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> {
                        "Inspect proxy/tunnel runtime errors and rerun the in-path repro once " +
                            "the service is stable."
                    }

                    ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> {
                        "Run the targeted internet-loss repro to capture a paired raw-path and " +
                            "in-path comparison on complaint-specific targets."
                    }
                }

            private val ResolverDiagnosisCodes =
                setOf(
                    "dns_tampering",
                    "dns_nxdomain_mismatch",
                    "dns_record_divergence",
                    "dns_compatible_divergence",
                    "dns_sinkhole_substitution",
                )

            private fun detectInPathRegression(
                rawTargets: Map<String, TargetOutcome>,
                inPathTargets: Map<String, TargetOutcome>,
            ): Boolean =
                rawTargets.any { (target, rawOutcome) ->
                    val inPathOutcome = inPathTargets[target] ?: return@any false
                    rawOutcome.success && !inPathOutcome.success
                }

            private fun buildResolverAssessment(rawReports: List<ScanReport>): ConnectivityResolverAssessment {
                val mismatchTargets =
                    rawReports
                        .flatMap(ScanReport::observations)
                        .filter { observation ->
                            observation.kind == ObservationKind.DNS &&
                                observation.dns?.status in
                                setOf(
                                    DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
                                    DnsObservationStatus.SINKHOLE_SUBSTITUTION,
                                    DnsObservationStatus.NXDOMAIN_MISMATCH,
                                    DnsObservationStatus.COMPATIBLE_DIVERGENCE,
                                )
                        }.map { it.target }
                        .distinct()
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
                val summary =
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

private const val NotTested = "not_tested"

private enum class TargetKind {
    DOMAIN,
    SERVICE,
    CIRCUMVENTION,
}

private data class TargetOutcome(
    val id: String,
    val kind: TargetKind,
    val isControl: Boolean = false,
    val success: Boolean,
    val outcome: String,
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
        kind = TargetKind.DOMAIN,
        isControl = fact.isControl,
        success = success && !failure,
        outcome = domainOutcomeLabel(fact),
    )
}

private fun serviceTargetOutcome(
    target: String,
    fact: ServiceObservationFact,
): TargetOutcome =
    TargetOutcome(
        id = target,
        kind = TargetKind.SERVICE,
        success =
            fact.endpointStatus == EndpointProbeStatus.OK ||
                fact.bootstrapStatus == HttpProbeStatus.OK ||
                fact.mediaStatus == HttpProbeStatus.OK,
        outcome = serviceOutcomeLabel(fact),
    )

private fun circumventionTargetOutcome(
    target: String,
    fact: CircumventionObservationFact,
): TargetOutcome =
    TargetOutcome(
        id = target,
        kind = TargetKind.CIRCUMVENTION,
        success =
            fact.handshakeStatus == EndpointProbeStatus.OK ||
                fact.bootstrapStatus == HttpProbeStatus.OK,
        outcome = circumventionOutcomeLabel(fact),
    )

private fun domainOutcomeLabel(fact: DomainObservationFact): String =
    when {
        fact.httpStatus == HttpProbeStatus.OK -> {
            "http_ok"
        }

        fact.httpStatus == HttpProbeStatus.BLOCKPAGE -> {
            "http_blockpage"
        }

        fact.httpStatus == HttpProbeStatus.UNREACHABLE -> {
            "http_unreachable"
        }

        fact.tls13Status == TlsProbeStatus.OK || fact.tls12Status == TlsProbeStatus.OK -> {
            "tls_ok"
        }

        fact.tls13Status == TlsProbeStatus.VERSION_SPLIT ||
            fact.tls12Status == TlsProbeStatus.VERSION_SPLIT -> {
            "tls_version_split"
        }

        fact.tls13Status == TlsProbeStatus.CERT_INVALID ||
            fact.tls12Status == TlsProbeStatus.CERT_INVALID -> {
            "tls_cert_invalid"
        }

        fact.tls13Status == TlsProbeStatus.HANDSHAKE_FAILED ||
            fact.tls12Status == TlsProbeStatus.HANDSHAKE_FAILED -> {
            "tls_handshake_failed"
        }

        fact.transportFailure != TransportFailureKind.NONE -> {
            "transport_${fact.transportFailure.name.lowercase()}"
        }

        else -> {
            NotTested
        }
    }

private fun serviceOutcomeLabel(fact: ServiceObservationFact): String =
    when {
        fact.endpointStatus == EndpointProbeStatus.OK -> "service_ok"
        fact.endpointStatus == EndpointProbeStatus.BLOCKED -> "service_blocked"
        fact.endpointStatus == EndpointProbeStatus.FAILED -> "service_failed"
        fact.bootstrapStatus == HttpProbeStatus.UNREACHABLE -> "bootstrap_unreachable"
        fact.quicStatus == QuicProbeStatus.ERROR -> "quic_error"
        else -> NotTested
    }

private fun circumventionOutcomeLabel(fact: CircumventionObservationFact): String =
    when {
        fact.handshakeStatus == EndpointProbeStatus.OK -> "circumvention_ok"
        fact.handshakeStatus == EndpointProbeStatus.BLOCKED -> "circumvention_blocked"
        fact.handshakeStatus == EndpointProbeStatus.FAILED -> "circumvention_failed"
        fact.bootstrapStatus == HttpProbeStatus.UNREACHABLE -> "bootstrap_unreachable"
        else -> NotTested
    }
