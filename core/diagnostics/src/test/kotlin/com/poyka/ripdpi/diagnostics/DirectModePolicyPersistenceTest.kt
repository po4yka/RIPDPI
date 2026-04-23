package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DirectModePolicyPersistenceTest {
    @Test
    fun `dns plus one successful active attempt confirms transparent policy`() {
        val persisted =
            buildPersistableDirectPathObservations(
                report =
                    ScanReport(
                        sessionId = "session",
                        profileId = "profile",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 200L,
                        summary = "summary",
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "dns_integrity",
                                    target = "example.org",
                                    outcome = "dns_match",
                                    details =
                                        listOf(
                                            ProbeDetail("targetHost", "example.org"),
                                            ProbeDetail("dnsClassification", "CLEAN"),
                                            ProbeDetail("dnsAnswerClass", "CLEAN"),
                                        ),
                                ),
                                ProbeResult(
                                    probeType = "strategy_https",
                                    target = "example.org",
                                    outcome = "tls_ok",
                                    details = listOf(ProbeDetail("targetHost", "example.org")),
                                ),
                            ),
                    ),
                existingRecords = emptyList(),
            ).single()
                .second

        assertEquals(DirectModeOutcome.TRANSPARENT_OK, persisted.transportPolicy?.outcome)
        assertEquals(200L, persisted.policyConfirmedAt)
        assertEquals(0, persisted.policyFailureCount)
    }

    @Test
    fun `single failed attempt only increments failure budget for previously confirmed policy`() {
        val persisted =
            buildPersistableDirectPathObservations(
                report =
                    ScanReport(
                        sessionId = "session",
                        profileId = "profile",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 200L,
                        summary = "summary",
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "strategy_https",
                                    target = "example.org",
                                    outcome = "tls_handshake_failed",
                                    details = listOf(ProbeDetail("targetHost", "example.org")),
                                ),
                            ),
                    ),
                existingRecords =
                    listOf(
                        ServerCapabilityRecord(
                            scope = "direct_path",
                            fingerprintHash = "fp",
                            authority = "example.org",
                            transportPolicyEnvelope =
                                TransportPolicyEnvelope(
                                    policy =
                                        TransportPolicy(
                                            quicMode = QuicMode.HARD_DISABLE,
                                            preferredStack = PreferredStack.H2,
                                            dnsMode = DnsMode.SYSTEM,
                                            tcpFamily = TcpFamily.REC_PRE_SNI,
                                            outcome = DirectModeOutcome.TRANSPARENT_OK,
                                        ),
                                    ipSetDigest = "feedface",
                                    transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                                    reasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                                ),
                            policyConfirmedAt = 50L,
                            policyFailureCount = 0,
                            updatedAt = 50L,
                        ),
                    ),
            ).single()
                .second

        assertNull(persisted.transportPolicy)
        assertEquals("feedface", persisted.ipSetDigest)
        assertEquals(1, persisted.policyFailureCount)
        assertNull(persisted.policyConfirmedAt)
    }

    @Test
    fun `two failed active attempts confirm no direct solution policy`() {
        val persisted =
            buildPersistableDirectPathObservations(
                report =
                    ScanReport(
                        sessionId = "session",
                        profileId = "profile",
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 200L,
                        summary = "summary",
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "strategy_https",
                                    target = "example.org",
                                    outcome = "tls_handshake_failed",
                                    details = listOf(ProbeDetail("targetHost", "example.org")),
                                ),
                                ProbeResult(
                                    probeType = "service_gateway",
                                    target = "example.org",
                                    outcome = "unreachable",
                                    details = listOf(ProbeDetail("targetHost", "example.org")),
                                ),
                            ),
                    ),
                existingRecords = emptyList(),
            ).single()
                .second

        assertNotNull(persisted.transportPolicy)
        assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, persisted.transportPolicy?.outcome)
        assertEquals(200L, persisted.policyConfirmedAt)
        assertEquals(0, persisted.policyFailureCount)
    }
}
