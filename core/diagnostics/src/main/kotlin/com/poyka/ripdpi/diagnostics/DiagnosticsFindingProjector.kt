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
                            "Control domains also failed, indicating a general network problem " +
                                "rather than targeted blocking",
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
            it.status == DnsObservationStatus.SUBSTITUTION ||
                it.status == DnsObservationStatus.EXPECTED_MISMATCH ||
                it.status == DnsObservationStatus.NXDOMAIN
        }.forEach { observation ->
            val udpMs = observation.udpLatencyMs
            val encMs = observation.encryptedLatencyMs
            val isTampered =
                observation.status == DnsObservationStatus.SUBSTITUTION ||
                    observation.status == DnsObservationStatus.NXDOMAIN
            val injectionSuspected = isTampered && udpMs != null && udpMs <= DnsInjectionThresholdMs
            val (mechanism, summary) =
                when {
                    observation.status == DnsObservationStatus.NXDOMAIN -> {
                        "record_deletion" to
                            "DNS records were deleted (NXDOMAIN)"
                    }

                    injectionSuspected -> {
                        "injection" to "DNS response injected in under 5ms with substituted answers"
                    }

                    else -> {
                        "substitution" to "DNS answers were substituted"
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
        .filter { it.status == DnsObservationStatus.SUBSTITUTION }
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
            observation.status == DnsObservationStatus.SUBSTITUTION ||
                observation.status == DnsObservationStatus.NXDOMAIN
        if (udpMs <= DnsInjectionThresholdMs && isTampered) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_injection_suspected",
                    summary =
                        "DNS response arrived in under 5ms with substituted answers, " +
                            "suggesting in-path injection",
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
                        "DPI equipment is likely injecting forged DNS responses. " +
                            "Enable encrypted DNS (DoH/DoT) to bypass this injection.",
                ),
            )
        }
        if (udpMs > DnsLatencySlowThresholdMs || (encMs > 0 && udpMs > encMs * DnsEncryptedLatencyMultiplier)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "dns_latency_anomaly",
                    summary = "UDP DNS resolution was abnormally slow, suggesting throttling",
                    severity = "degraded",
                    target = observation.domain,
                    evidence =
                        buildList {
                            add("udpLatencyMs=$udpMs")
                            add("encryptedLatencyMs=$encMs")
                            if (encMs > 0) add("slowdownRatio=${udpMs / encMs}x")
                        },
                    recommendation =
                        "Encrypted DNS bypasses this throttling. " +
                            "Enable DoH or DoT to avoid ISP-injected DNS delays for this domain.",
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
                TransportFailureKind.TIMEOUT -> "tls_clienthello_timeout" to "TLS handshake timed out after ClientHello"
                TransportFailureKind.RESET -> "tls_clienthello_rst" to "TLS handshake was reset after ClientHello"
                TransportFailureKind.CLOSE -> "tls_clienthello_close" to "TLS handshake was closed after ClientHello"
                TransportFailureKind.CERTIFICATE -> "tls_cert_mitm" to "TLS certificate anomaly suggests interception"
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
                    summary = "TLS certificate anomaly suggests interception",
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
                    summary = "Plain TLS is blocked, but ECH succeeds",
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
                summary = "TLS interference appears SNI-triggered while QUIC is also blocked",
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
                summary = "QUIC traffic was blocked or suppressed",
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
            "${observation.service} bootstrap endpoint was blocked",
            diagnoses,
            seen,
        )
        addServiceHttpDiagnosis(
            observation.service,
            observation.mediaStatus,
            "service_media_blocked",
            "${observation.service} media endpoint was blocked",
            diagnoses,
            seen,
        )
        if (observation.quicStatus in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY)) {
            pushDiagnosis(
                diagnoses,
                seen,
                Diagnosis(
                    code = "quic_blocked",
                    summary = "QUIC traffic was blocked or suppressed",
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
            "${observation.tool} bootstrap endpoint was blocked",
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
                    summary = "${observation.tool} handshake endpoint was blocked",
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
                    summary = "YouTube throughput was heavily throttled relative to control traffic",
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
                            "Target throughput is significantly lower than control, " +
                                "suggesting throttling",
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
        summary = "QUIC is completely blocked on this network",
    )
    addProtocolTotalFailureDiagnosis(
        strategyFacts,
        diagnoses,
        seen,
        StrategyProbeProtocol.HTTP,
        code = "http_network_blocked",
        summary = "HTTP port 80 is blocked network-wide",
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
                summary = "No desync strategy could recover any blocked target",
                severity = "blocked",
                evidence = listOf("candidatesTested=${candidateIds.size}"),
                recommendation =
                    "No desync strategy worked for any tested target. " +
                        "Consider using a proxy, tunnel, or VPN for blocked domains.",
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
                    summary = "Server advertises HTTP/3 (h3) via Alt-Svc but QUIC is blocked",
                    target = domain,
                    evidence = listOf("h3Advertised=true", "quicProbes=${quicForDomain.size}", "quicSuccess=0"),
                    recommendation =
                        "The server supports HTTP/3 but QUIC traffic is being blocked. " +
                            "This may indicate selective QUIC/UDP filtering by the network.",
                ),
            )
        }
    }
}

private fun normalizeTarget(value: String): String = value.trim().lowercase()
