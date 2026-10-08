@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val ThrottlingRatioThreshold = 0.25
private const val ControlFloorBps = 5_000_000L
private const val DnsInjectionThresholdMs = 5
private const val DnsLatencySlowThresholdMs = 3000
private const val DnsEncryptedLatencyMultiplier = 10

@Singleton
class DiagnosticsFindingProjector
    @Inject
    constructor() {
        companion object {
            const val ClassifierVersion = "ru_ooni_kotlin_v2"
        }

        fun classify(report: ScanReport): List<Diagnosis> = classify(report.observations)

        fun classify(observations: List<ObservationFact>): List<Diagnosis> {
            val diagnoses = mutableListOf<Diagnosis>()
            val seen = linkedSetOf<String>()

            val dns = observations.mapNotNull(ObservationFact::dns)
            val domains = observations.mapNotNull(ObservationFact::domain)
            val tcp = observations.mapNotNull(ObservationFact::tcp)
            val quic = observations.mapNotNull(ObservationFact::quic)
            val services = observations.mapNotNull(ObservationFact::service)
            val circumventions = observations.mapNotNull(ObservationFact::circumvention)
            val throughput = observations.mapNotNull(ObservationFact::throughput)
            val strategyFacts = observations.filter { it.kind == ObservationKind.STRATEGY && it.strategy != null }

            val controls = diagnosisControls(domains, throughput)

            collectDnsDiagnoses(dns, domains, diagnoses, seen)
            collectDomainDiagnoses(domains, diagnoses, seen)
            collectTcpDiagnoses(tcp, diagnoses, seen)
            collectQuicDiagnoses(quic, diagnoses, seen)
            collectServiceDiagnoses(services, diagnoses, seen)
            collectCircumventionDiagnoses(circumventions, diagnoses, seen)
            collectThroughputDiagnoses(throughput, diagnoses, seen)
            collectStrategyDiagnoses(strategyFacts, diagnoses, seen)

            if (controls.tls == false || controls.http == false) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "network_connectivity_issue",
                        summary =
                            "One or more executed control probes failed; the cause is not determined",
                    ),
                )
            }

            return diagnoses.map { diagnosis ->
                diagnosis.copy(controlValidated = controls.forDiagnosis(diagnosis.code))
            }
        }
    }

private fun collectDnsDiagnoses(
    dns: List<DnsObservationFact>,
    domains: List<DomainObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    collectDnsTamperingDiagnoses(dns, diagnoses, seen)
    collectDnsBlockpageDiagnoses(dns, domains, diagnoses, seen)
    collectDnsLatencyDiagnoses(dns, diagnoses, seen)
}

private fun collectDnsTamperingDiagnoses(
    dns: List<DnsObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    dns
        .filter {
            it.status == DnsObservationStatus.SINKHOLE_SUBSTITUTION ||
                it.status == DnsObservationStatus.EXPECTED_MISMATCH ||
                it.status == DnsObservationStatus.NXDOMAIN_MISMATCH
        }.forEach { observation ->
            val udpMs = observation.udpLatencyMs
            val encMs = observation.encryptedLatencyMs
            val isTampered =
                observation.status == DnsObservationStatus.SINKHOLE_SUBSTITUTION ||
                    observation.status == DnsObservationStatus.NXDOMAIN_MISMATCH
            val injectionSuspected = isTampered && udpMs != null && udpMs <= DnsInjectionThresholdMs
            val (observationKind, summary) =
                when {
                    observation.status == DnsObservationStatus.NXDOMAIN_MISMATCH -> {
                        "nxdomain_difference" to
                            "UDP DNS returned NXDOMAIN while encrypted DNS returned addresses"
                    }

                    injectionSuspected -> {
                        "fast_answer_difference" to "UDP DNS answers differed from encrypted DNS within 5ms"
                    }

                    else -> {
                        "answer_difference" to "DNS answers differed from the expected or encrypted DNS answers"
                    }
                }
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_tampering",
                    summary = summary,
                    target = observation.domain,
                    evidence =
                        buildList {
                            addAll(observation.udpAddresses)
                            addAll(observation.encryptedAddresses)
                            if (udpMs != null) add("udpLatencyMs=$udpMs")
                            if (encMs != null) add("encryptedLatencyMs=$encMs")
                            add("observation=$observationKind")
                        },
                ),
            )
        }
}

private fun collectDnsBlockpageDiagnoses(
    dns: List<DnsObservationFact>,
    domains: List<DomainObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    dns
        .filter { it.status == DnsObservationStatus.SINKHOLE_SUBSTITUTION }
        .forEach { observation ->
            val matchesBlockpage =
                domains.any { domain ->
                    normalizeTarget(domain.host) == normalizeTarget(observation.domain) &&
                        domain.httpStatus == HttpProbeStatus.BLOCKPAGE
                }
            if (matchesBlockpage) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "dns_blockpage_fingerprint",
                        summary = "DNS substitution matches a blockpage response",
                        target = observation.domain,
                        evidence = observation.udpAddresses + observation.encryptedAddresses,
                    ),
                )
            }
        }
}

private fun collectDnsLatencyDiagnoses(
    dns: List<DnsObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    dns.forEach { observation ->
        val udpMs = observation.udpLatencyMs ?: return@forEach
        val encMs = observation.encryptedLatencyMs ?: return@forEach
        val isTampered =
            observation.status == DnsObservationStatus.SINKHOLE_SUBSTITUTION ||
                observation.status == DnsObservationStatus.NXDOMAIN_MISMATCH
        if (udpMs <= DnsInjectionThresholdMs && isTampered) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_injection_suspected",
                    summary =
                        "UDP DNS answers differed from encrypted DNS within 5ms",
                    severity = "negative",
                    target = observation.domain,
                    evidence =
                        buildList {
                            add("udpLatencyMs=$udpMs")
                            add("encryptedLatencyMs=$encMs")
                            addAll(observation.udpAddresses)
                            addAll(observation.encryptedAddresses)
                        },
                    recommendation =
                        "Compare UDP and encrypted DNS results; response timing alone does not identify the cause.",
                ),
            )
        }
        if (udpMs > DnsLatencySlowThresholdMs || (encMs > 0 && udpMs > encMs * DnsEncryptedLatencyMultiplier)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_latency_anomaly",
                    summary = "UDP DNS latency exceeded the comparison threshold",
                    severity = "degraded",
                    target = observation.domain,
                    evidence =
                        buildList {
                            add("udpLatencyMs=$udpMs")
                            add("encryptedLatencyMs=$encMs")
                            if (encMs > 0) add("slowdownRatio=${udpMs / encMs}x")
                        },
                    recommendation =
                        "Repeat the comparison with UDP and encrypted DNS; latency alone does not identify the cause.",
                ),
            )
        }
    }
}

private fun collectDomainDiagnoses(
    domains: List<DomainObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    domains.forEach { obs ->
        val (code, summary) =
            when (obs.transportFailure) {
                TransportFailureKind.TIMEOUT -> "tls_clienthello_timeout" to "TLS handshake timed out"
                TransportFailureKind.RESET -> "tls_clienthello_rst" to "TLS handshake was reset"
                TransportFailureKind.CLOSE -> "tls_clienthello_close" to "TLS handshake was closed"
                TransportFailureKind.CERTIFICATE -> "tls_cert_mitm" to "TLS certificate validation failed"
                else -> null to null
            }
        if (code != null && summary != null) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(code = code, summary = summary, target = obs.host, evidence = listOf(obs.host)),
            )
        }
        if (obs.certificateAnomaly || obs.tls13Status == TlsProbeStatus.CERT_INVALID) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "tls_cert_mitm",
                    summary = "TLS certificate validation failed",
                    target = obs.host,
                    evidence = listOf(obs.host),
                ),
            )
        }
        if (obs.httpStatus == HttpProbeStatus.BLOCKPAGE) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "http_blockpage",
                    summary = "HTTP response matched a blockpage",
                    target = obs.host,
                    evidence = listOf(obs.host),
                ),
            )
        }
        val plainTlsStatuses = listOf(obs.tls13Status, obs.tls12Status)
        val plainTlsFailed =
            plainTlsStatuses.none { it == TlsProbeStatus.OK } &&
                plainTlsStatuses.any { it != TlsProbeStatus.NOT_RUN }
        if (obs.tlsEchStatus == TlsProbeStatus.OK && plainTlsFailed) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "tls_ech_only",
                    summary = "Plain TLS attempts failed, but ECH succeeded",
                    target = obs.host,
                    evidence = listOfNotNull(obs.host, obs.tlsEchVersion, obs.tlsEchError),
                ),
            )
        }
    }
}

private fun collectTcpDiagnoses(
    tcp: List<TcpObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    tcp.forEach { observation ->
        val diagnosis =
            when (observation.status) {
                TcpProbeStatus.BLOCKED_16KB -> {
                    Diagnosis(
                        code = "tcp_16kb_cutoff",
                        summary = "TCP flow failed around the 16 KiB threshold",
                        target = observation.provider,
                        evidence = listOfNotNull(observation.bytesSent?.toString()),
                    )
                }

                TcpProbeStatus.WHITELIST_SNI_OK -> {
                    Diagnosis(
                        code = "whitelist_sni_bypassable",
                        summary = "A whitelisted SNI restored TCP reachability",
                        target = observation.provider,
                        evidence = listOfNotNull(observation.selectedSni),
                    )
                }

                else -> {
                    null
                }
            }
        diagnosis?.let { pushDiagnosis(diagnoses, seen, it) }
    }
}

private fun collectQuicDiagnoses(
    quic: List<QuicObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    quic.filter { it.status in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY) }.forEach { obs ->
        pushDiagnosis(
            diagnoses,
            seen,
            Diagnosis(
                code = "quic_blocked",
                summary = "QUIC Initial probe did not receive a valid response",
                target = obs.host,
                evidence = listOf(obs.host),
            ),
        )
    }
}

private fun collectServiceDiagnoses(
    services: List<ServiceObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    services.forEach { observation ->
        addServiceHttpDiagnosis(
            observation.service,
            observation.bootstrapStatus,
            "service_bootstrap_blocked",
            "${observation.service} bootstrap request failed",
            diagnoses,
            seen,
        )
        addServiceHttpDiagnosis(
            observation.service,
            observation.mediaStatus,
            "service_media_blocked",
            "${observation.service} media request failed",
            diagnoses,
            seen,
        )
        if (observation.quicStatus in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "quic_blocked",
                    summary = "QUIC Initial probe did not receive a valid response",
                    target = observation.service,
                    evidence = listOf(observation.service),
                ),
            )
        }
    }
}

private fun collectCircumventionDiagnoses(
    circumventions: List<CircumventionObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    circumventions.forEach { observation ->
        addServiceHttpDiagnosis(
            observation.tool,
            observation.bootstrapStatus,
            "circumvention_bootstrap_blocked",
            "${observation.tool} bootstrap request failed",
            diagnoses,
            seen,
        )
        if (observation.handshakeStatus != EndpointProbeStatus.OK &&
            observation.handshakeStatus != EndpointProbeStatus.NOT_RUN
        ) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "circumvention_handshake_blocked",
                    summary = "${observation.tool} handshake failed",
                    target = observation.tool,
                    evidence = listOf(observation.tool),
                ),
            )
        }
    }
}

private fun addServiceHttpDiagnosis(
    target: String,
    status: HttpProbeStatus,
    code: String,
    summary: String,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    if (status !in setOf(HttpProbeStatus.OK, HttpProbeStatus.NOT_RUN)) {
        pushDiagnosis(
            diagnoses,
            seen,
            Diagnosis(code = code, summary = summary, target = target, evidence = listOf(target)),
        )
    }
}

private fun collectThroughputDiagnoses(
    throughput: List<ThroughputObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    val controlMedian =
        throughput
            .filter { it.isControl && it.status == ThroughputProbeStatus.MEASURED && it.medianBps > 0 }
            .map(ThroughputObservationFact::medianBps)
            .maxOrNull()
    val youtube =
        throughput
            .filterNot(ThroughputObservationFact::isControl)
            .firstOrNull { normalizeTarget(it.label).contains("youtube") }

    if (youtube != null) {
        val isSeverelyThrottled =
            youtube.status == ThroughputProbeStatus.MEASURED &&
                controlMedian != null &&
                controlMedian >= ControlFloorBps &&
                youtube.medianBps < (controlMedian * ThrottlingRatioThreshold).toLong()
        if (
            !hasHardYoutubeFailure(diagnoses) &&
            isSeverelyThrottled
        ) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "youtube_throttled",
                    summary = "Measured YouTube throughput was much lower than the measured control",
                    target = youtube.label,
                    evidence = listOf(youtube.medianBps.toString(), controlMedian.toString()),
                ),
            )
        }
    }

    if (controlMedian != null && controlMedian >= ControlFloorBps) {
        val targetThroughputs = throughput.filter { !it.isControl }
        for (target in targetThroughputs) {
            if (target.status != ThroughputProbeStatus.MEASURED) continue
            val targetBps = target.medianBps
            if (targetBps.toDouble() / controlMedian < ThrottlingRatioThreshold) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "throttling_suspected",
                        summary =
                            "Measured target throughput was much lower than the measured control",
                        target = target.label,
                        evidence =
                            listOf(
                                "targetBps=$targetBps",
                                "controlBps=$controlMedian",
                                "ratio=${String.format(
                                    Locale.ROOT,
                                    "%.2f",
                                    targetBps.toDouble() / controlMedian,
                                )}",
                            ),
                    ),
                )
            }
        }
    }
}

private fun hasHardYoutubeFailure(diagnoses: List<Diagnosis>): Boolean =
    diagnoses.any { diagnosis ->
        diagnosis.code in
            setOf(
                "dns_tampering",
                "http_blockpage",
                "tls_clienthello_timeout",
                "tls_clienthello_rst",
                "tls_clienthello_close",
            ) && diagnosis.target?.let(::normalizeTarget)?.contains("youtube") == true
    }

private fun pushDiagnosis(
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
    diagnosis: Diagnosis,
) {
    val key = "${diagnosis.code}:${diagnosis.target.orEmpty()}"
    if (seen.add(key)) {
        diagnoses += diagnosis
    }
}

private fun collectStrategyDiagnoses(
    strategyFacts: List<ObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    addStrategyExhaustionDiagnosis(strategyFacts, diagnoses, seen)
    addPerDomainStrategyFailureDiagnosis(strategyFacts, diagnoses, seen)
    addProtocolTotalFailureDiagnosis(
        strategyFacts,
        diagnoses,
        seen,
        StrategyProbeProtocol.QUIC,
        code = "quic_total_failure",
        summary = "All QUIC probes failed for the tested targets",
    )
    addProtocolTotalFailureDiagnosis(
        strategyFacts,
        diagnoses,
        seen,
        StrategyProbeProtocol.HTTP,
        code = "http_network_blocked",
        summary = "All HTTP probes failed for the tested targets",
    )
    addH3SelectiveBlockingDiagnosis(strategyFacts, diagnoses, seen)
}

private fun addStrategyExhaustionDiagnosis(
    strategyFacts: List<ObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    val httpsFacts = strategyFacts.filter { it.strategy?.protocol == StrategyProbeProtocol.HTTPS }
    val candidateIds = httpsFacts.mapNotNull { it.strategy?.candidateId }.distinct()
    if (candidateIds.size < 2) return
    val anySuccess = httpsFacts.any { it.strategy?.status == StrategyProbeStatus.SUCCESS }
    if (!anySuccess) {
        pushDiagnosis(
            diagnoses,
            seen,
            Diagnosis(
                code = "strategy_exhaustion",
                summary = "No tested strategy reached any tested target",
                severity = "blocked",
                evidence = listOf("candidatesTested=${candidateIds.size}"),
                recommendation =
                    "No desync strategy worked for any tested target. " +
                        "Consider using a proxy, tunnel, or VPN for these targets.",
            ),
        )
    }
}

private fun addPerDomainStrategyFailureDiagnosis(
    strategyFacts: List<ObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    val httpsFacts = strategyFacts.filter { it.strategy?.protocol == StrategyProbeProtocol.HTTPS }
    val domainGroups = httpsFacts.groupBy { parseDomainFromTarget(it.target) }.filterKeys { it != null }
    for ((domain, facts) in domainGroups) {
        val candidateCount = facts.mapNotNull { it.strategy?.candidateId }.distinct().size
        if (candidateCount < 2) continue
        val anySuccess = facts.any { it.strategy?.status == StrategyProbeStatus.SUCCESS }
        if (!anySuccess) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "strategy_domain_unreachable",
                    summary = "All tested strategies failed to recover this domain",
                    severity = "blocked",
                    target = domain,
                    evidence = listOf("strategiesTested=$candidateCount"),
                    recommendation =
                        "Consider adding this domain to a proxy or tunnel route, " +
                            "or enabling WS tunnel fallback to automatically bypass desync for this domain.",
                ),
            )
        }
    }
}

private fun parseDomainFromTarget(target: String): String? {
    val separator = " \u00b7 "
    val index = target.indexOf(separator)
    return if (index >= 0) target.substring(index + separator.length) else null
}

private fun addProtocolTotalFailureDiagnosis(
    strategyFacts: List<ObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
    protocol: StrategyProbeProtocol,
    code: String,
    summary: String,
) {
    val facts = strategyFacts.filter { it.strategy?.protocol == protocol }
    if (facts.size < 2) return
    if (!facts.any { it.strategy?.status == StrategyProbeStatus.SUCCESS }) {
        pushDiagnosis(
            diagnoses,
            seen,
            Diagnosis(
                code = code,
                summary = summary,
                evidence = facts.mapNotNull { parseDomainFromTarget(it.target) }.distinct(),
            ),
        )
    }
}

private fun addH3SelectiveBlockingDiagnosis(
    strategyFacts: List<ObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    // Find domains where HTTP probes advertise h3 support via Alt-Svc
    val httpFacts = strategyFacts.filter { it.strategy?.protocol == StrategyProbeProtocol.HTTP }
    val h3Domains =
        httpFacts
            .filter { it.strategy?.h3Advertised == true }
            .mapNotNull { parseDomainFromTarget(it.target) }
            .toSet()
    if (h3Domains.isEmpty()) return

    // Check if QUIC probes all failed for those same domains
    val quicFacts = strategyFacts.filter { it.strategy?.protocol == StrategyProbeProtocol.QUIC }
    for (domain in h3Domains) {
        val quicForDomain = quicFacts.filter { parseDomainFromTarget(it.target) == domain }
        if (quicForDomain.isEmpty()) continue
        val quicAllFailed = quicForDomain.none { it.strategy?.status == StrategyProbeStatus.SUCCESS }
        if (quicAllFailed) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "h3_selective_blocking",
                    summary = "HTTP/3 was advertised, but QUIC probes failed for this target",
                    target = domain,
                    evidence = listOf("h3Advertised=true", "quicProbes=${quicForDomain.size}", "quicSuccess=0"),
                    recommendation =
                        "Alt-Svc advertised HTTP/3. The probes test QUIC Initial responses; HTTP/3 was not tested. " +
                            "Repeat the test to compare results; the cause is not determined.",
                ),
            )
        }
    }
}

private fun normalizeTarget(value: String): String = value.trim().lowercase()

private data class DiagnosisControls(
    val tls: Boolean?,
    val http: Boolean?,
    val throughput: Boolean?,
) {
    fun forDiagnosis(code: String): Boolean? =
        when (code) {
            "tls_clienthello_timeout", "tls_clienthello_rst", "tls_clienthello_close",
            "tls_cert_mitm", "tls_ech_only",
            -> tls

            "http_blockpage" -> http

            "youtube_throttled", "throttling_suspected" -> throughput

            "network_connectivity_issue" -> false

            else -> null
        }
}

private fun diagnosisControls(
    domains: List<DomainObservationFact>,
    throughput: List<ThroughputObservationFact>,
): DiagnosisControls {
    val domainsWithControlRole = domains.filter { it.isControl }
    val tls =
        domainsWithControlRole
            .filter { it.tls13Status != TlsProbeStatus.NOT_RUN || it.tls12Status != TlsProbeStatus.NOT_RUN }
            .map { it.tls13Status == TlsProbeStatus.OK || it.tls12Status == TlsProbeStatus.OK }
    val http =
        domainsWithControlRole
            .filter { it.httpStatus != HttpProbeStatus.NOT_RUN }
            .map { it.httpStatus == HttpProbeStatus.OK }
    val throughputControls =
        throughput
            .filter { it.isControl }
            .map { it.status == ThroughputProbeStatus.MEASURED && it.medianBps > 0 }
    return DiagnosisControls(tls.controlResult(), http.controlResult(), throughputControls.controlResult())
}

private fun List<Boolean>.controlResult(): Boolean? = takeIf { it.isNotEmpty() }?.all { it }
