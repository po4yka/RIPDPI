package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import com.poyka.ripdpi.data.diagnostics.PolicyHandoverDeliveryDurableStatePrefix
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPolicyHandoverEventStore
    @Inject
    constructor(
        private val durableStateStore: DiagnosticsDurableStateStore,
    ) : PolicyHandoverEventStore {
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
                        val event = state.takeIf { it.key in retainedKeys }?.let(::decodeEvent)
                        if (event == null) {
                            durableStateStore.clearDurableStateIfCurrent(state.key, state.value)
                        } else {
                            emit(event)
                        }
                    }
                }

        override suspend fun publish(event: PolicyHandoverEvent) {
            require(event.deliveryId.isNotBlank()) { "Policy handover delivery id is required" }
            require(event.currentFingerprintHash.isNotBlank()) { "Policy handover fingerprint is required" }
            val now = System.currentTimeMillis()
            durableStateStore.upsertBoundedDurableState(
                DiagnosticsDurableStateEntity(
                    key = deliveryKey(event.deliveryId),
                    value =
                        PolicyHandoverJson.encodeToString(
                            PolicyHandoverDeliveryEnvelope(event = event),
                        ),
                    updatedAt = now,
                ),
                keyPrefix = PolicyHandoverDeliveryDurableStatePrefix,
                minimumUpdatedAt = now - PolicyHandoverRetentionMaxAgeMs,
                retainCount = PolicyHandoverRetentionLimit,
            )
        }

        override suspend fun acknowledge(deliveryId: String) {
            val key = deliveryKey(deliveryId)
            val current = durableStateStore.getDurableState(key) ?: return
            durableStateStore.clearDurableStateIfCurrent(key, current.value)
        }

        private fun decodeEvent(state: DiagnosticsDurableStateEntity): PolicyHandoverEvent? {
            val envelope =
                runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverDeliveryEnvelope>(state.value) }
                    .getOrNull()
            val event =
                if (envelope == null) {
                    runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverEvent>(state.value) }.getOrNull()
                } else {
                    envelope.event.takeIf { envelope.schemaVersion == PolicyHandoverDeliverySchemaVersion }
                }
            return event?.takeIf { candidate ->
                candidate.deliveryId.isNotBlank() &&
                    candidate.currentFingerprintHash.isNotBlank() &&
                    state.key == deliveryKey(candidate.deliveryId)
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

@Serializable
private data class PolicyHandoverDeliveryEnvelope(
    val schemaVersion: Int = PolicyHandoverDeliverySchemaVersion,
    val event: PolicyHandoverEvent,
)

private const val PolicyHandoverDeliverySchemaVersion = 2
private const val PolicyHandoverRetentionLimit = 64
private const val PolicyHandoverRetentionMaxAgeMs = 7L * 24L * 60L * 60L * 1_000L
private val PolicyHandoverJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }
