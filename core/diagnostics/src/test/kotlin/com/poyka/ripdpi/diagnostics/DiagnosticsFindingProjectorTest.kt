package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFindingProjectorTest {
    @Test
    fun `classify emits tls ech only diagnosis when clear tls is blocked`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DOMAIN,
                        target = "blocked.example",
                        domain =
                            DomainObservationFact(
                                host = "blocked.example",
                                tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tls12Status = TlsProbeStatus.HANDSHAKE_FAILED,
                                tlsEchStatus = TlsProbeStatus.OK,
                                tlsEchVersion = "TLS1.3",
                            ),
                    ),
                ),
            )

        val diagnosis = diagnoses.firstOrNull { it.code == "tls_ech_only" }
        assertNotNull(diagnosis)
        assertEquals("blocked.example", diagnosis?.target)
        assertEquals("Plain TLS is blocked, but ECH succeeds", diagnosis?.summary)
    }

    @Test
    fun `classify emits strategy_exhaustion when no strategy recovers any target`() {
        val observations =
            listOf("candidate_a", "candidate_b").flatMap { candidateId ->
                listOf("meduza.io", "telegram.org").map { domain ->
                    ObservationFact(
                        kind = ObservationKind.STRATEGY,
                        target = "$candidateId \u00b7 $domain",
                        strategy =
                            StrategyObservationFact(
                                candidateId = candidateId,
                                protocol = StrategyProbeProtocol.HTTPS,
                                status = StrategyProbeStatus.FAILED,
                            ),
                    )
                }
            }
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val diagnosis = diagnoses.firstOrNull { it.code == "strategy_exhaustion" }
        assertNotNull(diagnosis)
        assertEquals("No desync strategy could recover any blocked target", diagnosis?.summary)
        assertEquals("blocked", diagnosis?.severity)
        assertNotNull(diagnosis?.recommendation)
        assert(diagnosis!!.recommendation!!.contains("proxy"))
    }

    @Test
    fun `classify does not emit strategy_exhaustion when at least one strategy succeeds`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 meduza.io",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.HTTPS,
                            status = StrategyProbeStatus.FAILED,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_b \u00b7 meduza.io",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_b",
                            protocol = StrategyProbeProtocol.HTTPS,
                            status = StrategyProbeStatus.SUCCESS,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        assert(diagnoses.none { it.code == "strategy_exhaustion" })
    }

    @Test
    fun `classify emits strategy_domain_unreachable for domains where all strategies fail`() {
        val observations =
            listOf("candidate_a", "candidate_b").flatMap { candidateId ->
                listOf(
                    ObservationFact(
                        kind = ObservationKind.STRATEGY,
                        target = "$candidateId \u00b7 meduza.io",
                        strategy =
                            StrategyObservationFact(
                                candidateId = candidateId,
                                protocol = StrategyProbeProtocol.HTTPS,
                                status = StrategyProbeStatus.FAILED,
                            ),
                    ),
                    ObservationFact(
                        kind = ObservationKind.STRATEGY,
                        target = "$candidateId \u00b7 signal.org",
                        strategy =
                            StrategyObservationFact(
                                candidateId = candidateId,
                                protocol = StrategyProbeProtocol.HTTPS,
                                status = StrategyProbeStatus.SUCCESS,
                            ),
                    ),
                )
            }
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val unreachable = diagnoses.filter { it.code == "strategy_domain_unreachable" }
        assertEquals(1, unreachable.size)
        assertEquals("meduza.io", unreachable[0].target)
        assertEquals("blocked", unreachable[0].severity)
        assertNotNull(unreachable[0].recommendation)
        assert(unreachable[0].recommendation!!.contains("proxy"))
        assert(diagnoses.none { it.code == "strategy_exhaustion" })
    }

    @Test
    fun `classify emits quic_total_failure when all QUIC probes fail`() {
        val observations =
            listOf("quic_disabled", "quic_burst").flatMap { candidateId ->
                listOf("discord.com", "whatsapp.com").map { domain ->
                    ObservationFact(
                        kind = ObservationKind.STRATEGY,
                        target = "$candidateId \u00b7 $domain",
                        strategy =
                            StrategyObservationFact(
                                candidateId = candidateId,
                                protocol = StrategyProbeProtocol.QUIC,
                                status = StrategyProbeStatus.FAILED,
                            ),
                    )
                }
            }
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val diagnosis = diagnoses.firstOrNull { it.code == "quic_total_failure" }
        assertNotNull(diagnosis)
        assert(diagnosis!!.evidence.contains("discord.com"))
        assert(diagnosis.evidence.contains("whatsapp.com"))
    }

    @Test
    fun `classify emits dns_latency_anomaly when UDP DNS is abnormally slow`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DNS,
                        target = "proton.me",
                        dns =
                            DnsObservationFact(
                                domain = "proton.me",
                                status = DnsObservationStatus.MATCH,
                                udpLatencyMs = 6021,
                                encryptedLatencyMs = 98,
                            ),
                        evidence = listOf("dns_match"),
                    ),
                ),
            )

        val diagnosis = diagnoses.firstOrNull { it.code == "dns_latency_anomaly" }
        assertNotNull(diagnosis)
        assertEquals("proton.me", diagnosis?.target)
        assert(diagnosis!!.evidence.contains("udpLatencyMs=6021"))
        assert(diagnosis.evidence.contains("encryptedLatencyMs=98"))
    }

    @Test
    fun `classify does not emit dns_latency_anomaly when latencies are normal`() {
        val diagnoses =
            DiagnosticsFindingProjector().classify(
                listOf(
                    ObservationFact(
                        kind = ObservationKind.DNS,
                        target = "discord.com",
                        dns =
                            DnsObservationFact(
                                domain = "discord.com",
                                status = DnsObservationStatus.MATCH,
                                udpLatencyMs = 23,
                                encryptedLatencyMs = 78,
                            ),
                        evidence = listOf("dns_match"),
                    ),
                ),
            )

        assert(diagnoses.none { it.code == "dns_latency_anomaly" })
    }

    @Test
    fun `classify emits http_network_blocked when all HTTP probes fail`() {
        val observations =
            listOf("candidate_a", "candidate_b").flatMap { candidateId ->
                listOf("meduza.io", "signal.org").map { domain ->
                    ObservationFact(
                        kind = ObservationKind.STRATEGY,
                        target = "$candidateId \u00b7 $domain",
                        strategy =
                            StrategyObservationFact(
                                candidateId = candidateId,
                                protocol = StrategyProbeProtocol.HTTP,
                                status = StrategyProbeStatus.FAILED,
                            ),
                    )
                }
            }
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val diagnosis = diagnoses.firstOrNull { it.code == "http_network_blocked" }
        assertNotNull(diagnosis)
        assertEquals("HTTP port 80 is blocked network-wide", diagnosis?.summary)
        assert(diagnosis!!.evidence.contains("meduza.io"))
        assert(diagnosis.evidence.contains("signal.org"))
    }

    @Test
    fun `classify does not emit http_network_blocked when some HTTP probes succeed`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 meduza.io",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.HTTP,
                            status = StrategyProbeStatus.FAILED,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_b \u00b7 signal.org",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_b",
                            protocol = StrategyProbeProtocol.HTTP,
                            status = StrategyProbeStatus.SUCCESS,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        assert(diagnoses.none { it.code == "http_network_blocked" })
    }

    @Test
    fun `classify sets controlValidated true when control domains pass`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.DOMAIN,
                    target = "cloudflare.com",
                    domain =
                        DomainObservationFact(
                            host = "cloudflare.com",
                            tls13Status = TlsProbeStatus.OK,
                            isControl = true,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.DOMAIN,
                    target = "blocked.example",
                    domain =
                        DomainObservationFact(
                            host = "blocked.example",
                            transportFailure = TransportFailureKind.RESET,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        assertTrue(diagnoses.isNotEmpty())
        diagnoses.forEach { assertEquals(true, it.controlValidated) }
        assert(diagnoses.none { it.code == "network_connectivity_issue" })
    }

    @Test
    fun `classify emits network_connectivity_issue when control domains fail`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.DOMAIN,
                    target = "cloudflare.com",
                    domain =
                        DomainObservationFact(
                            host = "cloudflare.com",
                            tls13Status = TlsProbeStatus.HANDSHAKE_FAILED,
                            tls12Status = TlsProbeStatus.HANDSHAKE_FAILED,
                            isControl = true,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.DOMAIN,
                    target = "blocked.example",
                    domain =
                        DomainObservationFact(
                            host = "blocked.example",
                            transportFailure = TransportFailureKind.RESET,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val networkIssue = diagnoses.firstOrNull { it.code == "network_connectivity_issue" }
        assertNotNull(networkIssue)
        diagnoses.forEach { assertEquals(false, it.controlValidated) }
    }

    @Test
    fun `classify does not set controlValidated when no control domains present`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.DOMAIN,
                    target = "blocked.example",
                    domain =
                        DomainObservationFact(
                            host = "blocked.example",
                            transportFailure = TransportFailureKind.RESET,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        diagnoses.forEach { assertNull(it.controlValidated) }
    }

    @Test
    fun `classify emits throttling_suspected when target throughput is much lower than control`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.THROUGHPUT,
                    target = "CDN Control",
                    throughput =
                        ThroughputObservationFact(
                            label = "CDN Control",
                            status = ThroughputProbeStatus.MEASURED,
                            isControl = true,
                            medianBps = 10_000_000,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.THROUGHPUT,
                    target = "Target Service",
                    throughput =
                        ThroughputObservationFact(
                            label = "Target Service",
                            status = ThroughputProbeStatus.MEASURED,
                            isControl = false,
                            medianBps = 500_000,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val throttling = diagnoses.firstOrNull { it.code == "throttling_suspected" }
        assertNotNull(throttling)
        assertEquals("Target Service", throttling?.target)
        assertTrue(throttling!!.evidence.any { it.startsWith("targetBps=") })
        assertTrue(throttling.evidence.any { it.startsWith("controlBps=") })
        assertTrue(throttling.evidence.any { it.startsWith("ratio=") })
    }

    @Test
    fun `classify does not emit throttling_suspected when throughput ratio is acceptable`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.THROUGHPUT,
                    target = "CDN Control",
                    throughput =
                        ThroughputObservationFact(
                            label = "CDN Control",
                            status = ThroughputProbeStatus.MEASURED,
                            isControl = true,
                            medianBps = 10_000_000,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.THROUGHPUT,
                    target = "Target Service",
                    throughput =
                        ThroughputObservationFact(
                            label = "Target Service",
                            status = ThroughputProbeStatus.MEASURED,
                            isControl = false,
                            medianBps = 8_000_000,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        assert(diagnoses.none { it.code == "throttling_suspected" })
    }
}
