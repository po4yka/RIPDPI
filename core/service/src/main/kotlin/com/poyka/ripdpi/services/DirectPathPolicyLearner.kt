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
import com.poyka.ripdpi.data.NoTcpFallbackAppMemory
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
        private val pendingNoDirectSolutions = linkedMapOf<DirectPathTupleKey, Long>()

        /**
         * Per-app-family NO_TCP_FALLBACK memory. Consulted before a soft-disable
         * is learned and recorded when an attributed signal reports that an app
         * never fell back to TCP. Unattributed signals (no package name) never
         * touch it, so per-app suppression is conservative by default. The mark
         * is version-scoped, so it reverts automatically when the app updates.
         */
        private val noTcpFallbackMemory = NoTcpFallbackAppMemory()

        override suspend fun consume(snapshot: NativeRuntimeSnapshot) {
            // Events this build cannot map to a policy are ignored entirely --
            // not learned, not persisted, never fatal -- so a newer runtime
            // emitting an unrecognized event name stays compatible.
            val knownSignals =
                snapshot.directPathLearningSignals.filter { DirectPathLearningEventRules.isKnown(it.event) }
            if (knownSignals.isEmpty()) {
                return
            }
            val fingerprint = networkFingerprintProvider.capture() ?: return
            val fingerprintHash = fingerprint.scopeKey()
            ensureFingerprintCache(fingerprintHash)

            collapseSignals(knownSignals).forEach { (tupleKey, signal) ->
                val current = cachedEnvelopes[tupleKey]
                if (signal.event == DirectPathLearningEvent.ALL_IPS_FAILED &&
                    !shouldPersistNoDirectSolution(tupleKey, current)
                ) {
                    pendingNoDirectSolutions[tupleKey] = signal.capturedAt
                    trimCaches()
                    return@forEach
                }
                if (signal.event != DirectPathLearningEvent.ALL_IPS_FAILED) {
                    pendingNoDirectSolutions.remove(tupleKey)
                }
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
            pendingNoDirectSolutions.clear()
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

        private fun shouldPersistNoDirectSolution(
            tupleKey: DirectPathTupleKey,
            current: TransportPolicyEnvelope?,
        ): Boolean {
            if (current?.policy?.outcome == DirectModeOutcome.NO_DIRECT_SOLUTION) {
                return true
            }
            return pendingNoDirectSolutions.remove(tupleKey) != null
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
            val rule = DirectPathLearningEventRules.ruleFor(signal.event) ?: return null
            recordNoTcpFallbackIfAttributed(signal)
            val suppressedByNoTcpFallback =
                signal.event == DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK &&
                    (
                        current?.reasonCode == DirectModeReasonCode.NO_TCP_FALLBACK ||
                            shouldSkipSoftDisableForApp(signal)
                    )
            return if (suppressedByNoTcpFallback) {
                null
            } else {
                TransportPolicyEnvelope(
                    policy = rule.buildPolicy(signal, current),
                    ipSetDigest = signal.ipSetDigest.normalizeIpSetDigest(),
                    transportClass = rule.transportClass,
                    reasonCode = rule.reasonCode,
                    cooldownUntil =
                        if (rule.setsCooldown) {
                            signal.capturedAt + DirectModeNoDirectSolutionCooldownMs
                        } else {
                            null
                        },
                )
            }
        }

        /**
         * Record an attributed NO_TCP_FALLBACK observation so the same app is
         * not soft-disabled again. Requires both a package name and a version
         * code; an unattributed signal is left untouched (conservative default).
         */
        private fun recordNoTcpFallbackIfAttributed(signal: DirectPathLearningSignal) {
            if (signal.event != DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED) return
            val packageName = signal.packageName
            val versionCode = signal.appVersionCode
            if (packageName != null && versionCode != null) {
                noTcpFallbackMemory.recordNoTcpFallback(packageName, versionCode)
            }
        }

        /**
         * True when this signal's owning app is remembered as NO_TCP_FALLBACK for
         * its exact current version, meaning a soft-disable must NOT be learned.
         * Unattributed signals always return `false`.
         */
        private fun shouldSkipSoftDisableForApp(signal: DirectPathLearningSignal): Boolean {
            val packageName = signal.packageName
            val versionCode = signal.appVersionCode
            return packageName != null &&
                versionCode != null &&
                noTcpFallbackMemory.shouldSkipSoftDisable(packageName, versionCode)
        }

        private fun trimCaches() {
            while (cachedEnvelopes.size > CacheLimit) {
                val eldest = cachedEnvelopes.entries.firstOrNull()?.key ?: break
                cachedEnvelopes.remove(eldest)
                lastAppliedSignatures.remove(eldest)
            }
            while (pendingNoDirectSolutions.size > CacheLimit) {
                val eldest = pendingNoDirectSolutions.entries.firstOrNull()?.key ?: break
                pendingNoDirectSolutions.remove(eldest)
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
    DirectPathLearningEventRules.ruleFor(event)?.signalOrder ?: -1

/**
 * Central [DirectPathLearningEvent] -> policy mapping for [DirectPathPolicyLearner].
 *
 * Every event the learner acts on has exactly one [Rule] here. An event with no
 * rule is treated as unknown and skipped by the learner. Teaching the learner a
 * new event is therefore a single map entry plus a [DirectPathLearningEvent]
 * companion constant -- no scattered exhaustive `when` expressions to keep in
 * sync across `computeEnvelope` / classification / collapse ordering.
 */
private object DirectPathLearningEventRules {
    private const val SignalOrderAllIpsFailed = 0
    private const val SignalOrderNoTcpFallback = 1
    private const val SignalOrderQuicBlocked = 2
    private const val SignalOrderTcpPostClientHelloFailure = 3
    private const val SignalOrderQuicSuccess = 4

    /** Per-event facts the learner needs: collapse ordering, classification, cooldown, and the policy builder. */
    class Rule(
        val signalOrder: Int,
        val transportClass: DirectTransportClass?,
        val reasonCode: DirectModeReasonCode?,
        val setsCooldown: Boolean,
        val buildPolicy: (signal: DirectPathLearningSignal, current: TransportPolicyEnvelope?) -> TransportPolicy,
    )

    fun ruleFor(event: DirectPathLearningEvent): Rule? = rules[event]

    fun isKnown(event: DirectPathLearningEvent): Boolean = rules.containsKey(event)

    private val rules: Map<DirectPathLearningEvent, Rule> =
        mapOf(
            DirectPathLearningEvent.QUIC_SUCCESS to
                Rule(
                    signalOrder = SignalOrderQuicSuccess,
                    transportClass = null,
                    reasonCode = null,
                    setsCooldown = false,
                    buildPolicy = { _, _ -> transparentDirectPolicy() },
                ),
            DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED to
                Rule(
                    signalOrder = SignalOrderNoTcpFallback,
                    transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                    reasonCode = DirectModeReasonCode.NO_TCP_FALLBACK,
                    setsCooldown = false,
                    buildPolicy = { _, _ -> transparentDirectPolicy() },
                ),
            DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK to
                Rule(
                    signalOrder = SignalOrderQuicBlocked,
                    transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                    reasonCode = DirectModeReasonCode.QUIC_BLOCKED,
                    setsCooldown = false,
                    buildPolicy = { _, _ -> quicBlockedTcpOkPolicy() },
                ),
            DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK to
                Rule(
                    signalOrder = SignalOrderTcpPostClientHelloFailure,
                    transportClass = DirectTransportClass.SNI_TLS_SUSPECT,
                    reasonCode = DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE,
                    setsCooldown = false,
                    buildPolicy = { signal, _ -> tcpPostClientHelloFailurePolicy(signal) },
                ),
            DirectPathLearningEvent.ALL_IPS_FAILED to
                Rule(
                    signalOrder = SignalOrderAllIpsFailed,
                    transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                    reasonCode = DirectModeReasonCode.IP_BLOCKED,
                    setsCooldown = true,
                    buildPolicy = { _, current -> allIpsFailedPolicy(current) },
                ),
        )

    private fun transparentDirectPolicy(): TransportPolicy =
        TransportPolicy(
            quicMode = QuicMode.ALLOW,
            preferredStack = PreferredStack.H3,
            dnsMode = DnsMode.SYSTEM,
            tcpFamily = TcpFamily.NONE,
            outcome = DirectModeOutcome.TRANSPARENT_OK,
        )

    private fun quicBlockedTcpOkPolicy(): TransportPolicy =
        TransportPolicy(
            quicMode = QuicMode.SOFT_DISABLE,
            preferredStack = PreferredStack.H2,
            dnsMode = DnsMode.SYSTEM,
            tcpFamily = TcpFamily.NONE,
            outcome = DirectModeOutcome.TRANSPARENT_OK,
        )

    private fun tcpPostClientHelloFailurePolicy(signal: DirectPathLearningSignal): TransportPolicy =
        TransportPolicy(
            quicMode = QuicMode.HARD_DISABLE,
            preferredStack = PreferredStack.H2,
            dnsMode = DnsMode.SYSTEM,
            tcpFamily = normalizeStrategyFamilyToTcpFamily(signal.strategyFamily),
            outcome = DirectModeOutcome.TRANSPARENT_OK,
        )

    private fun allIpsFailedPolicy(current: TransportPolicyEnvelope?): TransportPolicy =
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

@Module
@InstallIn(ProxyServiceSessionComponent::class, VpnServiceSessionComponent::class)
internal abstract class DirectPathPolicyLearnerModule {
    @Binds
    @ServiceSessionScope
    abstract fun bindDirectPathPolicyTelemetryConsumer(
        learner: DirectPathPolicyLearner,
    ): DirectPathPolicyTelemetryConsumer
}
