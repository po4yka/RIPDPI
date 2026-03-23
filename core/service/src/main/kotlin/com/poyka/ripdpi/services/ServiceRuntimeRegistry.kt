package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.EnumMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ServiceRuntimeHandle {
    val runtimeId: String
    val mode: Mode
    val activeConnectionPolicy: StateFlow<ActiveConnectionPolicy?>
}

abstract class ServiceRuntimeSession
    protected constructor(
        final override val mode: Mode,
        final override val runtimeId: String = UUID.randomUUID().toString(),
    ) : ServiceRuntimeHandle {
        private val activeConnectionPolicyState = MutableStateFlow<ActiveConnectionPolicy?>(null)

        final override val activeConnectionPolicy: StateFlow<ActiveConnectionPolicy?> =
            activeConnectionPolicyState
                .asStateFlow()

        val currentActiveConnectionPolicy: ActiveConnectionPolicy?
            get() = activeConnectionPolicy.value

        fun updateActiveConnectionPolicy(policy: ActiveConnectionPolicy?) {
            activeConnectionPolicyState.value = policy
        }

        fun clearActiveConnectionPolicy() {
            activeConnectionPolicyState.value = null
        }
    }

class VpnRuntimeSession(
    runtimeId: String = UUID.randomUUID().toString(),
) : ServiceRuntimeSession(
        mode = Mode.VPN,
        runtimeId = runtimeId,
    ),
    HandoverAwareSession {
    override var pendingNetworkHandoverClass: String? = null
    override var lastSuccessfulHandoverFingerprintHash: String? = null
    override var lastSuccessfulHandoverAt: Long = 0L
}

class ProxyRuntimeSession(
    runtimeId: String = UUID.randomUUID().toString(),
) : ServiceRuntimeSession(
        mode = Mode.Proxy,
        runtimeId = runtimeId,
    ),
    HandoverAwareSession {
    override var pendingNetworkHandoverClass: String? = null
    override var lastSuccessfulHandoverFingerprintHash: String? = null
    override var lastSuccessfulHandoverAt: Long = 0L
}

interface ServiceRuntimeRegistry {
    val runtimes: StateFlow<Map<Mode, ServiceRuntimeHandle>>

    fun register(handle: ServiceRuntimeHandle)

    fun unregister(
        mode: Mode,
        runtimeId: String,
    )

    fun current(mode: Mode): ServiceRuntimeHandle? = runtimes.value[mode]
}

@Singleton
class DefaultServiceRuntimeRegistry
    @Inject
    constructor() : ServiceRuntimeRegistry {
        private val state = MutableStateFlow<Map<Mode, ServiceRuntimeHandle>>(emptyMap())

        override val runtimes: StateFlow<Map<Mode, ServiceRuntimeHandle>> = state.asStateFlow()

        override fun register(handle: ServiceRuntimeHandle) {
            state.update { current ->
                EnumMap<Mode, ServiceRuntimeHandle>(Mode::class.java).apply {
                    putAll(current)
                    put(handle.mode, handle)
                }
            }
        }

        override fun unregister(
            mode: Mode,
            runtimeId: String,
        ) {
            state.update { current ->
                if (current[mode]?.runtimeId != runtimeId) {
                    return@update current
                }
                EnumMap<Mode, ServiceRuntimeHandle>(Mode::class.java).apply {
                    putAll(current)
                    remove(mode)
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceRuntimeRegistryModule {
    @Binds
    @Singleton
    abstract fun bindServiceRuntimeRegistry(registry: DefaultServiceRuntimeRegistry): ServiceRuntimeRegistry
}
