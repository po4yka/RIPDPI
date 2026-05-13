package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(BootstrapProxySessionComponent::class)
internal object BootstrapProxySessionModule {
    @Provides
    @BootstrapProxySessionScope
    fun provideBootstrapProxyRuntimeSupervisor(
        sessionScope: kotlinx.coroutines.CoroutineScope,
        factory: ProxyRuntimeSupervisorFactory,
        networkSnapshotProvider: com.poyka.ripdpi.data.NativeNetworkSnapshotProvider,
        dispatchers: AppCoroutineDispatchers,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = sessionScope,
            dispatcher = dispatchers.io,
            networkSnapshotProvider = networkSnapshotProvider,
        )
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BootstrapProxyRuntimeSupervisorSessionFactoryModule {
    @Binds
    abstract fun bindBootstrapProxyRuntimeSupervisorSessionFactory(
        factory: DefaultBootstrapProxyRuntimeSupervisorSessionFactory,
    ): BootstrapProxyRuntimeSupervisorSessionFactory
}
