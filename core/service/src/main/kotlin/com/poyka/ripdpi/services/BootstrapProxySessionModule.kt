package com.poyka.ripdpi.services

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(BootstrapProxySessionComponent::class)
internal object BootstrapProxySessionModule {
    @Provides
    @BootstrapProxySessionScope
    fun provideBootstrapProxyRuntimeSupervisor(
        sessionScope: kotlinx.coroutines.CoroutineScope,
        factory: ProxyRuntimeSupervisorFactory,
        networkSnapshotProvider: com.poyka.ripdpi.data.NativeNetworkSnapshotProvider,
    ): ProxyRuntimeSupervisor =
        factory.create(
            scope = sessionScope,
            dispatcher = Dispatchers.IO,
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
