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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [FlowAppAttributionStore] double. Tests seed it by the same
 * `ipSetDigest` string the learning signal carries, modelling the Kotlin-side
 * join the tun2socks producer populates in production.
 */
private class FakeFlowAppAttributionStore : FlowAppAttributionStore {
    private val map = mutableMapOf<String, FlowAttribution.Attributed>()

    fun seed(
        ipSetDigest: String,
        packageName: String,
        versionCode: Long,
    ) {
        map[ipSetDigest] = FlowAttribution.Attributed(packageName, versionCode)
    }

    override fun noteFlow(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ) = Unit

    override fun resolveFlowUidOnly(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int = InvalidUid

    override fun lookup(ipSetDigest: String): FlowAttribution.Attributed? = map[ipSetDigest]

    override fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    ) {
        map.entries.removeIf { (_, attribution) ->
            attribution.packageName == packageName && attribution.versionCode != newVersionCode
        }
    }

    override fun clear() = map.clear()
}

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
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
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
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
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
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
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
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
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

    @Test
    fun `no tcp fallback memory skips soft disable for the same app on a different host`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val flowStore = FakeFlowAppAttributionStore()
            // App P owns flows to both hosts (their learning signals carry these digests).
            flowStore.seed("aaaa", "com.app.p", 1L)
            flowStore.seed("bbbb", "com.app.p", 1L)
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = flowStore,
                )

            // App P never falls back to TCP on host A — remember it per app family.
            learner.consumeSignal("host-a.example:443", "aaaa", DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED, 100L)
            // The same app hits a DIFFERENT host: the per-host record for B is empty,
            // but the per-app memory must still suppress soft-disable.
            learner.consumeSignal("host-b.example:443", "bbbb", DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 200L)

            val records = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
            assertNull(records.firstOrNull { it.authority == "host-b.example:443" })
        }

    @Test
    fun `app version change reverts the no tcp fallback memory`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val flowStore = FakeFlowAppAttributionStore()
            flowStore.seed("aaaa", "com.app.p", 1L)
            // Host B is served by app P at a NEWER version.
            flowStore.seed("bbbb", "com.app.p", 2L)
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = flowStore,
                )

            learner.consumeSignal("host-a.example:443", "aaaa", DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED, 100L)
            // App P updated to v2 — the v1 mark no longer applies, so soft-disable is learned.
            learner.consumeSignal("host-b.example:443", "bbbb", DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 200L)

            val record =
                store
                    .directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
                    .single { it.authority == "host-b.example:443" }
            assertEquals(QuicMode.SOFT_DISABLE, record.effectiveTransportPolicyEnvelope().policy.quicMode)
        }

    @Test
    fun `no tcp fallback memory does not skip soft disable for a different app`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val flowStore = FakeFlowAppAttributionStore()
            flowStore.seed("aaaa", "com.app.p", 1L)
            // Host B is owned by a DIFFERENT app.
            flowStore.seed("bbbb", "com.app.q", 1L)
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = flowStore,
                )

            learner.consumeSignal("host-a.example:443", "aaaa", DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED, 100L)
            learner.consumeSignal("host-b.example:443", "bbbb", DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 200L)

            val record =
                store
                    .directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
                    .single { it.authority == "host-b.example:443" }
            assertEquals(QuicMode.SOFT_DISABLE, record.effectiveTransportPolicyEnvelope().policy.quicMode)
        }

    @Test
    fun `unattributed flow does not arm the per app memory`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            // Empty store: no flow is attributed — conservative default.
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
                )

            learner.consumeSignal("host-a.example:443", "aaaa", DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED, 100L)
            learner.consumeSignal("host-b.example:443", "bbbb", DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 200L)

            val record =
                store
                    .directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
                    .single { it.authority == "host-b.example:443" }
            assertEquals(QuicMode.SOFT_DISABLE, record.effectiveTransportPolicyEnvelope().policy.quicMode)
        }

    @Test
    fun `soft disable is scoped to its host tuple and leaves other hosts unaffected`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
                )

            learner.consumeSignal("host-a.example:443", "aaaa", DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK, 100L)
            learner.consumeSignal("host-b.example:443", "bbbb", DirectPathLearningEvent.QUIC_SUCCESS, 200L)

            val records = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey())
            val hostA = records.single { it.authority == "host-a.example:443" }
            val hostB = records.single { it.authority == "host-b.example:443" }
            assertEquals(QuicMode.SOFT_DISABLE, hostA.effectiveTransportPolicyEnvelope().policy.quicMode)
            assertEquals(QuicMode.ALLOW, hostB.effectiveTransportPolicyEnvelope().policy.quicMode)
        }

    @Test
    fun `unknown direct path learning event is ignored and persists nothing`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
                )

            learner.consume(
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    directPathLearningSignals =
                        listOf(
                            DirectPathLearningSignal(
                                authority = "example.org:443",
                                ipSetDigest = "deadbeef",
                                event = DirectPathLearningEvent("FUTURE_DIRECT_PATH_EVENT_V2"),
                                capturedAt = 100L,
                            ),
                        ),
                ),
            )

            assertTrue(store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).isEmpty())
        }

    @Test
    fun `unknown event alongside a known event leaves the known event learned`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val store = TestServerCapabilityStore()
            val learner =
                DirectPathPolicyLearner(
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = store,
                    flowAppAttributionStore = FakeFlowAppAttributionStore(),
                )

            learner.consume(
                NativeRuntimeSnapshot(
                    source = "proxy",
                    state = "running",
                    directPathLearningSignals =
                        listOf(
                            DirectPathLearningSignal(
                                authority = "unknown.example:443",
                                ipSetDigest = "cafef00d",
                                event = DirectPathLearningEvent("FUTURE_DIRECT_PATH_EVENT_V2"),
                                capturedAt = 100L,
                            ),
                            DirectPathLearningSignal(
                                authority = "Example.org:443",
                                ipSetDigest = "deadbeef",
                                event = DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
                                capturedAt = 200L,
                            ),
                        ),
                ),
            )

            // The unknown event is dropped; the known event learns exactly as
            // it would have without the unknown sibling present.
            val record = store.directPathCapabilitiesForFingerprint(fingerprint.scopeKey()).single()
            val envelope = record.effectiveTransportPolicyEnvelope()
            assertEquals("example.org:443", record.authority)
            assertEquals("deadbeef", envelope.ipSetDigest)
            assertEquals(QuicMode.SOFT_DISABLE, envelope.policy.quicMode)
            assertEquals(DirectModeReasonCode.QUIC_BLOCKED, envelope.reasonCode)
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
