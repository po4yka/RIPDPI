package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 passive-observation unit tests: the observer must derive a typed
 * [PassiveObservation] from the last failed flow, stay free on success paths,
 * and be consumed by Phase 1 / Phase 2 classification.
 */
class PassiveObservationTest {
    private val observer = PassiveObserver()

    @Test
    fun `null flow yields the NONE sentinel with no work`() {
        val observation = observer.observe(null)

        assertSame(PassiveObservation.NONE, observation)
        assertFalse(observation.observed)
        assertEquals(FailPhase.NONE, observation.failPhase)
        assertEquals(ErrorPageShape.NONE, observation.errorPageShape)
    }

    @Test
    fun `failure before TCP completes is classified PRE_CLIENT_HELLO`() {
        val observation =
            observer.observe(
                LastFailedFlow(tcpHandshakeCompleted = false),
            )

        assertTrue(observation.observed)
        assertEquals(FailPhase.PRE_CLIENT_HELLO, observation.failPhase)
    }

    @Test
    fun `failure after ClientHello is classified POST_CLIENT_HELLO`() {
        val observation =
            observer.observe(
                LastFailedFlow(tcpHandshakeCompleted = true, failedAfterClientHello = true),
            )

        assertEquals(FailPhase.POST_CLIENT_HELLO, observation.failPhase)
    }

    @Test
    fun `udp443 failing while tcp works flags the QUIC-block signal`() {
        val observation =
            observer.observe(
                LastFailedFlow(
                    tcpHandshakeCompleted = true,
                    udp443Failed = true,
                    tcpToSameHostSucceeded = true,
                ),
            )

        assertTrue(observation.udp443FailedWhileTcpWorked)
    }

    @Test
    fun `udp443 failure without working tcp does not flag the QUIC-block signal`() {
        val observation =
            observer.observe(
                LastFailedFlow(udp443Failed = true, tcpToSameHostSucceeded = false),
            )

        assertFalse(observation.udp443FailedWhileTcpWorked)
    }

    @Test
    fun `certificate mismatch dominates the error-page shape`() {
        val observation =
            observer.observe(
                LastFailedFlow(
                    tlsCertificateMismatch = true,
                    responseBodySnippet = "access denied",
                ),
            )

        assertEquals(ErrorPageShape.TLS_CERT_MISMATCH, observation.errorPageShape)
    }

    @Test
    fun `known block phrase is detected case-insensitively`() {
        val observation =
            observer.observe(
                LastFailedFlow(responseBodySnippet = "ACCESS DENIED by network policy"),
            )

        assertEquals(ErrorPageShape.KNOWN_BLOCK_PATTERN, observation.errorPageShape)
    }

    @Test
    fun `middlebox html interstitial is detected`() {
        val observation =
            observer.observe(
                LastFailedFlow(
                    responseBodySnippet = "<html><body>this page is blocked by your firewall</body></html>",
                ),
            )

        assertEquals(ErrorPageShape.MIDDLEBOX_BLOCK_HTML, observation.errorPageShape)
    }

    @Test
    fun `451 with a tiny body is a legal-block stub size anomaly`() {
        val observation =
            observer.observe(
                LastFailedFlow(responseStatusCode = 451, responseBodyBytes = 120),
            )

        assertEquals(ErrorPageShape.SIZE_ANOMALY, observation.errorPageShape)
    }

    @Test
    fun `200 with a normal body has no error-page shape`() {
        val observation =
            observer.observe(
                LastFailedFlow(responseStatusCode = 200, responseBodyBytes = 40_000),
            )

        assertEquals(ErrorPageShape.NONE, observation.errorPageShape)
    }

    @Test
    fun `dns classification is carried through unchanged`() {
        val observation =
            observer.observe(
                LastFailedFlow(dnsClassification = DnsClassification.POISONED),
            )

        assertEquals(DnsClassification.POISONED, observation.dnsClassification)
    }

    @Test
    fun `orchestrator feeds the passive observation into phase 1 and phase 2`() =
        runTest {
            val dns = StubDnsClassifier(DnsClassification.CLEAN)
            val transport = StubTransportClassifier(TransportClass.QuicBlockSuspect)
            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = dns,
                    transportClassifier = transport,
                    armRanker = IdentityArmRanker(),
                    armExecutor = NeverSucceedExecutor(),
                    ownedStackPinGuard = ConfirmedPinGuard(),
                    lastFailedFlow =
                        LastFailedFlow(
                            udp443Failed = true,
                            tcpToSameHostSucceeded = true,
                        ),
                )

            orchestrator.run()

            assertTrue("phase 1 must receive the observation", dns.lastObservation?.observed == true)
            assertTrue(
                "phase 2 must receive the QUIC-block signal",
                transport.lastObservation?.udp443FailedWhileTcpWorked == true,
            )
        }

    @Test
    fun `orchestrator passes NONE on the success path with no failed flow`() =
        runTest {
            val dns = StubDnsClassifier(DnsClassification.CLEAN)
            val orchestrator =
                DirectModeOrchestrator(
                    dnsClassifier = dns,
                    transportClassifier = StubTransportClassifier(TransportClass.Transparent),
                    armRanker = IdentityArmRanker(),
                    armExecutor = AlwaysSucceedExecutor(),
                    ownedStackPinGuard = ConfirmedPinGuard(),
                )

            orchestrator.run()

            assertSame(PassiveObservation.NONE, dns.lastObservation)
        }
}
