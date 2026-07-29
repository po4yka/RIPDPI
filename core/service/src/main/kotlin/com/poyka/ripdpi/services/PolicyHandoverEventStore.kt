package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.PolicyHandoverDeliveryDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.diagnostics.TerminalPolicyDependencyDurableStatePrefix
import com.poyka.ripdpi.data.policyHandoverDeliveryId
import com.poyka.ripdpi.serialization.RipDpiContractJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPolicyHandoverEventStore
    @Inject
    constructor(
        private val durableStateStore: DiagnosticsDurableStateStore,
        private val rememberedPolicyRecordStore: RememberedNetworkPolicyRecordStore,
    ) : PolicyHandoverEventStore {
        private val publicationMutex = Mutex()

        override val events: Flow<PolicyHandoverEvent> =
            durableStateStore
                .observeDurableStateByPrefix(PolicyHandoverDeliveryDurableStatePrefix)
                .transform { states ->
                    val minimumUpdatedAt = System.currentTimeMillis() - PolicyHandoverRetentionMaxAgeMs
                    val retainedKeys =
                        states
                            .asSequence()
                            .filter { state -> state.updatedAt >= minimumUpdatedAt }
                            .sortedWith(
                                compareByDescending<DiagnosticsDurableStateEntity> { it.updatedAt }
                                    .thenByDescending { it.key },
                            ).take(PolicyHandoverRetentionLimit)
                            .mapTo(mutableSetOf()) { it.key }
                    states.forEach { state ->
                        val event =
                            if (state.key in retainedKeys) {
                                decodeEvent(state)
                            } else {
                                null
                            }
                        if (event == null) {
                            clearDelivery(state)
                        } else {
                            emit(event)
                        }
                    }
                }

        override suspend fun publish(event: PolicyHandoverEvent) =
            publicationMutex.withLock {
                require(event.deliveryId.isNotBlank()) { "Policy handover delivery id is required" }
                require(event.currentFingerprintHash.isNotBlank()) { "Policy handover fingerprint is required" }
                val envelope = createEnvelope(event)
                val now = System.currentTimeMillis()
                durableStateStore.upsertDurableState(
                    DiagnosticsDurableStateEntity(
                        key = deliveryKey(event.deliveryId),
                        value = PolicyHandoverJson.encodeToString(envelope),
                        updatedAt = now,
                    ),
                )
                pruneDeliveries(now)
            }

        override suspend fun acknowledge(deliveryId: String) {
            val key = deliveryKey(deliveryId)
            val current = durableStateStore.getDurableState(key)
            if (current != null) {
                val dependency = validatedDependencyForRemoval(current)
                if (dependency != null) {
                    durableStateStore.clearDurableStateAndDependencyIfCurrent(
                        key = key,
                        expectedValue = current.value,
                        dependencyKey = dependency.key,
                        expectedDependencyValue = dependency.value,
                    )
                } else {
                    durableStateStore.clearDurableStateIfCurrent(key, current.value)
                }
            }
        }

        override suspend fun isPending(deliveryId: String): Boolean {
            val state = durableStateStore.getDurableState(deliveryKey(deliveryId))
            val event =
                state
                    ?.takeIf { it.updatedAt >= System.currentTimeMillis() - PolicyHandoverRetentionMaxAgeMs }
                    ?.let { decodeEvent(it) }
            if (state != null && event == null) clearDelivery(state)
            return event?.deliveryId == deliveryId
        }

        private suspend fun createEnvelope(event: PolicyHandoverEvent): PolicyHandoverDeliveryEnvelope {
            val dependencyKey = event.rememberedPolicyDependencyKey
            if (dependencyKey != null) {
                val policy =
                    resolvePolicyDependency(
                        dependencyKey,
                        event.mode,
                    ) ?: error("Missing handover policy dependency")
                require(policy.fingerprintHash == event.currentFingerprintHash) {
                    "Handover policy dependency fingerprint mismatch"
                }
            }
            return PolicyHandoverDeliveryEnvelope(
                deliveryId = event.deliveryId,
                mode = event.mode,
                previousFingerprintHash = event.previousFingerprintHash.takeIf { dependencyKey == null },
                currentFingerprintHash = event.currentFingerprintHash.takeIf { dependencyKey == null },
                classification = event.classification,
                currentNetworkValidated = event.currentNetworkValidated,
                currentCaptivePortalDetected = event.currentCaptivePortalDetected,
                usedRememberedPolicy = event.usedRememberedPolicy,
                occurredAt = event.occurredAt,
                rememberedPolicyDependencyKey = dependencyKey,
            )
        }

        private suspend fun decodeEvent(state: DiagnosticsDurableStateEntity): PolicyHandoverEvent? {
            val currentEnvelope = decodeCurrentEnvelope(state.value)
            val event =
                if (currentEnvelope != null) {
                    currentEnvelope.toEvent()
                } else {
                    decodeLegacyEvent(state.value)
                }
            return event?.takeIf { candidate ->
                candidate.deliveryId.isNotBlank() &&
                    candidate.currentFingerprintHash.isNotBlank() &&
                    state.key == deliveryKey(candidate.deliveryId)
            }
        }

        private suspend fun PolicyHandoverDeliveryEnvelope.toEvent(): PolicyHandoverEvent? {
            val fingerprint =
                when {
                    schemaVersion != PolicyHandoverDeliverySchemaVersion -> {
                        null
                    }

                    rememberedPolicyDependencyKey != null &&
                        (previousFingerprintHash != null || currentFingerprintHash != null) -> {
                        null
                    }

                    rememberedPolicyDependencyKey != null -> {
                        resolvePolicyDependency(rememberedPolicyDependencyKey, mode)?.fingerprintHash
                    }

                    else -> {
                        currentFingerprintHash
                    }
                }
            return fingerprint?.let { resolvedFingerprint ->
                PolicyHandoverEvent(
                    deliveryId = deliveryId,
                    mode = mode,
                    previousFingerprintHash = previousFingerprintHash,
                    currentFingerprintHash = resolvedFingerprint,
                    classification = classification,
                    currentNetworkValidated = currentNetworkValidated,
                    currentCaptivePortalDetected = currentCaptivePortalDetected,
                    usedRememberedPolicy = usedRememberedPolicy,
                    occurredAt = occurredAt,
                    rememberedPolicyDependencyKey = rememberedPolicyDependencyKey,
                )
            }
        }

        private suspend fun resolvePolicyDependency(
            dependencyKey: String,
            mode: com.poyka.ripdpi.data.Mode,
        ): RememberedNetworkPolicyEntity? {
            val policyId =
                policyDependencySessionId(dependencyKey)
                    ?.let { durableStateStore.getDurableState(dependencyKey)?.value?.toLongOrNull() }
            val policy =
                if (policyId != null) {
                    rememberedPolicyRecordStore.getRememberedNetworkPolicyById(policyId)
                } else {
                    null
                }
            return policy
                ?.takeIf { policy -> policy.mode == mode.preferenceValue }
        }

        private suspend fun clearDelivery(state: DiagnosticsDurableStateEntity) {
            val dependency = validatedDependencyForRemoval(state)
            if (dependency != null) {
                durableStateStore.clearDurableStateAndDependencyIfCurrent(
                    key = state.key,
                    expectedValue = state.value,
                    dependencyKey = dependency.key,
                    expectedDependencyValue = dependency.value,
                )
            } else {
                durableStateStore.clearDurableStateIfCurrent(state.key, state.value)
            }
        }

        private suspend fun validatedDependencyForRemoval(
            state: DiagnosticsDurableStateEntity,
        ): DiagnosticsDurableStateEntity? {
            val envelope = decodeCurrentEnvelope(state.value)
            return envelope?.validatedDependencyKey(state.key)?.let { dependencyKey ->
                val dependency = durableStateStore.getDurableState(dependencyKey)
                val policyId = dependency?.value?.toLongOrNull()
                val policy = policyId?.let { rememberedPolicyRecordStore.getRememberedNetworkPolicyById(it) }
                dependency?.takeIf { policy?.mode == envelope.mode.preferenceValue }
            }
        }

        private suspend fun pruneDeliveries(now: Long) {
            val states =
                durableStateStore
                    .observeDurableStateByPrefix(PolicyHandoverDeliveryDurableStatePrefix)
                    .first()
            val retainedKeys =
                states
                    .asSequence()
                    .filter { state -> state.updatedAt >= now - PolicyHandoverRetentionMaxAgeMs }
                    .sortedWith(
                        compareByDescending<DiagnosticsDurableStateEntity> { state -> state.updatedAt }
                            .thenByDescending { state -> state.key },
                    ).take(PolicyHandoverRetentionLimit)
                    .mapTo(mutableSetOf()) { state -> state.key }
            states
                .filterNot { state -> state.key in retainedKeys }
                .forEach { state -> clearDelivery(state) }
        }

        private fun decodeCurrentEnvelope(value: String): PolicyHandoverDeliveryEnvelope? =
            runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverDeliveryEnvelope>(value) }.getOrNull()

        private fun decodeLegacyEvent(value: String): PolicyHandoverEvent? {
            val envelope =
                runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverDeliveryEnvelopeV2>(value) }.getOrNull()
            return if (envelope != null) {
                envelope.event.takeIf { envelope.schemaVersion == 2 }
            } else {
                runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverEvent>(value) }.getOrNull()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PolicyHandoverEventStoreModule {
    @Binds
    @Singleton
    abstract fun bindPolicyHandoverEventStore(store: DefaultPolicyHandoverEventStore): PolicyHandoverEventStore
}

private fun deliveryKey(deliveryId: String): String = "$PolicyHandoverDeliveryDurableStatePrefix$deliveryId"

private fun PolicyHandoverDeliveryEnvelope.validatedDependencyKey(stateKey: String): String? {
    val dependencyKey = rememberedPolicyDependencyKey
    val sessionId = dependencyKey?.let(::policyDependencySessionId)
    val expectedDeliveryId = sessionId?.let { policyHandoverDeliveryId("runtime-terminal:$it") }
    val isConsistent =
        schemaVersion == PolicyHandoverDeliverySchemaVersion &&
            expectedDeliveryId != null &&
            this.deliveryId == expectedDeliveryId &&
            stateKey == deliveryKey(expectedDeliveryId) &&
            previousFingerprintHash == null &&
            currentFingerprintHash == null &&
            usedRememberedPolicy
    return dependencyKey?.takeIf { isConsistent }
}

private fun policyDependencySessionId(dependencyKey: String): String? {
    if (!dependencyKey.startsWith(TerminalPolicyDependencyDurableStatePrefix)) return null
    val sessionId = dependencyKey.substring(TerminalPolicyDependencyDurableStatePrefix.length)
    val canonicalSessionId = runCatching { UUID.fromString(sessionId).toString() }.getOrNull()
    return sessionId.takeIf { it == canonicalSessionId }
}

@Serializable
private data class PolicyHandoverDeliveryEnvelope(
    val schemaVersion: Int = PolicyHandoverDeliverySchemaVersion,
    val deliveryId: String,
    val mode: com.poyka.ripdpi.data.Mode,
    val previousFingerprintHash: String? = null,
    val currentFingerprintHash: String? = null,
    val classification: String,
    val currentNetworkValidated: Boolean,
    val currentCaptivePortalDetected: Boolean,
    val usedRememberedPolicy: Boolean,
    val occurredAt: Long,
    val rememberedPolicyDependencyKey: String? = null,
)

@Serializable
private data class PolicyHandoverDeliveryEnvelopeV2(
    val schemaVersion: Int = 2,
    val event: PolicyHandoverEvent,
)

private const val PolicyHandoverDeliverySchemaVersion = 3
private const val PolicyHandoverRetentionLimit = 64
private const val PolicyHandoverRetentionMaxAgeMs = 7L * 24L * 60L * 60L * 1_000L
private val PolicyHandoverJson = RipDpiContractJson
