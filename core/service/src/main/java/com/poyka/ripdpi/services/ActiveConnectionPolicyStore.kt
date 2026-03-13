package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val activePolicy: StateFlow<ActiveConnectionPolicy?>

    fun set(policy: ActiveConnectionPolicy)

    fun clear()
}

@Singleton
class DefaultActiveConnectionPolicyStore
    @Inject
    constructor() : ActiveConnectionPolicyStore {
        private val state = MutableStateFlow<ActiveConnectionPolicy?>(null)

        override val activePolicy: StateFlow<ActiveConnectionPolicy?> = state.asStateFlow()

        override fun set(policy: ActiveConnectionPolicy) {
            state.value = policy
        }

        override fun clear() {
            state.value = null
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
