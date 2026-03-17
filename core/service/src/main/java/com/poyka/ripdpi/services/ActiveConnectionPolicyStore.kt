package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow

data class ActiveConnectionPolicy(
    val mode: Mode,
    val policy: RememberedNetworkPolicyJson,
    val matchedPolicy: RememberedNetworkPolicyEntity? = null,
    val usedRememberedPolicy: Boolean = false,
    val fingerprintHash: String? = null,
    val policySignature: String,
    val appliedAt: Long,
    val restartReason: String = "initial_start",
    val handoverClassification: String? = null,
)

interface ActiveConnectionPolicyStore {
    val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>>

    fun set(policy: ActiveConnectionPolicy)

    fun clear(mode: Mode)

    fun current(mode: Mode): ActiveConnectionPolicy? = activePolicies.value[mode]

    fun observe(mode: Mode): Flow<ActiveConnectionPolicy?> = activePolicies.map { policies -> policies[mode] }
}

@Singleton
class DefaultActiveConnectionPolicyStore
    @Inject
    constructor() : ActiveConnectionPolicyStore {
        private val state = MutableStateFlow<Map<Mode, ActiveConnectionPolicy>>(emptyMap())

        override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> = state.asStateFlow()

        override fun set(policy: ActiveConnectionPolicy) {
            state.value =
                EnumMap<Mode, ActiveConnectionPolicy>(Mode::class.java).apply {
                    putAll(state.value)
                    put(policy.mode, policy)
                }
        }

        override fun clear(mode: Mode) {
            state.value =
                EnumMap<Mode, ActiveConnectionPolicy>(Mode::class.java).apply {
                    putAll(state.value)
                    remove(mode)
                }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ActiveConnectionPolicyStoreModule {
    @Binds
    @Singleton
    abstract fun bindActiveConnectionPolicyStore(
        store: DefaultActiveConnectionPolicyStore,
    ): ActiveConnectionPolicyStore
}
