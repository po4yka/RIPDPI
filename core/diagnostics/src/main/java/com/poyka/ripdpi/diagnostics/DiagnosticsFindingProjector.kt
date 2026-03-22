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

            dns
                .filter { it.status == DnsObservationStatus.SUBSTITUTION || it.status == DnsObservationStatus.EXPECTED_MISMATCH }
                .forEach { observation ->
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
                    val domain = domains.firstOrNull {
                        normalizeTarget(it.host) == normalizeTarget(observation.domain) &&
                            it.httpStatus == HttpProbeStatus.BLOCKPAGE
                    }
                    if (domain != null) {
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

            domains.forEach { observation ->
                val target = observation.host
                when (observation.transportFailure) {
                    TransportFailureKind.TIMEOUT ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "tls_clienthello_timeout",
                                summary = "TLS handshake timed out after ClientHello",
                                target = target,
                                evidence = listOf(target),
                            ),
                        )
                    TransportFailureKind.RESET ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "tls_clienthello_rst",
                                summary = "TLS handshake was reset after ClientHello",
                                target = target,
                                evidence = listOf(target),
                            ),
                        )
                    TransportFailureKind.CLOSE ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "tls_clienthello_close",
                                summary = "TLS handshake was closed after ClientHello",
                                target = target,
                                evidence = listOf(target),
                            ),
                        )
                    TransportFailureKind.CERTIFICATE ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "tls_cert_mitm",
                                summary = "TLS certificate anomaly suggests interception",
                                target = target,
                                evidence = listOf(target),
                            ),
                        )
                    else -> Unit
                }
                if (observation.certificateAnomaly || observation.tls13Status == TlsProbeStatus.CERT_INVALID) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "tls_cert_mitm",
                            summary = "TLS certificate anomaly suggests interception",
                            target = target,
                            evidence = listOf(target),
                        ),
                    )
                }
                if (observation.httpStatus == HttpProbeStatus.BLOCKPAGE) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "http_blockpage",
                            summary = "HTTP response matched a blockpage",
                            target = target,
                            evidence = listOf(target),
                        ),
                    )
                }
                val matchingQuic = quic.firstOrNull { normalizeTarget(it.host) == normalizeTarget(target) }
                if (
                    matchingQuic != null &&
                    observation.transportFailure in setOf(
                        TransportFailureKind.TIMEOUT,
                        TransportFailureKind.RESET,
                        TransportFailureKind.CLOSE,
                    ) &&
                    matchingQuic.status in setOf(QuicProbeStatus.ERROR, QuicProbeStatus.EMPTY)
                ) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "sni_triggered_tls_interference",
                            summary = "TLS interference appears SNI-triggered while QUIC is also blocked",
                            target = target,
                            evidence = listOf(target),
                        ),
                    )
                }
            }

            tcp.forEach { observation ->
                when (observation.status) {
                    TcpProbeStatus.BLOCKED_16KB ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "tcp_16kb_cutoff",
                                summary = "TCP flow failed around the 16 KiB threshold",
                                target = observation.provider,
                                evidence = listOfNotNull(observation.bytesSent?.toString()),
                            ),
                        )
                    TcpProbeStatus.WHITELIST_SNI_OK ->
                        pushDiagnosis(
                            diagnoses,
                            seen,
                            Diagnosis(
                                code = "whitelist_sni_bypassable",
                                summary = "A whitelisted SNI restored TCP reachability",
                                target = observation.provider,
                                evidence = listOfNotNull(observation.selectedSni),
                            ),
                        )
                    else -> Unit
                }
            }

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

            services.forEach { observation ->
                if (observation.bootstrapStatus !in setOf(HttpProbeStatus.OK, HttpProbeStatus.NOT_RUN)) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "service_bootstrap_blocked",
                            summary = "${observation.service} bootstrap endpoint was blocked",
                            target = observation.service,
                            evidence = listOf(observation.service),
                        ),
                    )
                }
                if (observation.mediaStatus !in setOf(HttpProbeStatus.OK, HttpProbeStatus.NOT_RUN)) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "service_media_blocked",
                            summary = "${observation.service} media endpoint was blocked",
                            target = observation.service,
                            evidence = listOf(observation.service),
                        ),
                    )
                }
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

            circumventions.forEach { observation ->
                if (observation.bootstrapStatus !in setOf(HttpProbeStatus.OK, HttpProbeStatus.NOT_RUN)) {
                    pushDiagnosis(
                        diagnoses,
                        seen,
                        Diagnosis(
                            code = "circumvention_bootstrap_blocked",
                            summary = "${observation.tool} bootstrap endpoint was blocked",
                            target = observation.tool,
                            evidence = listOf(observation.tool),
                        ),
                    )
                }
                if (observation.handshakeStatus != EndpointProbeStatus.OK && observation.handshakeStatus != EndpointProbeStatus.NOT_RUN) {
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

            val controlMedian =
                throughput.filter(ThroughputObservationFact::isControl).map(ThroughputObservationFact::medianBps).maxOrNull()
            throughput
                .filterNot(ThroughputObservationFact::isControl)
                .firstOrNull { normalizeTarget(it.label).contains("youtube") }
                ?.let { youtube ->
                    val hasHardFailure =
                        diagnoses.any { diagnosis ->
                            diagnosis.code in setOf(
                                "dns_tampering",
                                "http_blockpage",
                                "tls_clienthello_timeout",
                                "tls_clienthello_rst",
                                "tls_clienthello_close",
                            ) && diagnosis.target?.let(::normalizeTarget)?.contains("youtube") == true
                        }
                    if (
                        youtube.status == ThroughputProbeStatus.MEASURED &&
                        !hasHardFailure &&
                        controlMedian != null &&
                        controlMedian >= ControlFloorBps &&
                        youtube.medianBps < (controlMedian * ThrottlingRatioThreshold).toLong()
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

            return diagnoses
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
