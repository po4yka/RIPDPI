package com.poyka.ripdpi.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsFindingProjector
    @Inject
    constructor() {
        companion object {
            const val ClassifierVersion = "ru_ooni_kotlin_v2"
            private const val ThrottlingRatioThreshold = 0.25
            private const val ControlFloorBps = 5_000_000L
            private val BlockedTransportFailures =
                setOf(
                    TransportFailureKind.TIMEOUT,
                    TransportFailureKind.RESET,
                    TransportFailureKind.CLOSE,
                )
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

            collectDnsDiagnoses(dns, domains, diagnoses, seen)
            collectDomainDiagnoses(domains, quic, diagnoses, seen)
            collectTcpDiagnoses(tcp, diagnoses, seen)
            collectQuicDiagnoses(quic, diagnoses, seen)
            collectServiceDiagnoses(services, diagnoses, seen)
            collectCircumventionDiagnoses(circumventions, diagnoses, seen)
            collectThroughputDiagnoses(throughput, diagnoses, seen)

            return diagnoses
        }

        private fun collectDnsDiagnoses(
            dns: List<DnsObservationFact>,
            domains: List<DomainObservationFact>,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            dns
                .filter {
                    it.status == DnsObservationStatus.SUBSTITUTION ||
                        it.status == DnsObservationStatus.EXPECTED_MISMATCH
                }.forEach { observation ->
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "dns_tampering",
                            summary = "DNS answers were substituted",
                            target = observation.domain,
                            evidence = observation.udpAddresses + observation.encryptedAddresses,
                        ),
                    )
                }

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

        private fun collectDomainDiagnoses(
            domains: List<DomainObservationFact>,
            quic: List<QuicObservationFact>,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            domains.forEach { observation ->
                addTransportFailureDiagnosis(observation, diagnoses, seen)
                addCertificateDiagnosis(observation, diagnoses, seen)
                addHttpBlockpageDiagnosis(observation, diagnoses, seen)
                addEchOnlyDiagnosis(observation, diagnoses, seen)
                addSniInterferenceDiagnosis(observation, quic, diagnoses, seen)
            }
        }

        private fun addTransportFailureDiagnosis(
            observation: DomainObservationFact,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            val diagnosisCode =
                when (observation.transportFailure) {
                    TransportFailureKind.TIMEOUT -> "tls_clienthello_timeout"
                    TransportFailureKind.RESET -> "tls_clienthello_rst"
                    TransportFailureKind.CLOSE -> "tls_clienthello_close"
                    TransportFailureKind.CERTIFICATE -> "tls_cert_mitm"
                    else -> null
                }
            val summary =
                when (observation.transportFailure) {
                    TransportFailureKind.TIMEOUT -> "TLS handshake timed out after ClientHello"
                    TransportFailureKind.RESET -> "TLS handshake was reset after ClientHello"
                    TransportFailureKind.CLOSE -> "TLS handshake was closed after ClientHello"
                    TransportFailureKind.CERTIFICATE -> "TLS certificate anomaly suggests interception"
                    else -> null
                }
            if (diagnosisCode != null && summary != null) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = diagnosisCode,
                        summary = summary,
                        target = observation.host,
                        evidence = listOf(observation.host),
                    ),
                )
            }
        }

        private fun addCertificateDiagnosis(
            observation: DomainObservationFact,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            if (observation.certificateAnomaly || observation.tls13Status == TlsProbeStatus.CERT_INVALID) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "tls_cert_mitm",
                        summary = "TLS certificate anomaly suggests interception",
                        target = observation.host,
                        evidence = listOf(observation.host),
                    ),
                )
            }
        }

        private fun addHttpBlockpageDiagnosis(
            observation: DomainObservationFact,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            if (observation.httpStatus == HttpProbeStatus.BLOCKPAGE) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "http_blockpage",
                        summary = "HTTP response matched a blockpage",
                        target = observation.host,
                        evidence = listOf(observation.host),
                    ),
                )
            }
        }

        private fun addEchOnlyDiagnosis(
            observation: DomainObservationFact,
            diagnoses: MutableList<Diagnosis>,
            seen: MutableSet<String>,
        ) {
            if (
                observation.tlsEchStatus == TlsProbeStatus.OK &&
                observation.tls13Status != TlsProbeStatus.OK &&
                observation.tls12Status != TlsProbeStatus.OK
            ) {
                pushDiagnosis(
                    diagnoses,
                    seen,
                    Diagnosis(
                        code = "tls_ech_only",
                        summary = "Plain TLS is blocked, but ECH succeeds",
                        target = observation.host,
                        evidence =
                            listOfNotNull(
                                observation.host,
                                observation.tlsEchVersion,
                                observation.tlsEchError,
                            ),
                    ),
                )
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
            quic
                .filter { it.status in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY) }
                .forEach { observation ->
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "quic_blocked",
                            summary = "QUIC traffic was blocked or suppressed",
                            target = observation.host,
                            evidence = listOf(observation.host),
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
                if (
                    observation.handshakeStatus != EndpointProbeStatus.OK &&
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
                    Diagnosis(
                        code = code,
                        summary = summary,
                        target = target,
                        evidence = listOf(target),
                    ),
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
                    ?: return
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

        private fun normalizeTarget(value: String): String = value.trim().lowercase()
    }
