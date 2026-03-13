package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class PolicyHandoverEvent(
    val mode: Mode,
    val previousFingerprintHash: String? = null,
    val currentFingerprintHash: String,
    val classification: String,
    val usedRememberedPolicy: Boolean,
    val policySignature: String,
    val occurredAt: Long,
)

interface PolicyHandoverEventStore {
    val events: SharedFlow<PolicyHandoverEvent>

    fun publish(event: PolicyHandoverEvent)
}

@Singleton
class DefaultPolicyHandoverEventStore
    @Inject
    constructor() : PolicyHandoverEventStore {
        private val state = MutableSharedFlow<PolicyHandoverEvent>(extraBufferCapacity = 32)

        override val events: SharedFlow<PolicyHandoverEvent> = state.asSharedFlow()

        override fun publish(event: PolicyHandoverEvent) {
            state.tryEmit(event)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PolicyHandoverEventStoreModule {
    @Binds
    @Singleton
    abstract fun bindPolicyHandoverEventStore(
        store: DefaultPolicyHandoverEventStore,
    ): PolicyHandoverEventStore
}
