package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
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
        appliesPendingHostAutolearnErase: Boolean = false,
    ): ProxyRuntimeSupervisor
}

@Singleton
internal class DefaultProxyRuntimeSupervisorFactory
    @Inject
    constructor(
        private val ripDpiProxyFactory: RipDpiProxyFactory,
        private val hostAutolearnStoreController: HostAutolearnStoreController? = null,
    ) : ProxyRuntimeSupervisorFactory {
        override fun create(
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            networkSnapshotProvider: NativeNetworkSnapshotProvider,
            appliesPendingHostAutolearnErase: Boolean,
        ): ProxyRuntimeSupervisor =
            ProxyRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                ripDpiProxyFactory = ripDpiProxyFactory,
                networkSnapshotProvider = networkSnapshotProvider,
                beforeNativeStart = if (appliesPendingHostAutolearnErase) ::applyPendingHostAutolearnErase else ({}),
            )

        private fun applyPendingHostAutolearnErase() {
            if (hostAutolearnStoreController?.applyPendingErase() == false) {
                Logger.w { "Pending learned-hosts erase could not delete the store; it stays pending" }
            }
        }
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
