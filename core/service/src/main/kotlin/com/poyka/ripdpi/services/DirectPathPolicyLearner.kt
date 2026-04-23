package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DirectModeNoDirectSolutionCooldownMs
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectPathLearningEvent
import com.poyka.ripdpi.data.DirectPathLearningSignal
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
import com.poyka.ripdpi.data.normalizeStrategyFamilyToTcpFamily
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import javax.inject.Inject

internal interface DirectPathPolicyTelemetryConsumer {
    suspend fun consume(snapshot: NativeRuntimeSnapshot)
}

internal object NoOpDirectPathPolicyTelemetryConsumer : DirectPathPolicyTelemetryConsumer {
    override suspend fun consume(snapshot: NativeRuntimeSnapshot) = Unit
}

@ServiceSessionScope
internal class DirectPathPolicyLearner
    @Inject
    constructor(
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
    ) : DirectPathPolicyTelemetryConsumer {
        private var cachedFingerprintHash: String? = null
        private val cachedEnvelopes = linkedMapOf<DirectPathTupleKey, TransportPolicyEnvelope>()
        private val lastAppliedSignatures = linkedMapOf<DirectPathTupleKey, String>()

        override suspend fun consume(snapshot: NativeRuntimeSnapshot) {
            if (snapshot.directPathLearningSignals.isEmpty()) {
                return
            }
            val fingerprint = networkFingerprintProvider.capture() ?: return
            val fingerprintHash = fingerprint.scopeKey()
            ensureFingerprintCache(fingerprintHash)

            collapseSignals(snapshot.directPathLearningSignals).forEach { (tupleKey, signal) ->
                val current = cachedEnvelopes[tupleKey]
                val next = computeEnvelope(signal, current) ?: return@forEach
                val signature = envelopeSignature(next)
                if (current == next || lastAppliedSignatures[tupleKey] == signature) {
                    return@forEach
                }
                val stored =
                    serverCapabilityStore.rememberDirectPathObservation(
                        fingerprint = fingerprint,
                        authority = tupleKey.authority,
                        observation =
                            ServerCapabilityObservation(
                                transportPolicy = next.policy,
                                ipSetDigest = next.ipSetDigest,
                                transportClass = next.transportClass,
                                reasonCode = next.reasonCode,
                                cooldownUntil = next.cooldownUntil,
                            ),
                        source = "runtime_learning",
                        recordedAt = signal.capturedAt.takeIf { it > 0L },
                    )
                val effective = stored.effectiveTransportPolicyEnvelope()
                cachedEnvelopes[tupleKey] = effective
                lastAppliedSignatures[tupleKey] = envelopeSignature(effective)
                trimCaches()
            }
        }

        private suspend fun ensureFingerprintCache(fingerprintHash: String) {
            if (cachedFingerprintHash == fingerprintHash) {
                return
            }
            cachedFingerprintHash = fingerprintHash
            cachedEnvelopes.clear()
            lastAppliedSignatures.clear()
            serverCapabilityStore
                .directPathCapabilitiesForFingerprint(fingerprintHash)
                .forEach { record ->
                    val tupleKey =
                        DirectPathTupleKey(
                            authority = normalizeAuthority(record.authority),
                            ipSetDigest = record.effectiveTransportPolicyEnvelope().ipSetDigest.normalizeIpSetDigest(),
                        )
                    cachedEnvelopes[tupleKey] = record.effectiveTransportPolicyEnvelope()
                    lastAppliedSignatures[tupleKey] = envelopeSignature(record.effectiveTransportPolicyEnvelope())
                }
        }

        private fun collapseSignals(
            signals: List<DirectPathLearningSignal>,
        ): List<Pair<DirectPathTupleKey, DirectPathLearningSignal>> =
            signals
                .map { signal ->
                    DirectPathTupleKey(
                        authority = normalizeAuthority(signal.authority),
                        ipSetDigest = signal.ipSetDigest.normalizeIpSetDigest(),
                    ) to signal
                }.filter { (tupleKey, _) -> tupleKey.authority.isNotEmpty() }
                .groupBy({ it.first }, { it.second })
                .mapNotNull { (tupleKey, groupedSignals) ->
                    groupedSignals
                        .maxWithOrNull(
                            compareBy<DirectPathLearningSignal> { it.capturedAt }.thenBy { signalOrder(it.event) },
                        )?.let { tupleKey to it }
                }

        private fun computeEnvelope(
            signal: DirectPathLearningSignal,
            current: TransportPolicyEnvelope?,
        ): TransportPolicyEnvelope? {
            if (signal.event == DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK &&
                current?.reasonCode == DirectModeReasonCode.NO_TCP_FALLBACK
            ) {
                return null
            }

            val policy =
                when (signal.event) {
                    DirectPathLearningEvent.QUIC_SUCCESS,
                    DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED,
                    -> {
                        TransportPolicy(
                            quicMode = QuicMode.ALLOW,
                            preferredStack = PreferredStack.H3,
                            dnsMode = DnsMode.SYSTEM,
                            tcpFamily = TcpFamily.NONE,
                            outcome = DirectModeOutcome.TRANSPARENT_OK,
                        )
                    }

                    DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK -> {
                        TransportPolicy(
                            quicMode = QuicMode.SOFT_DISABLE,
                            preferredStack = PreferredStack.H2,
                            dnsMode = DnsMode.SYSTEM,
                            tcpFamily = TcpFamily.NONE,
                            outcome = DirectModeOutcome.TRANSPARENT_OK,
                        )
                    }

                    DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK -> {
                        TransportPolicy(
                            quicMode = QuicMode.HARD_DISABLE,
                            preferredStack = PreferredStack.H2,
                            dnsMode = DnsMode.SYSTEM,
                            tcpFamily = normalizeStrategyFamilyToTcpFamily(signal.strategyFamily),
                            outcome = DirectModeOutcome.TRANSPARENT_OK,
                        )
                    }

                    DirectPathLearningEvent.ALL_IPS_FAILED -> {
                        TransportPolicy(
                            quicMode =
                                when (current?.transportClass) {
                                    DirectTransportClass.SNI_TLS_SUSPECT -> QuicMode.HARD_DISABLE
                                    DirectTransportClass.QUIC_BLOCK_SUSPECT -> QuicMode.SOFT_DISABLE
                                    else -> QuicMode.ALLOW
                                },
                            preferredStack = PreferredStack.H2,
                            dnsMode = DnsMode.SYSTEM,
                            tcpFamily = current?.policy?.tcpFamily ?: TcpFamily.NONE,
                            outcome = DirectModeOutcome.NO_DIRECT_SOLUTION,
                        )
                    }
                }

            val transportClass =
                when (signal.event) {
                    DirectPathLearningEvent.QUIC_SUCCESS -> null

                    DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
                    DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED,
                    -> DirectTransportClass.QUIC_BLOCK_SUSPECT

                    DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK -> DirectTransportClass.SNI_TLS_SUSPECT

                    DirectPathLearningEvent.ALL_IPS_FAILED -> DirectTransportClass.IP_BLOCK_SUSPECT
                }

            val reasonCode =
                when (signal.event) {
                    DirectPathLearningEvent.QUIC_SUCCESS -> {
                        null
                    }

                    DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK -> {
                        DirectModeReasonCode.QUIC_BLOCKED
                    }

                    DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK -> {
                        DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
                    }

                    DirectPathLearningEvent.ALL_IPS_FAILED -> {
                        DirectModeReasonCode.IP_BLOCKED
                    }

                    DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED -> {
                        DirectModeReasonCode.NO_TCP_FALLBACK
                    }
                }

            return TransportPolicyEnvelope(
                policy = policy,
                ipSetDigest = signal.ipSetDigest.normalizeIpSetDigest(),
                transportClass = transportClass,
                reasonCode = reasonCode,
                cooldownUntil =
                    if (signal.event == DirectPathLearningEvent.ALL_IPS_FAILED) {
                        signal.capturedAt + DirectModeNoDirectSolutionCooldownMs
                    } else {
                        null
                    },
            )
        }

        private fun trimCaches() {
            while (cachedEnvelopes.size > CacheLimit) {
                val eldest = cachedEnvelopes.entries.firstOrNull()?.key ?: break
                cachedEnvelopes.remove(eldest)
                lastAppliedSignatures.remove(eldest)
            }
        }
    }

private const val CacheLimit = 256

private data class DirectPathTupleKey(
    val authority: String,
    val ipSetDigest: String,
)

private fun normalizeAuthority(authority: String): String = authority.trim().trimEnd('.').lowercase()

private fun String.normalizeIpSetDigest(): String = trim().lowercase()

private fun envelopeSignature(envelope: TransportPolicyEnvelope): String =
    buildString {
        append(envelope.policy.quicMode)
        append('|')
        append(envelope.policy.preferredStack)
        append('|')
        append(envelope.policy.dnsMode)
        append('|')
        append(envelope.policy.tcpFamily)
        append('|')
        append(envelope.policy.outcome)
        append('|')
        append(envelope.ipSetDigest)
        append('|')
        append(envelope.transportClass)
        append('|')
        append(envelope.reasonCode)
        append('|')
        append(envelope.cooldownUntil)
    }

private fun signalOrder(event: DirectPathLearningEvent): Int =
    when (event) {
        DirectPathLearningEvent.ALL_IPS_FAILED -> 0
        DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED -> 1
        DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK -> 2
        DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK -> 3
        DirectPathLearningEvent.QUIC_SUCCESS -> 4
    }

@Module
@InstallIn(ProxyServiceSessionComponent::class, VpnServiceSessionComponent::class)
internal abstract class DirectPathPolicyLearnerModule {
    @Binds
    @ServiceSessionScope
    abstract fun bindDirectPathPolicyTelemetryConsumer(
        learner: DirectPathPolicyLearner,
    ): DirectPathPolicyTelemetryConsumer
}
