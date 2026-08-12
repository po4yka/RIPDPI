package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFindingProjectorTest {
    @Test
    fun `classify reports tls ech split without claiming blocking cause`() {
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
        assertTrue(
            diagnosis?.summary?.let { summary ->
                summary.contains("observed", ignoreCase = true) &&
                    summary.contains("ECH succeeded", ignoreCase = true) &&
                    summary.contains("cause is not established", ignoreCase = true) &&
                    !summary.contains("plain TLS is blocked", ignoreCase = true)
            } == true,
        )
    }

    @Test
    fun `classify scopes strategy exhaustion to tested candidates and targets`() {
        val observations =
            listOf("candidate_a", "candidate_b").flatMap { candidateId ->
                listOf("blocked.example", "telegram.org").map { domain ->
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
        assertTrue(
            diagnosis?.summary?.let { summary ->
                summary.contains("tested", ignoreCase = true) &&
                    summary.contains("failed", ignoreCase = true) &&
                    summary.contains("does not establish", ignoreCase = true) &&
                    !summary.contains("blocked target", ignoreCase = true)
            } == true,
        )
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
                    target = "candidate_a \u00b7 blocked.example",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.HTTPS,
                            status = StrategyProbeStatus.FAILED,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_b \u00b7 blocked.example",
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
                        target = "$candidateId \u00b7 blocked.example",
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
        assertEquals("blocked.example", unreachable[0].target)
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
    fun `classify reports dns latency anomaly without attributing throttling`() {
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
        assertEquals("degraded", diagnosis?.severity)
        assert(diagnosis!!.evidence.contains("udpLatencyMs=6021"))
        assert(diagnosis.evidence.contains("encryptedLatencyMs=98"))
        assert(diagnosis.evidence.contains("slowdownRatio=${6021 / 98}x"))
        assertNotNull(diagnosis.recommendation)
        assertTrue(
            listOfNotNull(diagnosis.summary, diagnosis.recommendation).joinToString(" ").let { text ->
                text.contains("observed", ignoreCase = true) &&
                    text.contains("cause is not established", ignoreCase = true) &&
                    !text.contains("suggesting throttling", ignoreCase = true) &&
                    !text.contains("bypasses this throttling", ignoreCase = true)
            },
        )
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
    fun `classify scopes total HTTP failure to the tested sample`() {
        val observations =
            listOf("candidate_a", "candidate_b").flatMap { candidateId ->
                listOf("blocked.example", "signal.org").map { domain ->
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
        assertTrue(
            diagnosis?.summary?.let { summary ->
                summary.contains("all tested", ignoreCase = true) &&
                    summary.contains("failed", ignoreCase = true) &&
                    summary.contains("does not establish", ignoreCase = true) &&
                    !summary.contains("blocked network-wide", ignoreCase = true)
            } == true,
        )
        assert(diagnosis!!.evidence.contains("blocked.example"))
        assert(diagnosis.evidence.contains("signal.org"))
    }

    @Test
    fun `classify does not emit http_network_blocked when some HTTP probes succeed`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 blocked.example",
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
    fun `classify does not attribute joint control failures to a general network cause`() {
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
        assertTrue(
            networkIssue?.summary?.let { summary ->
                summary.contains("observed", ignoreCase = true) &&
                    summary.contains("cause is not established", ignoreCase = true) &&
                    !summary.contains("indicating a general network problem", ignoreCase = true)
            } == true,
        )
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
    fun `classify treats throughput delta as a throttling candidate signal`() {
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
        assertTrue(
            throttling.summary.contains("observed", ignoreCase = true) &&
                throttling.summary.contains("candidate", ignoreCase = true) &&
                throttling.summary.contains("not established", ignoreCase = true) &&
                !throttling.summary.contains("suggesting throttling", ignoreCase = true),
        )
    }

    @Test
    fun `classify treats h3 and failed quic as a candidate filtering pattern`() {
        val observations =
            listOf(
                // HTTP strategy observation advertising h3 for discord.com
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 discord.com",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.HTTP,
                            status = StrategyProbeStatus.SUCCESS,
                            h3Advertised = true,
                        ),
                ),
                // QUIC strategy probes that all fail for the same domain
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 discord.com",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.QUIC,
                            status = StrategyProbeStatus.FAILED,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_b \u00b7 discord.com",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_b",
                            protocol = StrategyProbeProtocol.QUIC,
                            status = StrategyProbeStatus.FAILED,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        val diagnosis = diagnoses.firstOrNull { it.code == "h3_selective_blocking" }
        assertNotNull(diagnosis)
        assertEquals("discord.com", diagnosis?.target)
        assertTrue(
            diagnosis?.summary?.let { summary ->
                summary.contains("observed", ignoreCase = true) &&
                    summary.contains("candidate", ignoreCase = true) &&
                    summary.contains("not established", ignoreCase = true) &&
                    !summary.contains("quic is blocked", ignoreCase = true)
            } == true,
        )
        assertTrue(diagnosis!!.evidence.contains("h3Advertised=true"))
        assertTrue(diagnosis.evidence.contains("quicSuccess=0"))
        assertNotNull(diagnosis.recommendation)
    }

    @Test
    fun `classify does not emit h3_selective_blocking when QUIC succeeds`() {
        val observations =
            listOf(
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 discord.com",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.HTTP,
                            status = StrategyProbeStatus.SUCCESS,
                            h3Advertised = true,
                        ),
                ),
                ObservationFact(
                    kind = ObservationKind.STRATEGY,
                    target = "candidate_a \u00b7 discord.com",
                    strategy =
                        StrategyObservationFact(
                            candidateId = "candidate_a",
                            protocol = StrategyProbeProtocol.QUIC,
                            status = StrategyProbeStatus.SUCCESS,
                        ),
                ),
            )
        val diagnoses = DiagnosticsFindingProjector().classify(observations)
        assert(diagnoses.none { it.code == "h3_selective_blocking" })
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
