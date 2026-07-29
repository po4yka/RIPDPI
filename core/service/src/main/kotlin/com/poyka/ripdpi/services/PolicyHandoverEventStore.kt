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
                    states.forEach { state -> decodeEvent(state.value)?.let { event -> emit(event) } }
                }

        override suspend fun publish(event: PolicyHandoverEvent) {
            require(event.deliveryId.isNotBlank()) { "Policy handover delivery id is required" }
            durableStateStore.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = deliveryKey(event.deliveryId),
                    value = PolicyHandoverJson.encodeToString(event),
                    updatedAt = event.occurredAt,
                ),
            )
        }

        override suspend fun acknowledge(deliveryId: String) {
            val key = deliveryKey(deliveryId)
            val current = durableStateStore.getDurableState(key) ?: return
            durableStateStore.clearDurableStateIfCurrent(key, current.value)
        }

        private fun decodeEvent(value: String): PolicyHandoverEvent? =
            runCatching { PolicyHandoverJson.decodeFromString<PolicyHandoverEvent>(value) }.getOrNull()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PolicyHandoverEventStoreModule {
    @Binds
    @Singleton
    abstract fun bindPolicyHandoverEventStore(store: DefaultPolicyHandoverEventStore): PolicyHandoverEventStore
}

private fun deliveryKey(deliveryId: String): String = "$PolicyHandoverDeliveryDurableStatePrefix$deliveryId"

private val PolicyHandoverJson = Json { ignoreUnknownKeys = false }
