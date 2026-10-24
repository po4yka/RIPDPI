package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.Mode
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Singleton

/** Hilt wiring for the urltest latency prober and its lifecycle coordinator. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorUrltestModule {
    @Binds
    @Singleton
    abstract fun bindMemberLatencyProbe(probe: RelayPayloadMemberLatencyProbe): MemberLatencyProbe

    companion object {
        @Provides
        @Singleton
        internal fun provideSelectorUrltestProber(
            selectionStore: SelectorSelectionStore,
            probe: MemberLatencyProbe,
            scopeProvider: CurrentSelectorProbeScopeProvider,
            qualityStore: com.poyka.ripdpi.data.ProfileQualityStore,
            budgetLedger: com.poyka.ripdpi.data.ProfileQualityBudgetLedger,
        ): SelectorUrltestProber =
            SelectorUrltestProber(
                selectionStore = selectionStore,
                probe = probe,
                scopeProvider = scopeProvider,
                monotonicMillis = android.os.SystemClock::elapsedRealtime,
                qualityStore = qualityStore,
                reserveBudget = budgetLedger::reserve,
            )

        @Provides
        @Singleton
        fun provideSelectorUrltestCoordinator(
            @SelectorReloadScope scope: CoroutineScope,
            groupRepository: ProxyGroupRepository,
            prober: SelectorUrltestProber,
            network: com.poyka.ripdpi.services.PhysicalNetworkObserver,
            settings: com.poyka.ripdpi.data.AppSettingsRepository,
            service: com.poyka.ripdpi.data.ServiceStateStore,
            activeGroup: com.poyka.ripdpi.data.selector.SelectorActiveGroupStore,
        ): SelectorUrltestCoordinator =
            SelectorUrltestCoordinator(
                scope = scope,
                groupRepository = groupRepository,
                prober = prober,
                scopeChanges =
                    merge(
                        network.changes.map { },
                        settings.settings.map { },
                        service.status.map { },
                        activeGroup.choiceChanges.map { },
                    ),
            )

        /** Adapts the urltest coordinator to the service-lifecycle multibinding. */
        @Provides
        @Singleton
        @IntoSet
        fun provideSelectorUrltestLifecycleListener(
            coordinator: SelectorUrltestCoordinator,
        ): SelectorRuntimeLifecycleListener =
            object : SelectorRuntimeLifecycleListener {
                override fun start(owner: Mode) = coordinator.start(owner)

                override fun stop(owner: Mode) = coordinator.stop(owner)
            }
    }
}
