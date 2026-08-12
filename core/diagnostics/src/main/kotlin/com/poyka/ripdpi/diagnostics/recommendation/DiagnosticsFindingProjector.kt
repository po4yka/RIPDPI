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
private val BlockedTransportFailures =
    setOf(
        TransportFailureKind.TIMEOUT,
        TransportFailureKind.RESET,
        TransportFailureKind.CLOSE,
    )

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

            val controlDomains = domains.filter { it.isControl }
            val controlsPassed =
                controlDomains.isNotEmpty() &&
                    controlDomains.all {
                        it.tls13Status == TlsProbeStatus.OK || it.tls12Status == TlsProbeStatus.OK
                    }
            val hasControls = controlDomains.isNotEmpty()

            collectDnsDiagnoses(dns, domains, diagnoses, seen)
            collectDomainDiagnoses(domains, quic, diagnoses, seen)
            collectTcpDiagnoses(tcp, diagnoses, seen)
            collectQuicDiagnoses(quic, diagnoses, seen)
            collectServiceDiagnoses(services, diagnoses, seen)
            collectCircumventionDiagnoses(circumventions, diagnoses, seen)
            collectThroughputDiagnoses(throughput, diagnoses, seen)
            collectStrategyDiagnoses(strategyFacts, diagnoses, seen)

            if (hasControls && !controlsPassed) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "network_connectivity_issue",
                        summary =
                            "Control and affected-domain failures were observed together. This pattern is " +
                                "consistent with a general reachability problem, but its cause is not established.",
                    ),
                )
            }

            if (hasControls) {
                return diagnoses.map { d -> d.copy(controlValidated = controlsPassed) }
            }

            return diagnoses
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
            val (mechanism, summary) =
                when {
                    observation.status == DnsObservationStatus.NXDOMAIN_MISMATCH -> {
                        "record_deletion" to
                            "Observed DNS NXDOMAIN divergence; record deletion is a candidate pattern, " +
                            "but its cause is not established"
                    }

                    injectionSuspected -> {
                        "injection" to
                            "Observed substituted DNS response under 5ms; injection is a candidate signal, " +
                            "but its cause is not established"
                    }

                    else -> {
                        "substitution" to
                            "Observed DNS answer divergence consistent with substitution; its cause is not established"
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
                            add("mechanism=$mechanism")
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
                        "Observed substituted DNS response under 5ms. In-path injection is a candidate " +
                            "explanation, but injection is not confirmed and its cause is not established.",
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
                        "Use encrypted DNS (DoH/DoT) to compare this signal on another path; the current " +
                            "evidence does not establish injection or identify its cause.",
                ),
            )
        }
        if (udpMs > DnsLatencySlowThresholdMs || (encMs > 0 && udpMs > encMs * DnsEncryptedLatencyMultiplier)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_latency_anomaly",
                    summary =
                        "Observed UDP DNS latency was substantially higher than the encrypted comparison. " +
                            "Throttling is a candidate explanation, but its cause is not established.",
                    severity = "degraded",
                    target = observation.domain,
                    evidence =
                        buildList {
                            add("udpLatencyMs=$udpMs")
                            add("encryptedLatencyMs=$encMs")
                            if (encMs > 0) add("slowdownRatio=${udpMs / encMs}x")
                        },
                    recommendation =
                        "Compare the result with encrypted DNS (DoH/DoT) and another network before " +
                            "attributing the delay to throttling or an in-path mechanism.",
                ),
            )
        }
    }
}

private fun collectDomainDiagnoses(
    domains: List<DomainObservationFact>,
    quic: List<QuicObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    domains.forEach { obs ->
        val (code, summary) =
            when (obs.transportFailure) {
                TransportFailureKind.TIMEOUT -> {
                    "tls_clienthello_timeout" to "TLS handshake timed out after ClientHello"
                }

                TransportFailureKind.RESET -> {
                    "tls_clienthello_rst" to "TLS handshake was reset after ClientHello"
                }

                TransportFailureKind.CLOSE -> {
                    "tls_clienthello_close" to "TLS handshake was closed after ClientHello"
                }

                TransportFailureKind.CERTIFICATE -> {
                    "tls_cert_mitm" to
                        "Observed TLS certificate anomaly. Interception is a candidate explanation, " +
                        "but its cause is not established."
                }

                else -> {
                    null to null
                }
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
                    summary =
                        "Observed TLS certificate anomaly. Interception is a candidate explanation, " +
                            "but its cause is not established.",
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
        if (obs.tlsEchStatus == TlsProbeStatus.OK && obs.tls13Status != TlsProbeStatus.OK &&
            obs.tls12Status != TlsProbeStatus.OK
        ) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "tls_ech_only",
                    summary =
                        "Observed plain TLS failures while ECH succeeded; the path-dependent cause is not established",
                    target = obs.host,
                    evidence = listOfNotNull(obs.host, obs.tlsEchVersion, obs.tlsEchError),
                ),
            )
        }
        addSniInterferenceDiagnosis(obs, quic, diagnoses, seen)
    }
}

private fun addSniInterferenceDiagnosis(
    observation: DomainObservationFact,
    quic: List<QuicObservationFact>,
    diagnoses: MutableList<Diagnosis>,
    seen: MutableSet<String>,
) {
    val matchingQuic = quic.firstOrNull { normalizeTarget(it.host) == normalizeTarget(observation.host) }
    if (
        matchingQuic != null &&
        observation.transportFailure in BlockedTransportFailures &&
        matchingQuic.status in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY)
    ) {
        pushDiagnosis(
            diagnoses,
            seen,
            Diagnosis(
                code = "sni_triggered_tls_interference",
                summary =
                    "Observed TLS and QUIC failures for the same target. An SNI-triggered pattern is a " +
                        "candidate explanation, but its cause is not established.",
                target = observation.host,
                evidence = listOf(observation.host),
            ),
        )
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
                summary = "Observed QUIC probe failures or empty responses; blocking is not established",
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
            "Observed ${observation.service} bootstrap endpoint probe failure; blocking is not established",
            diagnoses,
            seen,
        )
        addServiceHttpDiagnosis(
            observation.service,
            observation.mediaStatus,
            "service_media_blocked",
            "Observed ${observation.service} media endpoint probe failure; blocking is not established",
            diagnoses,
            seen,
        )
        if (observation.quicStatus in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "quic_blocked",
                    summary = "Observed QUIC probe failures or empty responses; blocking is not established",
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
            "Observed ${observation.tool} bootstrap endpoint probe failure; blocking is not established",
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
                    summary =
                        "Observed ${observation.tool} handshake endpoint probe failure; " +
                            "blocking is not established",
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
            .filter(ThroughputObservationFact::isControl)
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
                    summary =
                        "Observed YouTube throughput was substantially lower than control traffic. " +
                            "Throttling is a candidate explanation, but it is not established.",
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
                            "Observed target throughput was substantially lower than control traffic. " +
                                "Throttling is a candidate explanation, but it is not established.",
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
        summary = "All tested QUIC strategy probes failed; this sample does not establish network-wide blocking",
    )
    addProtocolTotalFailureDiagnosis(
        strategyFacts,
        diagnoses,
        seen,
        StrategyProbeProtocol.HTTP,
        code = "http_network_blocked",
        summary =
            "All tested HTTP port 80 strategy probes failed; this sample does not establish " +
                "network-wide blocking",
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
                summary =
                    "All tested desync candidates failed on the sampled targets; this does not establish blocking",
                severity = "blocked",
                evidence = listOf("candidatesTested=${candidateIds.size}"),
                recommendation =
                    "No tested desync candidate succeeded on the sampled targets. Consider a proxy, tunnel, " +
                        "or VPN while collecting a paired-path comparison before attributing a cause.",
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
                    summary =
                        "All tested strategy probes failed for this domain; the cause is not established",
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
                    summary =
                        "Observed HTTP/3 advertisement with failed QUIC probes. Selective filtering is a " +
                            "candidate pattern, but blocking is not established.",
                    target = domain,
                    evidence = listOf("h3Advertised=true", "quicProbes=${quicForDomain.size}", "quicSuccess=0"),
                    recommendation =
                        "Repeat the same QUIC comparison on another path before attributing selective " +
                            "QUIC/UDP filtering to the network.",
                ),
            )
        }
    }
}

private fun normalizeTarget(value: String): String = value.trim().lowercase()
