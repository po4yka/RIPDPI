package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

internal interface ProxyRuntimeSupervisorFactory {
    fun create(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        networkSnapshotProvider: NativeNetworkSnapshotProvider,
    ): ProxyRuntimeSupervisor
}

@Singleton
internal class DefaultProxyRuntimeSupervisorFactory
    @Inject
    constructor(
        private val ripDpiProxyFactory: RipDpiProxyFactory,
    ) : ProxyRuntimeSupervisorFactory {
        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkSnapshotProvider: NativeNetworkSnapshotProvider,
        ): ProxyRuntimeSupervisor =
            ProxyRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                ripDpiProxyFactory = ripDpiProxyFactory,
                networkSnapshotProvider = networkSnapshotProvider,
            )
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProxyRuntimeSupervisorFactoryModule {
    @Binds
    @Singleton
    abstract fun bindProxyRuntimeSupervisorFactory(
        factory: DefaultProxyRuntimeSupervisorFactory,
    ): ProxyRuntimeSupervisorFactory
}
