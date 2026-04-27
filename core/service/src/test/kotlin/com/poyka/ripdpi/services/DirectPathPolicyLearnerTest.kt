package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectModeNoDirectSolutionCooldownMs
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectPathLearningEvent
import com.poyka.ripdpi.data.DirectPathLearningSignal
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPathPolicyLearnerTest {
    @Test
    fun `quic blocked tcp ok persists soft disable and duplicate poll does not rewrite`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                )
            val firstSnapshot =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    directPathLearningSignals =
                        listOf(
                            DirectPathLearningSignal(
                                authority = "Example.org:443",
                                ipSetDigest = "deadbeef",
                                event = DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
                                capturedAt = 100L,
                            ),
                        ),
                )

            learner.consume(firstSnapshot)
            learner.consume(firstSnapshot.copy(directPathLearningSignals = firstSnapshot.directPathLearningSignals))

            val record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            val envelope = record.effectiveTransportPolicyEnvelope()

            assertEquals(100L, record.updatedAt)
            assertEquals("example.org:443", record.authority)
            assertEquals("deadbeef", envelope.ipSetDigest)
            assertEquals(QuicMode.SOFT_DISABLE, envelope.policy.quicMode)
            assertEquals(PreferredStack.H2, envelope.policy.preferredStack)
            assertEquals(TcpFamily.NONE, envelope.policy.tcpFamily)
            assertEquals(DirectModeOutcome.TRANSPARENT_OK, envelope.policy.outcome)
            assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, envelope.transportClass)
            assertEquals(DirectModeReasonCode.QUIC_BLOCKED, envelope.reasonCode)
            assertNull(envelope.cooldownUntil)
        }

    @Test
    fun `no tcp fallback blocks soft disable relearning until quic success`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                )
            val tupleAuthority = "example.org:443"
            val ipSetDigest = "deadbeef"

            learner.consumeSignal(tupleAuthority, ipSetDigest, DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED, 100L)
            learner.consumeSignal(tupleAuthority, ipSetDigest, DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 200L)

            var record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            var envelope = record.effectiveTransportPolicyEnvelope()
            assertEquals(100L, record.updatedAt)
            assertEquals(QuicMode.ALLOW, envelope.policy.quicMode)
            assertEquals(DirectModeReasonCode.NO_TCP_FALLBACK, envelope.reasonCode)

            learner.consumeSignal(tupleAuthority, ipSetDigest, DirectPathLearningEvent.QUIC_SUCCESS, 300L)
            learner.consumeSignal(tupleAuthority, ipSetDigest, DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 400L)

            record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            envelope = record.effectiveTransportPolicyEnvelope()
            assertEquals(400L, record.updatedAt)
            assertEquals(QuicMode.SOFT_DISABLE, envelope.policy.quicMode)
            assertEquals(DirectModeReasonCode.QUIC_BLOCKED, envelope.reasonCode)
        }

    @Test
    fun `all ips failed requires revalidation before persisting no direct solution`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                )
            val authority = "example.org:443"
            val digest = "feedface"

            learner.consume(
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    directPathLearningSignals =
                        listOf(
                            DirectPathLearningSignal(
                                authority = authority,
                                ipSetDigest = digest,
                                event = DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
                                strategyFamily = "tlsrec_disorder",
                                capturedAt = 100L,
                            ),
                        ),
                ),
            )
            learner.consume(
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    directPathLearningSignals =
                        listOf(
                            DirectPathLearningSignal(
                                authority = authority,
                                ipSetDigest = digest,
                                event = DirectPathLearningEvent.ALL_IPS_FAILED,
                                capturedAt = 200L,
                            ),
                        ),
                ),
            )

            val record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            val envelope = record.effectiveTransportPolicyEnvelope()

            assertEquals(100L, record.updatedAt)
            assertEquals(DirectModeOutcome.TRANSPARENT_OK, envelope.policy.outcome)
            assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, envelope.transportClass)
            assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, envelope.reasonCode)
            assertNull(envelope.cooldownUntil)
        }

    @Test
    fun `verified all ips failed sets cooldown and later quic success clears negative verdict`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                )
            val authority = "example.org:443"
            val digest = "feedface"

            learner.consumeSignal(
                authority,
                digest,
                DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
                100L,
                strategyFamily = "tlsrec_disorder",
            )
            learner.consumeSignal(authority, digest, DirectPathLearningEvent.ALL_IPS_FAILED, 200L)
            learner.consumeSignal(authority, digest, DirectPathLearningEvent.ALL_IPS_FAILED, 250L)

            var record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            var envelope = record.effectiveTransportPolicyEnvelope()
            assertEquals(QuicMode.HARD_DISABLE, envelope.policy.quicMode)
            assertEquals(TcpFamily.REC_PRE_SNI, envelope.policy.tcpFamily)
            assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, envelope.policy.outcome)
            assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, envelope.transportClass)
            assertEquals(DirectModeReasonCode.IP_BLOCKED, envelope.reasonCode)
            assertEquals(250L + DirectModeNoDirectSolutionCooldownMs, envelope.cooldownUntil)

            learner.consumeSignal(authority, digest, DirectPathLearningEvent.QUIC_SUCCESS, 300L)

            record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            envelope = record.effectiveTransportPolicyEnvelope()
            assertEquals(300L, record.updatedAt)
            assertEquals(QuicMode.ALLOW, envelope.policy.quicMode)
            assertEquals(PreferredStack.H3, envelope.policy.preferredStack)
            assertEquals(TcpFamily.NONE, envelope.policy.tcpFamily)
            assertEquals(DirectModeOutcome.TRANSPARENT_OK, envelope.policy.outcome)
            assertNull(envelope.transportClass)
            assertNull(envelope.reasonCode)
            assertNull(envelope.cooldownUntil)
        }
}

private suspend fun DirectPathPolicyLearner.consumeSignal(
    authority: String,
    ipSetDigest: String,
    event: DirectPathLearningEvent,
    capturedAt: Long,
    strategyFamily: String? = null,
) {
    consume(
        NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            directPathLearningSignals =
                listOf(
                    DirectPathLearningSignal(
                        authority = authority,
                        ipSetDigest = ipSetDigest,
                        event = event,
                        capturedAt = capturedAt,
                        strategyFamily = strategyFamily,
                    ),
                ),
        ),
    )
}
