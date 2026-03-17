package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActiveConnectionPolicyStore
    private constructor(
        serviceRuntimeRegistry: ServiceRuntimeRegistry,
        scope: CoroutineScope,
        @Suppress("UNUSED_PARAMETER")
        constructorToken: Any,
    ) : ActiveConnectionPolicyStore {
        companion object {
            private object ConstructionToken

            fun createForTests(
                serviceRuntimeRegistry: ServiceRuntimeRegistry = DefaultServiceRuntimeRegistry(),
                scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ): DefaultActiveConnectionPolicyStore =
                DefaultActiveConnectionPolicyStore(
                    serviceRuntimeRegistry = serviceRuntimeRegistry,
                    scope = scope,
                    constructorToken = ConstructionToken,
                )
        }

        @Inject
        constructor(
            serviceRuntimeRegistry: ServiceRuntimeRegistry,
            @ApplicationScope scope: CoroutineScope,
        ) : this(
            serviceRuntimeRegistry = serviceRuntimeRegistry,
            scope = scope,
            constructorToken = ConstructionToken,
        )

        override val activePolicies: StateFlow<Map<Mode, ActiveConnectionPolicy>> =
            serviceRuntimeRegistry.runtimes
                .flatMapLatest { runtimes ->
                    val policyFlows =
                        Mode.entries.mapNotNull { mode ->
                            runtimes[mode]?.activeConnectionPolicy?.map { policy -> mode to policy }
                        }
                    if (policyFlows.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(policyFlows) { pairs ->
                            EnumMap<Mode, ActiveConnectionPolicy>(Mode::class.java).apply {
                                pairs.forEach { (mode, policy) ->
                                    if (policy != null) {
                                        put(mode, policy)
                                    }
                                }
                            }
                        }
                    }
                }.stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyMap(),
                )
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
