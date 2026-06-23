package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.proxyimport.SelectorReloadScope
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Hilt wiring for the urltest latency prober and its lifecycle coordinator. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorUrltestModule {
    @Binds
    @Singleton
    abstract fun bindMemberLatencyProbe(probe: TcpConnectMemberLatencyProbe): MemberLatencyProbe

    companion object {
        @Provides
        @Singleton
        fun provideSelectorUrltestProber(
            selectionStore: SelectorSelectionStore,
            probe: MemberLatencyProbe,
        ): SelectorUrltestProber =
            SelectorUrltestProber(
                selectionStore = selectionStore,
                probe = probe,
            )

        @Provides
        @Singleton
        fun provideSelectorUrltestCoordinator(
            @SelectorReloadScope scope: CoroutineScope,
            groupRepository: ProxyGroupRepository,
            prober: SelectorUrltestProber,
        ): SelectorUrltestCoordinator =
            SelectorUrltestCoordinator(
                scope = scope,
                groupRepository = groupRepository,
                prober = prober,
            )

        /** Adapts the urltest coordinator to the service-lifecycle multibinding. */
        @Provides
        @Singleton
        @IntoSet
        fun provideSelectorUrltestLifecycleListener(
            coordinator: SelectorUrltestCoordinator,
        ): SelectorRuntimeLifecycleListener =
            object : SelectorRuntimeLifecycleListener {
                override fun start() = coordinator.start()

                override fun stop() = coordinator.stop()
            }
    }
}
