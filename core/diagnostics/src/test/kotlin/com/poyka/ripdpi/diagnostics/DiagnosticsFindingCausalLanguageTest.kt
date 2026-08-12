package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFindingCausalLanguageTest {
    @Test
    fun `classify describes endpoint failures as observations rather than established blocking`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.QUIC,
                        target = "quic.example",
                        quic = QuicObservationFact("quic.example", QuicProbeStatus.ERROR),
                    ),
                    ObservationFact(
                        kind = ObservationKind.SERVICE,
                        target = "messenger",
                        service =
                            ServiceObservationFact(
                                service = "messenger",
                                bootstrapStatus = HttpProbeStatus.UNREACHABLE,
                                mediaStatus = HttpProbeStatus.BLOCKPAGE,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.CIRCUMVENTION,
                        target = "tool",
                        circumvention =
                            CircumventionObservationFact(
                                tool = "tool",
                                handshakeStatus = EndpointProbeStatus.FAILED,
                            ),
                    ),
                ),
            )
        val expectedCodes =
            setOf(
                "quic_blocked",
                "service_bootstrap_blocked",
                "service_media_blocked",
                "circumvention_handshake_blocked",
            )
        val rendered =
            diagnoses
                .filter { it.code in expectedCodes }
                .joinToString("\n") { it.summary }

        assertTrue(
            diagnoses.map { it.code }.containsAll(expectedCodes) &&
                rendered.contains("observed", ignoreCase = true) &&
                !rendered.contains("was blocked", ignoreCase = true) &&
                !rendered.contains("traffic was blocked", ignoreCase = true) &&
                !rendered.contains("was blocked or suppressed", ignoreCase = true),
        )
    }

    @Test
    fun `classify qualifies dns and certificate findings without establishing a cause`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DNS,
                        target = "sinkhole.example",
                        dns =
                            DnsObservationFact(
                                domain = "sinkhole.example",
                                status = DnsObservationStatus.SINKHOLE_SUBSTITUTION,
                                udpAddresses = listOf("203.0.113.10"),
                                encryptedAddresses = listOf("198.51.100.10"),
                                udpLatencyMs = 2,
                                encryptedLatencyMs = 78,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "certificate.example",
                        domain =
                            DomainObservationFact(
                                host = "certificate.example",
                                transportFailure = TransportFailureKind.CERTIFICATE,
                                certificateAnomaly = true,
                            ),
                    ),
                ),
            )
        val expectedCodes = setOf("dns_tampering", "dns_injection_suspected", "tls_cert_mitm")
        val renderedText =
            diagnoses
                .flatMap { diagnosis -> listOfNotNull(diagnosis.summary, diagnosis.recommendation) }
                .joinToString("\n")
                .lowercase()
        val forbiddenPhrases =
            listOf(
                "likely injecting",
                "suggests interception",
                "indicating a general network problem",
                "is blocked network-wide",
                "traffic was blocked",
            )

        assertTrue(
            "DNS and certificate findings must describe observed signals without establishing cause, " +
                "interception, or injection",
            diagnoses.map { it.code }.containsAll(expectedCodes) &&
                renderedText.contains("observed") &&
                (renderedText.contains("signal") || renderedText.contains("pattern")) &&
                renderedText.contains("cause") &&
                renderedText.contains("interception") &&
                renderedText.contains("injection") &&
                (
                    renderedText.contains("not established") ||
                        renderedText.contains("not confirmed") ||
                        renderedText.contains("cannot establish") ||
                        renderedText.contains("insufficient evidence")
                ) &&
                forbiddenPhrases.none(renderedText::contains),
        )
    }
}
